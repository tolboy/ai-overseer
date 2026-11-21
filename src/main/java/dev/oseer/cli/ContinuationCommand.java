package dev.oseer.cli;

import dev.oseer.approval.HumanDecision;
import dev.oseer.config.ConfigLoader;
import dev.oseer.config.PlannerDraft;
import dev.oseer.config.Profile;
import dev.oseer.config.RunOverride;
import dev.oseer.config.UserConfig;
import dev.oseer.dashboard.DecisionPage;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.process.ProcessRunner;
import dev.oseer.run.DoCommand;
import dev.oseer.run.Preparation;
import dev.oseer.run.Progress;
import dev.oseer.run.RunReadiness;
import dev.oseer.run.TaskLoop;
import dev.oseer.run.Workspace;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.Continuation.Carried;
import static dev.oseer.cli.Continuation.continuation;
import static dev.oseer.cli.ContinuationClaim.claimContinuation;
import static dev.oseer.cli.ContinuationClaim.continuationRunCreated;
import static dev.oseer.cli.ContinuationClaim.nextRunId;
import static dev.oseer.cli.ContinuationClaim.releaseContinuation;
import static dev.oseer.cli.ContinuationPolicy.advanceWouldRepeat;
import static dev.oseer.cli.RunPresentation.narration;
import static dev.oseer.cli.RunPresentation.narrationFile;
import static dev.oseer.cli.RunPresentation.writeRunReport;

public final class ContinuationCommand {
    private ContinuationCommand() {}

    /** A held supervisor lease means that process will attempt the continuation. */
    static boolean supervisorAlive(Path root, String runId) {
        try (var lock = DecisionPage.supervise(root, runId)) {
            return lock == null;
        } catch (Exception unavailable) {
            return false;
        }
    }

    /**
     * {@code advance}: close this run and start the next id of the same task, carrying
     * writers. Verdicts are not reused — the point of advance is that the operator changed
     * the contract, the scope, or the tree. {@code --no-start} records the decision and
     * prints the command, for a script that wants to launch the run itself.
     */
    public static Map<String, Object> startAdvance(Path root, HumanDecision resolved, String[] args,
                                                   ApproveEnv env) {
        return startAdvance(root, resolved, args, env, false);
    }

    static Map<String, Object> startAdvance(Path root, HumanDecision resolved, String[] args,
                                            ApproveEnv env, boolean waiterObserved) {
        String nextId = nextRunId(root, resolved.runId());
        boolean replan = resolved.kind() == HumanDecision.Kind.PREPARATION
                && "replan".equals(resolved.decision());
        // A preparation stop's `proceed` is the explicit "run writers on this contract".
        // `replan` re-runs preparation; plain failure continuation must not be used for it.
        String prepareMode = replan ? "always" : "off";
        String command = "oseer run " + resolved.taskId() + " --run-id " + nextId
                + " --continue " + resolved.runId() + " --prepare " + prepareMode;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_id", nextId);
        report.put("continued_from", resolved.runId());
        report.put("prepare", prepareMode);
        boolean noStart = hasFlag(args, "--no-start");
        if (waiterObserved || ((noStart || hasFlag(args, "--start-unless-waiting"))
                && supervisorAlive(root, resolved.runId()))) {
            report.put("started", false);
            report.put("waiter", true);
            report.put("message", "a process is already waiting on this decision and will "
                    + "start the next run; do not start another");
            return report;
        }
        report.put("command", command);
        if (noStart) {
            report.put("started", false);
            return report;
        }
        String unchanged = "advance".equals(resolved.decision()) ? advanceWouldRepeat(root, resolved) : null;
        if (unchanged != null) {
            report.put("started", false);
            report.put("ok", false);
            report.put("code", "advance_would_repeat");
            report.put("message", unchanged);
            return report;
        }
        // Everything that can refuse without dispatching is asked before the answer is
        // consumed: a contract that does not load must not use up the one continuation.
        ConfigLoader.Loaded loaded;
        UserConfig user;
        Carried carried;
        try {
            loaded = new ConfigLoader().load(root, resolved.taskId());
            user = env.user();
            carried = continuation(root, resolved.runId());
        } catch (Exception invalid) {
            report.put("started", false);
            report.put("ok", false);
            report.put("code", "advance_start_failed");
            report.put("message", String.valueOf(invalid.getMessage()));
            return report;
        }
        if (replan && env.readiness != null) {
            Set<String> names = new LinkedHashSet<>();
            if (user.policy() != null) for (var spec : user.policy().roles().values()) names.addAll(spec.profiles());
            List<Profile> models = names.stream().map(user.profiles()::get).filter(Objects::nonNull)
                    .filter(Profile::verified).toList();
            try {
                Progress progress = narration(args);
                Map<String, Object> checked = env.readiness.check(
                        user, root, models, loaded.resolved().budget().strictCostCap(), progress::line);
                if (!RunReadiness.permitsLaunch(checked, user, new ProcessRunner(), RunOverride.fromArgs(args),
                        Set.copyOf(Preparation.payingRoles(user)), progress::line)) {
                    report.put("started", false); report.put("ok", false);
                    report.put("code", "model_check_failed"); report.put("model_check", checked);
                    return report;
                }
            } catch (Exception failed) {
                report.put("started", false); report.put("ok", false); report.put("code", "model_check_failed");
                return report;
            }
        }
        Map<String, Object> claimed;
        try {
            claimed = claimContinuation(root, resolved.runId(), nextId);
        } catch (Exception unclaimable) {
            report.put("started", false);
            report.put("ok", false);
            report.put("code", "advance_start_failed");
            report.put("message", "could not record which run continues " + resolved.runId() + ": "
                    + unclaimable.getMessage());
            return report;
        }
        if (claimed != null) {
            report.put("started", false);
            report.put("ok", false);
            report.put("code", "continuation_already_started");
            report.put("run_id", claimed.get("run_id"));
            report.put("message", "run " + claimed.get("run_id") + " already continues " + resolved.runId()
                    + (continuationRunCreated(root, String.valueOf(claimed.get("run_id"))) ? ""
                        : ", and the process starting it (pid " + claimed.get("pid") + ") is still alive")
                    + "; one answer starts one run");
            return report;
        }
        try {
            Workspace card = Workspace.guarded(env.observation.forRun(args).at(root));
            Path narration = narrationFile(root, nextId);
            TaskLoop.Preparation preparation = TaskLoop.Preparation.NONE;
            if (replan) {
                // Re-run preparation so writers never receive the objected contract by default.
                String scope = loaded.task().scope().entries().isEmpty()
                        ? null : loaded.task().scope().entries().get(0);
                Map<String, Object> reservation = new LinkedHashMap<>();
                reservation.put("prepare", "always");
                var chainGate = TaskLoop.amendmentGate(root, resolved.runId(), loaded.resolved().budget(),
                        () -> env.clock.nowMillis() * 1_000_000L);
                new EvidenceLedger(root, nextId, user.home())
                        .reserveWorkflowRun(resolved.taskId(), reservation);
                if (!hasFlag(args, "--no-workspace-status")) card.watch(narration, nextId);
                Preparation.Outcome prepared = new Preparation(new ProcessRunner(),
                        narration(args)).withWorkspace(card).within(chainGate).run(root, loaded.project(), user, resolved.taskId(),
                        nextId, loaded.task().goal(), scope, loaded.task().risk(),
                        PlannerDraft.Access.DO_DEFAULT, false);
                if (!prepared.ok()) {
                    Map<String, Object> prepReport = new LinkedHashMap<>();
                    prepReport.put("prepare", "always");
                    prepReport.put("continued_from", resolved.runId());
                    prepReport.put("goal", loaded.task().goal());
                    prepReport.put("preparation_role_runs", (long) prepared.roleRuns());
                    prepReport.put("preparation_cost_usd", prepared.costUsd());
                    prepReport.put("preparation_unpriced", (long) prepared.unpriced());
                    if (prepared.planReview() != null) {
                        prepReport.put("plan_review", prepared.planReview());
                    }
                    if (prepared.written() != null) {
                        prepReport.put("task_file", prepared.written().file().toString());
                    }
                    new DoCommand(new ProcessRunner(), narration(args))
                            .withOrcaGate(!hasFlag(args, "--no-orca-gate"))
                            .recordPreparationStop(root, user, resolved.taskId(), nextId,
                                    prepared, prepReport);
                    report.put("started", true);
                    report.put("ok", false);
                    report.put("reason", prepared.code());
                    report.put("next_action", "human_escalation");
                    report.put("summary_report", prepReport);
                    report.put("report_path", writeRunReport(root, nextId));
                    report.put("preparation_replanned", true);
                    return report;
                }
                new EvidenceLedger(root, nextId, user.home())
                        .markPrepared(prepared.roleRuns(), prepared.costUsd(), prepared.unpriced(),
                                null);
                preparation = new TaskLoop.Preparation("always", true, prepared.roleRuns(),
                        prepared.costUsd(), prepared.unpriced(), true);
                // Reload: the redraft may have rewritten the contract.
                loaded = new ConfigLoader().load(root, resolved.taskId());
            }
            TaskLoop.Outcome outcome = new TaskLoop(new ProcessRunner())
                    .withTaskRouting(env.taskRouting)
                    .withReadiness(env.readiness)
                    .withProgress(Progress.tee(narration(args),
                            Progress.toFile(narration)))
                    .withWorkspace(card)
                    .withWatch(hasFlag(args, "--no-workspace-status") ? null : narration)
                    .withOrcaGate(!hasFlag(args, "--no-orca-gate"))
                    .withOverride(RunOverride.fromArgs(args))
                    .withPreparation(preparation)
                    .run(loaded, user, nextId, false, carried.failover(), carried.continuation());
            report.put("started", true);
            report.put("ok", outcome.ok());
            report.put("reason", outcome.reason());
            report.put("next_action", outcome.nextAction());
            report.put("summary", outcome.summary() == null ? null : outcome.summary().toString());
            report.put("summary_report", outcome.summaryReport());
            report.put("report_path", writeRunReport(root, nextId));
            if (replan) report.put("preparation_replanned", true);
        } catch (Exception failed) {
            report.put("started", false);
            report.put("ok", false);
            report.put("code", "advance_start_failed");
            // A run that reserved its evidence may have paid for something: its claim stands,
            // and it is continued by the printed command. One that did not never dispatched,
            // and the answer is free to be acted on again once the cause is fixed.
            boolean created = continuationRunCreated(root, nextId);
            if (!created) releaseContinuation(root, resolved.runId(), nextId);
            report.put("continuation_released", !created);
            report.put("message", String.valueOf(failed.getMessage())
                    + (created ? "; run " + nextId + " was created, so it keeps the claim: continue it with "
                            + command : "; no run was created, so the answer can be acted on again"));
        }
        return report;
    }
}
