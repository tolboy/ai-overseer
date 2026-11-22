package dev.oseer.cli;

import dev.oseer.Main;
import dev.oseer.config.ConnectionCommand;
import dev.oseer.config.Policy;
import dev.oseer.config.Profile;
import dev.oseer.config.UserConfig;
import dev.oseer.config.Workflow;
import dev.oseer.execution.CredentialAudit;
import dev.oseer.execution.CredentialResolver;
import dev.oseer.execution.orca.OrcaClient;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleRunner;
import dev.oseer.run.DoCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Readiness checks and the JSON report consumed by `oseer doctor`. */
final class DoctorReport {
    private static final int MINIMUM_JAVA_FEATURE = 21;

    private DoctorReport() {}

    static Map<String, Object> inspect(Path directory, ProcessRunner processes) throws Exception {
        Map<String, Object> orca = new OrcaClient(processes).status(directory);
        int javaFeature = Runtime.version().feature();
        boolean javaSupported = javaFeature >= MINIMUM_JAVA_FEATURE;
        boolean projectConfig = Files.isRegularFile(directory.resolve(".oseer/project.yaml"));
        boolean machineGatesReady = javaSupported && projectConfig;
        UserConfig user = UserConfig.load();
        RoleReadiness roles = RoleReadiness.check(user, processes);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", machineGatesReady);
        report.put("oseer_version", Main.VERSION);
        report.put("java_version", System.getProperty("java.version"));
        report.put("java_feature", (long) javaFeature);
        report.put("java_21_or_newer", javaSupported);
        report.put("argument_encoding", argumentEncoding());
        report.put("project_config_found", projectConfig);
        report.put("machine_gates_ready", machineGatesReady);
        report.put("orca", orca);
        recordCredentialStatus(report, user);
        report.put("user_config", userConfig(user, roles));
        report.put("workflow", workflow(user.policy()));
        report.put("role_execution_ready", roles.ready());
        if (!roles.ready()) {
            report.put("role_execution_blocked_by", roles.blockedBy(user));
        }
        report.put("direct_cli_ready", roles.ready());
        report.put("orca_adapter", Map.of(
                "available", Boolean.TRUE.equals(orca.get("available")),
                "orchestration_contract", Boolean.TRUE.equals(orca.get("orchestration_contract")),
                "creates_worktrees", false,
                "completion", "worker_done or dispatch-show settlement only; terminal text is not evidence"));
        report.put("note", readinessNote(machineGatesReady, roles.ready()));
        return report;
    }

    private static void recordCredentialStatus(Map<String, Object> report, UserConfig user) {
        List<Map<String, Object>> unresolved = CredentialAudit.unresolved(user, new CredentialResolver());
        report.put("credentials_unresolved", unresolved);
        if (unresolved.isEmpty()) return;
        report.put("credentials_warning", "this process cannot resolve " + unresolved.size()
                + " credential reference(s) the roster needs: " + unresolved.stream()
                .map(row -> row.get("credential_ref") + " (" + row.get("connection") + ")").toList()
                + ". Runs started from here, including from `oseer dashboard`, stop at the model check."
                + " Start the process from the shell or launcher that exports them.");
    }

    private static Map<String, Object> argumentEncoding() {
        Map<String, Object> encoding = new LinkedHashMap<>();
        encoding.put("sun_jnu_encoding", System.getProperty("sun.jnu.encoding"));
        encoding.put("native_encoding", System.getProperty("native.encoding"));
        encoding.put("file_encoding", System.getProperty("file.encoding"));
        boolean argumentsSafe = DoCommand.argumentsCanCarryNonAscii();
        encoding.put("non_ascii_arguments_safe", argumentsSafe);
        if (!argumentsSafe) {
            encoding.put("consequence", "non-ASCII text in a command-line argument reaches Overseer as "
                    + "'?'. No JVM flag changes this. Use `oseer do --goal-file <utf8 file>`, or "
                    + "enable the Windows setting \"Use Unicode UTF-8 for worldwide language support\".");
        }
        return encoding;
    }

    private static Map<String, Object> userConfig(UserConfig user, RoleReadiness roles) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("home", user.home().toString());
        summary.put("policy_present", user.policyPresent());
        summary.put("profiles_loaded", (long) user.profiles().size());
        summary.put("verified_profiles", roles.eligible());
        summary.put("unverified_profiles", roles.unverified());
        summary.put("problems", user.problems());
        summary.put("dangling_policy_references", user.danglingProfileReferences());
        summary.put("connections", user.connections().values().stream()
                .map(connection -> ConnectionCommand.view(connection, System::getenv)).toList());
        return summary;
    }

    private static Map<String, Object> workflow(Policy policy) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("declared", policy != null && policy.workflowDeclared());
        report.put("stages", (policy != null ? policy.workflow() : Workflow.builtIn()).toList());
        return report;
    }

    private static String readinessNote(boolean machineGatesReady, boolean roleExecutionReady) {
        if (!machineGatesReady) {
            return "Machine gates need Java " + MINIMUM_JAVA_FEATURE + "+ and .oseer/project.yaml in the target repository";
        }
        if (!roleExecutionReady) {
            return "Machine gates are ready; role execution waits on a verified profile in ~/.oseer.";
        }
        return "Machine gates and Direct CLI role execution are ready. Orca is an optional runner "
                + "and only attaches to an existing worktree.";
    }

    private record RoleReadiness(boolean ready, List<String> eligible, List<String> unverified) {
        static RoleReadiness check(UserConfig user, ProcessRunner processes) {
            List<String> eligible = user.profiles().values().stream()
                    .filter(Profile::verified)
                    .filter(profile -> !"api".equals(profile.runner()) || RoleRunner.executableFound(processes, profile))
                    .map(Profile::name).toList();
            List<String> unverified = user.profiles().values().stream()
                    .filter(profile -> !profile.verified()).map(Profile::name).toList();
            boolean ready = user.policyPresent() && user.policy() != null
                    && !eligible.isEmpty() && user.danglingProfileReferences().isEmpty();
            return new RoleReadiness(ready, eligible, unverified);
        }

        String blockedBy(UserConfig user) {
            if (!user.policyPresent()) {
                return "no ~/.oseer/policy.yaml — run `oseer setup`";
            }
            if (!eligible.isEmpty()) {
                return "policy references profiles that do not load: " + user.danglingProfileReferences();
            }
            if (unverified.size() == user.profiles().size()) {
                return "every profile is unverified; run its verification probe";
            }
            return "verified API profiles have unavailable connection credentials; inspect connections list";
        }
    }
}
