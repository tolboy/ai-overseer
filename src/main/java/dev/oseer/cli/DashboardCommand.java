package dev.oseer.cli;

import dev.oseer.approval.ApprovalException;
import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.config.UserConfig;
import dev.oseer.dashboard.Dashboard;
import dev.oseer.dashboard.DecisionPage;
import dev.oseer.dashboard.DecisionPageOpener;
import dev.oseer.dashboard.RunLauncher;
import dev.oseer.execution.CredentialAudit;
import dev.oseer.execution.CredentialResolver;
import dev.oseer.execution.orca.OrcaClient;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.CliOptions.option;
import static dev.oseer.cli.DecisionSession.awaitDecision;
import static dev.oseer.cli.DecisionSupervisor.superviseGates;

public final class DashboardCommand {
    /** A dashboard page can stay open after the process that started the run has exited. */
    private static final int PAGE_WAIT_MINUTES = (int) Duration.ofDays(7).toMinutes();

    private DashboardCommand() {}

    static int run(String[] args, RunObservation observation) throws Exception {
        Path project = Path.of(option(args, "--project", ".")).toAbsolutePath().normalize();
        int port = Integer.parseInt(option(args, "--port", "0"));
        if (port < 0 || port > 65535) throw new IllegalArgumentException("dashboard port must be 0..65535");
        DecisionPageOpener pages = new DecisionPageOpener();
        OrcaClient orca = new OrcaClient(new ProcessRunner());
        Dashboard.DecisionOpener opener = runId -> openDecision(pages, project, runId,
                new String[] {"decide", runId, "--quiet"}, ApproveEnv.realtime().withObservation(observation),
                url -> orca.openTab(project, url).ok());
        try (var dashboard = new Dashboard(project, port, opener)) {
            List<String> actions = new ArrayList<>(List.of(
                    "open_decision_page", "manage_connections", "manage_models", "check_models"));
            if (!hasFlag(args, "--no-launcher")) {
                dashboard.enableLauncher(new RunLauncher(UserConfig.defaultHome(), project));
                actions.add("launch_runs");
            }
            dashboard.start();
            CliOutput.json(Map.of("ok", true, "url", dashboard.url(), "project", project.toString(),
                    "read_only", false, "actions", actions));
            warnUnresolvedCredentials();
            if (hasFlag(args, "--open-orca")) {
                var opened = orca.openTab(project, dashboard.url());
                if (!opened.ok()) System.err.println("Dashboard is available at " + dashboard.url()
                        + "; Orca did not open the tab: " + opened.stderr());
            }
            Thread shutdown = new Thread(dashboard::close, "oseer-dashboard-shutdown");
            Runtime runtime = Runtime.getRuntime();
            runtime.addShutdownHook(shutdown);
            try {
                new CountDownLatch(1).await();
            } finally {
                runtime.removeShutdownHook(shutdown);
            }
        }
        return 0;
    }

    private static void warnUnresolvedCredentials() {
        try {
            var unresolved = CredentialAudit.unresolved(UserConfig.load(), new CredentialResolver());
            if (unresolved.isEmpty()) return;
            System.err.println("warning: this dashboard cannot resolve "
                    + unresolved.stream().map(row -> String.valueOf(row.get("credential_ref"))).toList()
                    + "; runs it starts stop at the model check. Start it from the shell or launcher"
                    + " that exports them (see `oseer doctor`).");
        } catch (IOException | RuntimeException unreadable) {
            // The page shows configuration problems itself.
        }
    }

    /** Reuses the live page lease or starts a supervisor for a decision already on disk. */
    public static String openDecision(DecisionPageOpener pages, Path root, String runId, String[] args,
                                      ApproveEnv env, DecisionSession.PageOpener tabs) throws Exception {
        var live = DecisionPage.liveLease(root, runId);
        if (live != null) return live.url();
        return pages.open(root, runId,
                publish -> superviseDashboardDecision(root, runId, args, env, publish), tabs::open);
    }

    private static void superviseDashboardDecision(Path root, String runId, String[] args, ApproveEnv env,
                                                   DecisionPageOpener.Publisher publish) throws Exception {
        var live = DecisionPage.liveLease(root, runId);
        if (live != null) {
            publish.open(live.url());
            return;
        }
        HumanDecision pending = new ApprovalStore(root).read(runId);
        if (pending.state() != HumanDecision.State.PENDING) {
            throw new ApprovalException("duplicate_decision", "run " + runId + " is already "
                    + pending.state().jsonValue());
        }
        Map<String, Object> summary = Json.parseObject(Files.readString(
                root.resolve(".oseer/runs").resolve(runId).resolve("task-run.json")));
        Map<String, Object> supervised = superviseGates(root, runId, summary, args, env,
                ContinuationCommand::startAdvance,
                (path, id, ignored) -> awaitDecision(path, id, args, PAGE_WAIT_MINUTES, env, publish::open));
        if (!runId.equals(supervised.get("supervised_elsewhere"))) return;
        var leased = DecisionPage.liveLease(root, runId);
        if (leased == null) {
            throw new ApprovalException("decision_supervised_elsewhere",
                    "another process supervises run " + runId + " and has no page up yet "
                            + "(it may be amending the contract); try again in a moment");
        }
        publish.open(leased.url());
    }
}
