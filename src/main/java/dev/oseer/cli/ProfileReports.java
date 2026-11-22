package dev.oseer.cli;

import dev.oseer.config.DecisionProfile;
import dev.oseer.config.Profile;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.CredentialResolver;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleRunner;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON views for profile discovery and verification. */
final class ProfileReports {
    private ProfileReports() {}

    static Map<String, Object> listing(UserConfig user) throws Exception {
        ProcessRunner processes = new ProcessRunner();
        CredentialResolver credentials = new CredentialResolver();
        List<Map<String, Object>> profiles = user.profiles().values().stream()
                .map(profile -> roleProfile(profile, processes, credentials))
                .toList();
        List<Map<String, Object>> classifiers = DecisionProfile.load(user.home()).values().stream()
                .map(ProfileReports::classifierProfile)
                .toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", user.policyPresent() && !profiles.isEmpty());
        report.put("home", user.home().toString());
        report.put("policy_present", user.policyPresent());
        report.put("profiles", profiles);
        report.put("classifier_profiles", classifiers);
        report.put("problems", user.problems());
        report.put("dangling_policy_references", user.danglingProfileReferences());
        if (!user.policyPresent()) {
            report.put("hint", "run `oseer setup` to create a starter configuration");
        }
        return report;
    }

    static Map<String, Object> unknownProfile(UserConfig user, String name) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", false);
        report.put("code", "profile_not_found");
        report.put("profile", name);
        report.put("known_profiles", List.copyOf(user.profiles().keySet()));
        report.put("problems", user.problems());
        return report;
    }

    private static Map<String, Object> roleProfile(Profile profile, ProcessRunner processes,
                                                    CredentialResolver credentials) {
        boolean api = "api".equals(profile.runner());
        boolean hasHarness = profile.harness() != null;
        boolean verified = profile.verified();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("profile", profile.name());
        row.put("role", profile.role());
        row.put("vendor", profile.vendor());
        row.put("model", profile.model());
        row.put("effort", profile.effort());
        row.put("command", profile.command());
        row.put("runner", profile.runner());
        if (api || "local".equals(profile.runner())) {
            row.put("endpoint", profile.endpoint());
        }
        if (api) {
            row.put("connection", profile.connectionRef());
            row.put("model_vendor", profile.modelVendor());
            row.put("api_fingerprint", profile.apiFingerprint());
            row.put("auth", credentials.status(profile.apiConnection()));
            row.put("execution", "text_completion_only");
        }
        row.put("read_only", profile.readOnly());
        row.put("verified", verified);
        boolean eligible = verified && (!(api || hasHarness) || RoleRunner.executableFound(processes, profile));
        row.put("eligible", eligible);

        if (hasHarness) {
            appendHarness(row, profile, verified, eligible);
        } else if (!verified) {
            row.put("blocked_because", "profile is unverified; fill verification.verified_on "
                    + "only after running its probe");
            row.put("probe", profile.verificationProbe());
            row.put("verify_with", "oseer profiles --verify " + profile.name());
        }
        return row;
    }

    private static void appendHarness(Map<String, Object> row, Profile profile, boolean verified, boolean eligible) {
        Path executable = profile.harnessExecutable();
        row.put("harness", profile.harness());
        row.put("connection", profile.connectionRef());
        row.put("model_vendor", profile.modelVendor());
        row.put("coding_tools_verified", verified);
        row.put("harness_executable", executable == null ? null : executable.toString());
        if (!verified) {
            row.put("blocked_because", "coding capability receipt is absent or stale; run oseer profiles --verify "
                    + profile.name());
        } else if (!eligible) {
            row.put("blocked_because", "coding connection credential is unavailable");
        }
    }

    private static Map<String, Object> classifierProfile(DecisionProfile profile) {
        return Map.of("profile", profile.name(), "model", profile.model(), "reported_model", profile.reportedModel(),
                "connection", profile.connection(), "execution", "typed_decisions_only");
    }
}
