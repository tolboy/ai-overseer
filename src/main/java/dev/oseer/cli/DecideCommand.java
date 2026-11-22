package dev.oseer.cli;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.approval.StatusCommand;
import dev.oseer.config.ConfigLoader;
import dev.oseer.dashboard.DecisionPage;
import dev.oseer.execution.orca.OrcaClient;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static dev.oseer.cli.CliOptions.DEFAULT_DECISION_WAIT_MINUTES;
import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.CliOptions.optionalMinutes;
import static dev.oseer.cli.DecisionSession.awaitDecision;
import static dev.oseer.cli.DecisionSupervisor.superviseGates;

final class DecideCommand {
    private DecideCommand() {}

    /**
     * The decision page for a run that is already waiting: the `do` that stopped at it has
     * exited, timed out, or ran outside the supervisor. Opens the Overseer decision page in this
     * worktree's Orca browser and waits for the answer exactly as `do` does, continuing the
     * loop on retry, advance, switch or apply.
     */
    static int decide(String[] args, RunObservation observation) throws Exception {
        if (args.length < 2 || args[1].startsWith("--")) {
            throw new IllegalArgumentException("decide requires a run id: oseer decide <run-id>");
        }
        String runId = args[1];
        Optional<Path> found = new ConfigLoader().locateProjectRoot(Path.of("."));
        if (found.isEmpty()) {
            System.out.println(Json.write(StatusCommand.notAOverseerProject(Path.of("."))));
            return 1;
        }
        Path root = found.get();
        Path summaryFile = root.resolve(".oseer/runs").resolve(runId).resolve("task-run.json");
        if (!Files.isRegularFile(summaryFile)) {
            System.out.println(Json.write(Map.of("ok", false, "code", "run_not_found",
                    "message", "no summary for " + runId + " under " + root.resolve(".oseer/runs"))));
            return 1;
        }
        Integer minutes = optionalMinutes(args, "--wait-minutes");
        boolean openTab = !hasFlag(args, "--no-open");
        OrcaClient orca = new OrcaClient(new ProcessRunner());
        // A page that is already up has a supervisor behind it. A second one would start the
        // continuation a second time after the answer, so this shows the one that is there.
        var live = DecisionPage.liveLease(root, runId);
        if (live != null) {
            boolean opened = openTab && orca.openTab(root, live.url()).ok();
            Map<String, Object> reused = new LinkedHashMap<>();
            reused.put("ok", true);
            reused.put("code", "decision_page_open");
            reused.put("run_id", runId);
            reused.put("url", live.url());
            reused.put("served_by_pid", live.pid());
            reused.put("orca_tab_opened", opened);
            System.out.println(Json.write(reused));
            return 0;
        }
        ApproveEnv env = ApproveEnv.realtime().withObservation(observation);
        int waitMinutes = minutes == null ? DEFAULT_DECISION_WAIT_MINUTES : minutes;
        Map<String, Object> summary = Json.parseObject(Files.readString(summaryFile));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", runId);
        report.putAll(superviseGates(root, runId, summary, args, env, ContinuationCommand::startAdvance,
                (path, id, shown) -> awaitDecision(path, id, args, waitMinutes, env,
                        url -> openTab && orca.openTab(path, url).ok())));
        if (runId.equals(report.get("supervised_elsewhere"))) {
            // Another process took the run between the lease check above and here, or holds it
            // while it amends the contract, before any page is up. Its page is the one to use.
            report.put("ok", true);
            report.put("code", "decision_supervised_elsewhere");
            report.put("message", "another process supervises " + runId + "; its page is listed by "
                    + "`oseer dashboard` once it is up, and `oseer approve` still answers it");
            System.out.println(Json.write(report));
            return 0;
        }
        HumanDecision after = new ApprovalStore(root).read(runId);
        report.put("ok", after.state() == HumanDecision.State.RESOLVED);
        report.put("decision", after.toMap());
        System.out.println(Json.write(report));
        return after.state() == HumanDecision.State.RESOLVED ? 0 : 1;
    }
}
