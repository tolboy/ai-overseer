package dev.oseer.cli;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.RunOverride;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.WorktreeLease;
import dev.oseer.json.Json;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.process.ProcessRunner;
import dev.oseer.run.DoCommand;
import dev.oseer.run.Preparation;
import dev.oseer.run.Progress;
import dev.oseer.run.RunReadiness;
import dev.oseer.run.TaskLoop;
import dev.oseer.run.TaskRouting;
import dev.oseer.run.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.oseer.cli.ApprovalCommand.waitForPublishedGate;
import static dev.oseer.cli.CliOptions.DEFAULT_DECISION_WAIT_MINUTES;
import static dev.oseer.cli.CliOptions.defaultRunId;
import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.CliOptions.option;
import static dev.oseer.cli.CliOptions.optionalMinutes;
import static dev.oseer.cli.Continuation.Carried;
import static dev.oseer.cli.Continuation.continuation;
import static dev.oseer.cli.ContinuationCommand.startAdvance;
import static dev.oseer.cli.DecisionSupervisor.decisionPage;
import static dev.oseer.cli.DecisionSupervisor.superviseGates;
import static dev.oseer.cli.GateWait.skippedGateWait;
import static dev.oseer.cli.RunPreflight.recordPreflightFailure;
import static dev.oseer.cli.RunPresentation.narration;
import static dev.oseer.cli.RunPresentation.narrationFile;
import static dev.oseer.cli.RunPresentation.surfaceChain;
import static dev.oseer.cli.RunPresentation.writeRunReport;

final class RunCommand {
    private final RunObservation observation;

    RunCommand(RunObservation observation) {
        this.observation = observation;
    }

    /**
     * The bounded loop. It stops at the human gate and never lands anything: the only two
     * outcomes are `human_gate` and `human_escalation`.
     */
    int runLoop(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("run requires a task id or YAML path");
        if ("--help".equals(args[1]) || "-h".equals(args[1])) {
            CliHelp.printRun();
            return 0;
        }
        String runId = option(args, "--run-id", defaultRunId());
        boolean dryRun = hasFlag(args, "--dry-run");
        String continueFrom = option(args, "--continue", null);
        String prepare = option(args, "--prepare", null);
        Integer waitForGate = optionalMinutes(args, "--wait-for-gate");
        TaskLoop.Outcome outcome;
        Path root = null;
        try {
            ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
            root = loaded.root();
            UserConfig user = UserConfig.load();
            Carried carried = continuation(loaded.root(), continueFrom);
            Workspace card = observation.forRun(args).at(loaded.root());
            Path narration = narrationFile(loaded.root(), runId);
            TaskLoop loop = new TaskLoop(new ProcessRunner())
                    .withTaskRouting(TaskRouting.live())
                    .withReadiness(RunReadiness.live(new ProcessRunner()))
                    .withProgress(Progress.tee(narration(args),
                            Progress.toFile(narration)))
                    .withWorkspace(card)
                    .withWatch(hasFlag(args, "--no-workspace-status") ? null : narration)
                    .withOrcaGate(!dryRun && !hasFlag(args, "--no-orca-gate"))
                    .withOverride(RunOverride.fromArgs(args));
            // Conductor's inner `oseer run` is a new process and cannot see DoCommand's
            // in-memory Preparation. The reservation carries the mode when a planner ran
            // (or when auto skipped and still recorded it); --prepare is the same fact
            // arriving on the command line so a skipped auto is not recorded as off.
            if (prepare != null) {
                loop = loop.withPreparation(new TaskLoop.Preparation(
                        Preparation.parseMode(prepare), false, 0, 0, 0, false));
            }
            outcome = loop.run(loaded, user, runId, dryRun, carried.failover(), carried.continuation());
        } catch (WorktreeLease.Refused refused) {
            Map<String, Object> result = new LinkedHashMap<>(refused.report());
            result.put("run_id", runId);
            CliOutput.json(result);
            return 1;
        } catch (EvidenceLedger.RunExistsException duplicate) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("reason", "run_id_exists");
            result.put("next_action", "choose_new_run_id");
            result.put("run_id", runId);
            result.put("message", duplicate.getMessage());
            CliOutput.json(result);
            return 1;
        } catch (Exception preflight) {
            return recordPreflightFailure(args[1], runId, continueFrom, preflight);
        }
        Map<String, Object> result = RunPresentation.runReport(outcome, runId,
                writeRunReport(Path.of("."), runId));
        if (!dryRun && root != null && !hasFlag(args, "--no-wait-for-gate")) {
            int minutes = waitForGate == null ? DEFAULT_DECISION_WAIT_MINUTES : waitForGate;
            ApproveEnv env = ApproveEnv.realtime().withObservation(observation);
            result.putAll(superviseGates(root, runId, outcome.summaryReport(), args, env,
                    ContinuationCommand::startAdvance, decisionPage(args, minutes, env)));
        } else if (waitForGate != null) {
            // Import only. The wait never starts, retries or continues a run; a missing
            // gate is said, not treated as an answer.
            if (dryRun) {
                result.put("gate_wait", skippedGateWait("dry_run"));
            } else if (root != null) {
                result.putAll(waitForPublishedGate(root, runId, outcome.summaryReport(),
                        waitForGate, ApproveEnv.realtime().withObservation(observation)));
            }
        }
        surfaceChain(result);
        CliOutput.json(result);
        int exit = Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
        if (result.get("gate_import") instanceof Map<?, ?> imported
                && Boolean.FALSE.equals(imported.get("ok"))) {
            exit = 1;
        }
        return exit;
    }

    /**
     * Goal plus project. Isolates via Orca, writes a task, runs the loop, stops at the human
     * gate. Never lands.
     */
    int doIntent(String[] args) throws Exception {
        DoCommand.Options options = DoCommand.parse(args);
        UserConfig user = UserConfig.load();
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner(), narration(args))
                .withTaskRouting(TaskRouting.live())
                .withReadiness(RunReadiness.live(new ProcessRunner()))
                .withWorkspace(observation.forRun(args), !hasFlag(args, "--no-workspace-status"))
                .withOrcaGate(!hasFlag(args, "--no-orca-gate"))
                .withOverride(RunOverride.fromArgs(args))
                .run(options, user);
        Map<String, Object> report = new LinkedHashMap<>(outcome.report());
        Object runId = report.get("run_id");
        if (runId instanceof String id && outcome.worktree() != null) {
            report.put("report_path", writeRunReport(outcome.worktree(), id));
            Path summary = outcome.worktree().resolve(".oseer/runs").resolve(id).resolve("task-run.json");
            if (!options.dryRun() && !options.conductor() && !hasFlag(args, "--no-wait-for-gate")
                    && Files.isRegularFile(summary)) {
                Integer requestedWait = optionalMinutes(args, "--wait-for-gate");
                int minutes = requestedWait == null ? DEFAULT_DECISION_WAIT_MINUTES : requestedWait;
                ApproveEnv env = ApproveEnv.realtime().withObservation(observation);
                report.putAll(superviseGates(outcome.worktree(), id,
                        Json.parseObject(Files.readString(summary)), args, env,
                        ContinuationCommand::startAdvance, decisionPage(args, minutes, env)));
            }
        }
        surfaceChain(report);
        return CliOutput.report(report);
    }
}
