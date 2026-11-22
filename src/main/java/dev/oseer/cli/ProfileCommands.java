package dev.oseer.cli;

import dev.oseer.config.CheckCommand;
import dev.oseer.config.CodexConfig;
import dev.oseer.config.ConfigFileStore;
import dev.oseer.config.ConnectionCommand;
import dev.oseer.config.DecisionProfile;
import dev.oseer.config.Profile;
import dev.oseer.config.ProfileVerifier;
import dev.oseer.config.RosterCommand;
import dev.oseer.config.ServiceMigration;
import dev.oseer.config.UserConfig;
import dev.oseer.config.UserSetup;
import dev.oseer.execution.DecisionClient;
import dev.oseer.execution.DecisionCompatibility;
import dev.oseer.execution.orca.OrcaProbe;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;

import java.io.ByteArrayInputStream;
import java.io.Console;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.CliOptions.option;
import static dev.oseer.cli.RunPresentation.narration;

/** Operator commands for profiles, connection credentials, and routing configuration. */
final class ProfileCommands {
    private static final Duration VERIFICATION_TIMEOUT = Duration.ofMinutes(10);

    private ProfileCommands() {}

    /** Create a starter ~/.oseer so the first dry run explains itself. Never overwrites. */
    static int setup() throws Exception {
        UserSetup.Result outcome = new UserSetup().run(UserConfig.defaultHome());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("home", outcome.home().toString());
        result.put("created", outcome.created());
        result.put("skipped_existing", outcome.skipped());
        result.put("next", "oseer profiles, then oseer role reviewer <task> --dry-run");
        CliOutput.json(result);
        return 0;
    }

    static int profiles(String[] args) throws Exception {
        if (hasFlag(args, "migrate-services")) {
            return migrateServices(args);
        }
        String verify = option(args, "--verify", null);
        if (verify != null) {
            return verifyProfile(verify, hasFlag(args, "--confirm"), option(args, "--reported-model", null));
        }
        return CliOutput.report(ProfileReports.listing(UserConfig.load()));
    }

    /** Stamp the profile only after its own probe succeeds against the loaded configuration. */
    private static int verifyProfile(String name, boolean confirm, String reportedModel) throws Exception {
        Map<Path, ConfigFileStore.Snapshot> sources = new LinkedHashMap<>();
        UserConfig user = UserConfig.load(UserConfig.defaultHome(), sources);
        DecisionProfile classifier = DecisionProfile.load(user.home()).get(name);
        if (classifier != null) {
            return CliOutput.report(DecisionCompatibility.verify(new DecisionClient(), classifier, user,
                    reportedModel, confirm));
        }
        Profile profile = user.profiles().get(name);
        if (profile == null) {
            return CliOutput.report(ProfileReports.unknownProfile(user, name));
        }

        ProfileVerifier verifier = new ProfileVerifier(new ProcessRunner(), ProfileCommands::ownProbe);
        ProfileVerifier.Probe probe = verifier.run(profile, user.home(), VERIFICATION_TIMEOUT, Path.of("."));
        Map<String, Object> result = new LinkedHashMap<>(ProfileVerifier.report(profile, probe));
        result.put("already_verified", profile.verified());

        if (!probe.ok()) {
            result.put("ok", false);
            result.put("code", "probe_failed");
            result.put("next", ProfileVerifier.nextStep(probe));
            CliOutput.json(result);
            return 1;
        }

        Path file = user.home().resolve("profiles").resolve(name + ".yaml");
        if ("api".equals(profile.runner()) || profile.harness() != null) {
            Profile current = UserConfig.load(user.home()).profiles().get(name);
            Object checked = Json.parseObject(Files.readString(probe.transcript()))
                    .get("profile_fingerprint");
            if (current == null || !Objects.equals(checked, current.apiFingerprint())) {
                result.put("ok", false);
                result.put("code", "role_api_profile_changed");
                result.put("message", "API configuration changed during verification; nothing was stamped");
                CliOutput.json(result);
                return 1;
            }
        }
        ProfileVerifier.Stamp stamp = verifier.stamp(file, profile, LocalDate.now(), sources.get(file).sha256());
        result.put("ok", stamp.written());
        result.put("code", stamp.code());
        result.put("message", stamp.message());
        result.put("profile_file", stamp.profileFile().toString());
        result.put("stamped", stamp.written());
        if (stamp.written()) result.put("verified_on", stamp.date());
        return CliOutput.report(result);
    }

    /**
     * `oseer profiles migrate-services [--apply] [--map NAME=SERVICE[:ACCOUNT]]... | --rollback ID`.
     * Preview by default. Ambiguous profiles are bound only by an explicit --map.
     */
    private static int migrateServices(String[] args) throws Exception {
        var migration = new ServiceMigration(UserConfig.defaultHome(), CodexConfig.load());
        String rollback = option(args, "--rollback", null);
        Map<String, Object> report;
        try {
            if (rollback != null) {
                report = migration.rollback(rollback);
            } else {
                List<ServiceMigration.Mapping> mappings = migrationMappings(args);
                report = hasFlag(args, "--apply") ? migration.apply(mappings) : migration.preview(mappings);
            }
        } catch (IllegalArgumentException | IllegalStateException refused) {
            report = Map.of("ok", false, "code", "migration_refused", "error", String.valueOf(refused.getMessage()));
        }
        return CliOutput.report(report);
    }

    private static List<ServiceMigration.Mapping> migrationMappings(String[] args) {
        List<ServiceMigration.Mapping> mappings = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String flag = args[index];
            if ("--map".equals(flag) && index + 1 < args.length) {
                mappings.add(ServiceMigration.Mapping.parse(args[++index]));
            } else if (flag.startsWith("--map=")) {
                mappings.add(ServiceMigration.Mapping.parse(flag.substring("--map=".length())));
            }
        }
        return mappings;
    }

    /**
     * See and change which profile fills which role, and a profile's model/effort, without
     * a YAML round-trip. Line edits only; comments and the rest of the file stay.
     */
    static int roster(String[] args) throws Exception {
        RosterCommand.Outcome outcome = new RosterCommand().run(UserConfig.defaultHome(), args);
        if (outcome.text() != null) System.out.print(outcome.text());
        else CliOutput.json(outcome.report());
        return outcome.exitCode();
    }

    static int connections(String[] args) throws Exception {
        String operation = args.length >= 2 ? args[1] : "";
        boolean hiddenCode = "auth-login".equals(operation) && hasFlag(args, "--headless");
        // Headless login prints its authorization URL before reading the code.
        Supplier<InputStream> input = () -> credentialInput(operation, hiddenCode);
        Map<String, Object> report = new ConnectionCommand()
                .runWithInput(UserConfig.defaultHome(), args, input);
        return CliOutput.report(report);
    }

    private static InputStream credentialInput(String operation, boolean hiddenCode) {
        Console console = System.console();
        if (console == null || !("auth-set".equals(operation) || hiddenCode)) {
            return System.in;
        }
        char[] password = console.readPassword(hiddenCode
                ? "Authorization code (hidden; it is not written to the connection file): "
                : "Credential (hidden; it is not an argument and is not written to the connection file): ");
        if (password == null) {
            throw new IllegalArgumentException("credential input was closed");
        }
        try {
            return new ByteArrayInputStream(new String(password).getBytes(StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static int check(String[] args) throws Exception {
        Map<String, Object> report = CheckCommand.run(args, UserConfig.load(),
                Path.of(".").toAbsolutePath().normalize(), new ProcessRunner(), narration(args)::line);
        return CliOutput.report(report);
    }

    /** {@code oseer probe orca ...}: the verification probe for a runner: orca profile. */
    static int probe(String[] args) {
        var outcome = new OrcaProbe(new ProcessRunner())
                .run(OrcaProbe.parse(args));
        outcome.lines().forEach(System.out::println);
        return outcome.exitCode();
    }

    /** A {@code oseer probe} named by a profile's verification, run in this process. */
    private static ProcessRunner.Result ownProbe(List<String> argv, Duration ignoredTimeout) {
        long started = System.nanoTime();
        String[] args = argv.toArray(String[]::new);
        if (args.length < 2 || !"probe".equals(args[0]) || !"orca".equals(args[1])) {
            return new ProcessRunner.Result(argv, 2, false, 0L, "",
                    "oseer probe knows one channel: orca", false, false);
        }
        OrcaProbe.Outcome outcome;
        try {
            outcome = new OrcaProbe(new ProcessRunner())
                    .run(OrcaProbe.parse(args));
        } catch (IllegalArgumentException misspelled) {
            return new ProcessRunner.Result(argv, 2, false, 0L, "", misspelled.getMessage(), false, false);
        }
        return new ProcessRunner.Result(argv, outcome.exitCode(), false,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                String.join(System.lineSeparator(), outcome.lines()), "", false, false);
    }
}
