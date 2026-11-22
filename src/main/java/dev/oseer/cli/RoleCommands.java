package dev.oseer.cli;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.RoutingSettings;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.DecisionClient;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.ledger.HomeCorpus;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;
import dev.oseer.run.TaskLoop;
import dev.oseer.run.TaskRouting;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.oseer.cli.CliOptions.defaultRunId;
import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.CliOptions.option;
import static dev.oseer.cli.RunPresentation.narration;

/** Commands that run or preview a single role or task classification. */
final class RoleCommands {
    private static final Set<String> CLASSIFY_FLAGS = Set.of("--dry-run", "--quiet");

    private RoleCommands() {}

    /**
     * Run one role. `--dry-run` resolves the vendor and writes the exact prompt without
     * spending anything — the cheapest way to find out whether the setup is right.
     */
    static int role(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("role requires a role name and a task, "
                    + "for example: oseer role reviewer my-task --dry-run");
        }
        String roleName = args[1];
        String taskSelector = args[2];
        boolean dryRun = hasFlag(args, "--dry-run");
        String runId = option(args, "--run-id", defaultRunId());
        String implementerVendor = option(args, "--implementer-vendor", null);
        String contextPath = option(args, "--context", null);
        String pinnedProfile = option(args, "--use", null);

        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), taskSelector);
        UserConfig user = UserConfig.load();
        Set<String> writerVendors = new LinkedHashSet<>(loaded.resolved().priorWriters());
        if (implementerVendor != null && !implementerVendor.isBlank()) writerVendors.add(implementerVendor);
        RoleResolver.Writers writers = writerVendors.isEmpty() ? RoleResolver.Writers.NONE
                : new RoleResolver.Writers(writerVendors, Set.of(), true,
                        "same_vendor_peer".equals(loaded.resolved().reviewAssurance()));
        RoleRunner runner = new RoleRunner(new ProcessRunner());
        if (pinnedProfile != null) runner.pinRoleForRun(roleName, pinnedProfile);
        RoleRunner.Outcome outcome;
        try {
            outcome = runner.run(loaded, user, roleName, runId, writers,
                    contextPath == null ? null : Path.of(contextPath), List.of(), dryRun, RoleRunner.UNSTAGED);
        } catch (HomeCorpus.UnavailableException unavailable) {
            return CliOutput.report(ledgerUnavailable(roleName, runId, unavailable));
        }

        var ledger = new EvidenceLedger(loaded.root(), runId, user.home());
        return CliOutput.report(roleReport(outcome, runId, ledger));
    }

    private static Map<String, Object> ledgerUnavailable(String role, String runId,
                                                          HomeCorpus.UnavailableException unavailable) {
        Map<String, Object> refused = new LinkedHashMap<>();
        refused.put("ok", false);
        refused.put("code", "ledger_unavailable");
        refused.put("role", role);
        refused.put("run_id", runId);
        refused.put("message", unavailable.getMessage());
        refused.put("next", "restore write access to the home corpus, then run this again");
        return refused;
    }

    private static Map<String, Object> roleReport(RoleRunner.Outcome outcome, String runId, EvidenceLedger ledger) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("role", outcome.role());
        result.put("profile", outcome.profile());
        result.put("vendor", outcome.vendor());
        result.put("rejected_profiles", outcome.rejected());
        result.put("run_id", runId);
        if (outcome.report() != null) result.put("report", outcome.report().toString());
        if (outcome.details() != null) result.put("details", outcome.details());
        ledger.recordCorpusVisibility(result);
        return result;
    }

    static int classify(String[] args) throws Exception {
        if (args.length < 2 || args[1].startsWith("--")) {
            throw new IllegalArgumentException("classify requires a task id or YAML path");
        }
        for (int index = 2; index < args.length; index++) {
            if (!CLASSIFY_FLAGS.contains(args[index])) {
                throw new IllegalArgumentException("classify accepts TASK, --dry-run and --quiet");
            }
        }
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        UserConfig user = UserConfig.load();
        ProcessRunner processes = new ProcessRunner();
        List<TaskRouting.Target> targets = TaskLoop.classificationTargets(loaded.root(), loaded, user,
                profile -> RoleRunner.executableFound(processes, profile));
        if (hasFlag(args, "--dry-run")) {
            return CliOutput.report(Map.of(
                    "ok", true,
                    "dry_run", true,
                    "classifier", RoutingSettings.load(user.home()).classifier(),
                    "task", loaded.resolved().id(),
                    "target_stages", targets.stream().map(TaskRouting.Target::stage).toList()));
        }
        TaskRouting.Result result = TaskRouting.using(new DecisionClient(), true)
                .route(user, loaded.root(), loaded.resolved(), targets, narration(args)::line);
        Map<String, Object> report = new LinkedHashMap<>(result.report());
        report.put("ok", !report.containsKey("fallback"));
        return CliOutput.report(report);
    }
}
