package dev.oseer.run;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.config.ConfigLoader;
import dev.oseer.config.Policy;
import dev.oseer.config.RunOverride;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.config.OverseerTree;
import dev.oseer.config.Workflow;
import dev.oseer.execution.Executors;
import dev.oseer.execution.orca.OrcaDecisionGate;
import dev.oseer.execution.orca.OrcaLifecycle;
import dev.oseer.gate.GateRunner;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.ledger.HomeCorpus;
import dev.oseer.ledger.Findings;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static dev.oseer.run.WorkflowAdmission.initialPreviewWriters;
import static dev.oseer.run.WorkflowAdmission.judgeGap;
import static dev.oseer.run.WorkflowAdmission.judgesCandidate;
import static dev.oseer.run.WorkflowAdmission.overlayGap;
import static dev.oseer.run.WorkflowAdmission.readerGap;
import static dev.oseer.run.WorkflowAdmission.selectableProfiles;
import static dev.oseer.run.WorkflowAdmission.skipReason;
import static dev.oseer.run.WorkflowAdmission.strictBounds;
import static dev.oseer.run.WorkflowAdmission.strictCapGap;
import static dev.oseer.run.WorkflowAdmission.toolingGap;
import static dev.oseer.run.WorkflowAdmission.withPreviewWriter;
import static dev.oseer.run.WorkflowPreview.budgetPreview;
import static dev.oseer.run.WorkflowPreview.cast;
import static dev.oseer.run.WorkflowPreview.modelSelectionEnabled;

/**
 * The bounded loop. By default:
 *
 *   implement → machine gates → [fix ≤ N] → independent review → [fix ≤ N]
 *             → browser harness → [fix ≤ N] → visual QA role → [fix ≤ N] → stop for a human
 *
 * That chain is the built-in {@link Workflow}, not a hard-coded one: the order of stages and
 * the conditions under which each runs are declared beside the roles they dispatch, in
 * `~/.oseer/policy.yaml`. This class executes whatever chain it is handed and owns the parts
 * that must not be configurable — the fix bound, the budget, and what a failure is called.
 *
 * Three properties are deliberate.
 *
 * 1. It never lands anything. The final act is a summary whose `next_action` can only be
 *    `human_gate` or `human_escalation`. Approval is the one step that treats agent output as
 *    trusted, and it stays a human act.
 * 2. Routing is code. Whether to fix again, whether to review at all, and which vendor fills a
 *    role are decided here by counters, risk and exit codes. No model is consulted.
 * 3. Every attempt leaves the exact failure text that was handed back, so a loop that went
 *    wrong is diagnosable afterwards instead of being re-run to be understood.
 *
 * Budgets are enforced before a vendor is dispatched, never after: an overrun should not be
 * discovered by paying for it. That includes the dispatches a failover adds, which is why the
 * loop hands its budget to the role runner as a gate instead of counting role invocations.
 *
 * A spent subscription is reported as its own stop reason. `implementer_failed` and
 * `quota_exhausted` demand different things from whoever reads the summary — one is a defect
 * to look at, the other resolves itself when the quota window rolls over — and collapsing them
 * into one reason sends an operator to read a transcript that has nothing wrong in it.
 *
 * The stage walk is {@link WorkflowEngine}. The sentence a stop offers is {@link SafeNextStep}.
 * A repair is handed {@link RepairBrief}. The terminal and the board are {@link RunNarration}.
 */
public final class TaskLoop {

    public record Outcome(boolean ok, String reason, String nextAction, Path summary,
                          Map<String, Object> summaryReport) {}

    /**
     * The browser step, as a seam. It exists so the loop's routing — when a failing
     * screenshot goes back to the implementer, when the visual role is paid to look — can be
     * tested without a browser. The routing is the part that breaks; the CDP driver has its
     * own live proof in docs/SMOKE.md.
     */
    @FunctionalInterface
    public interface VisualCheck {
        VisualQaRunner.Outcome run(ConfigLoader.Loaded loaded, String runId) throws Exception;
    }

    /**
     * What {@code oseer do --prepare} already did before this loop started.
     *
     * The planner is not a workflow stage: the shipped chain is unchanged. Preparation
     * still costs a vendor call, still writes a {@code role_run}, and still has to be in
     * the budget arithmetic, or a report would show it as free.
     *
     * @param mode            {@code off}, {@code auto} or {@code always}, as the invocation asked
     * @param runReserved     the caller already fenced {@code run.json} (the planner wrote
     *                        evidence into it)
     * @param roleRuns        vendor calls already spent, counted against {@code max_role_runs}
     * @param costUsd         cost already spent, counted against {@code max_cost_usd}
     * @param unpriced        of those calls, how many reported no price
     * @param includedInPlan  whether {@link CallPlan} should reserve a planner call
     */
    public record Preparation(String mode, boolean runReserved, int roleRuns, double costUsd,
                              int unpriced, boolean includedInPlan,
                              Map<String, Object> estimateAtReservation,
                              Boolean reservationMatchedContract) {
        public static final Preparation NONE = new Preparation("off", false, 0, 0, 0, false);

        public Preparation(String mode, boolean runReserved, int roleRuns, double costUsd,
                           int unpriced, boolean includedInPlan) {
            this(mode, runReserved, roleRuns, costUsd, unpriced, includedInPlan, null, null);
        }

        /**
         * Rebuild what {@code oseer do --prepare} recorded on the reservation, so a second
         * process (Conductor's {@code oseer run}, or an operator following --draft-only)
         * counts the planner the same way the in-process loop does.
         */
        public static Preparation fromReservation(Map<String, Object> reservation) {
            String mode = reservation.get("prepare") instanceof String value ? value : "off";
            int runs = reservation.get("preparation_role_runs") instanceof Number number
                    ? number.intValue() : 0;
            double cost = reservation.get("preparation_cost_usd") instanceof Number number
                    ? number.doubleValue() : 0;
            int unpriced = reservation.get("preparation_unpriced") instanceof Number number
                    ? number.intValue() : 0;
            boolean included = false;
            if (reservation.get("budget_plan") instanceof Map<?, ?> plan) {
                included = String.valueOf(plan.get("paying_stages")).contains("planner");
            }
            Map<String, Object> estimate = null;
            if (reservation.get("budget_plan_at_reservation") instanceof Map<?, ?> earlier) {
                Map<String, Object> copied = new LinkedHashMap<>();
                earlier.forEach((key, value) -> copied.put(String.valueOf(key), value));
                estimate = copied;
            }
            Boolean matched = reservation.get("reservation_matched_contract") instanceof Boolean flag
                    ? flag : null;
            return new Preparation(mode, true, runs, cost, unpriced, included, estimate, matched);
        }
    }

    private final ProcessRunner processes;
    private final VisualCheck visualCheck;
    private final Progress progress;
    private final Workspace workspace;
    private final boolean orcaGate;
    private final Preparation preparation;
    private final RunOverride override;
    /** Nanoseconds, as {@link System#nanoTime}; replaced only by the suite. */
    private java.util.function.LongSupplier clock = System::nanoTime;
    /** How the loop waits between rate-limit retries; the suite replaces it. */
    private java.util.function.LongConsumer sleeper;
    /** How a profile becomes an executor; the suite stands in for a vendor it cannot wait out. */
    private Executors.Factory executors;
    private RoleResolver.Availability availability;
    private RunReadiness readiness;
    private TaskRouting taskRouting;

    public TaskLoop withTaskRouting(TaskRouting next){
        TaskLoop copy=keepClock(new TaskLoop(processes,visualCheck,progress,workspace,orcaGate,preparation,override));
        copy.taskRouting=next;return copy;
    }
    /** The narration `--watch` asked the board to follow, or null for no live view. */
    private Path watchNarration;

    public TaskLoop(ProcessRunner processes) {
        this(processes, (VisualCheck) null);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck) {
        this(processes, visualCheck, Progress.SILENT);
    }

    /**
     * The same loop, narrating itself. Not an overloaded constructor on purpose: `Progress`
     * and `VisualCheck` are both single-method interfaces, so `new TaskLoop(runner, x::y)`
     * would be ambiguous at every call site and a lambda could silently pick the wrong one.
     */
    public TaskLoop withProgress(Progress narration) {
        return keepClock(new TaskLoop(processes, visualCheck, narration, workspace, orcaGate,
                preparation, override));
    }

    /**
     * The same loop, timed by another clock. A chain deadline is measured in minutes, and the
     * suite cannot wait that long for one to pass.
     */
    public TaskLoop withClock(java.util.function.LongSupplier nanos) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.clock = nanos;
        return copy;
    }

    /**
     * The same loop, pausing between rate-limit retries through {@code next} instead of the
     * clock. The suite cannot wait out a vendor's backoff.
     */
    public TaskLoop withSleeper(java.util.function.LongConsumer next) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.sleeper = next;
        return copy;
    }

    /** Program readiness supplied by an embedded adapter or an offline executor fixture. */
    public TaskLoop withAvailability(RoleResolver.Availability next) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.availability = next;
        return copy;
    }

    /** Live inference is wired by launchers; embedded/offline loops explicitly own this seam. */
    public TaskLoop withReadiness(RunReadiness next) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.readiness = next;
        return copy;
    }

    /**
     * The same loop, dispatching through {@code next} instead of the profile's own runner.
     *
     * A wall-clock timeout is at least a minute, because that is the smallest bound a
     * profile may declare. The suite uses this to return the code a killed process produces
     * without waiting the minute out. The same reason {@link #withClock} exists.
     */
    public TaskLoop withExecutors(Executors.Factory next) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.executors = next;
        return copy;
    }

    /**
     * The same loop, opening a live view of {@code narration} on its board once the run is
     * reserved. The loop opens it rather than its caller because the board records the tab
     * in the run's own directory, and a reservation refuses a directory it finds that in.
     */
    public TaskLoop withWatch(Path narration) {
        TaskLoop copy = keepClock(new TaskLoop(processes, visualCheck, progress, workspace,
                orcaGate, preparation, override));
        copy.watchNarration = narration;
        return copy;
    }

    private TaskLoop keepClock(TaskLoop copy) {
        copy.clock = clock;
        copy.sleeper = sleeper;
        copy.watchNarration = watchNarration;
        copy.executors = executors;
        copy.availability = availability;
        copy.readiness = readiness;
        copy.taskRouting = taskRouting;
        return copy;
    }

    /**
     * The same loop, reporting to the board it is running on. Separate from the terminal
     * narration because the two carry different things: every line goes to the terminal, and
     * only what a person would act on goes to the card.
     */
    public TaskLoop withWorkspace(Workspace board) {
        return keepClock(new TaskLoop(processes, visualCheck, progress, Workspace.guarded(board),
                orcaGate, preparation, override));
    }

    /**
     * Whether a pending decision is also published to Orca as a decision gate.
     *
     * Off unless a caller asks, and `oseer run` and `oseer do` ask, for the same reason they
     * write the card: a run that stops for a person stops wherever that person is not, and
     * inside an Orca worktree there is already a surface that reaches them. Outside one,
     * publishing finds nothing and records that it did not. The gate never decides anything —
     * see {@link dev.oseer.execution.orca.OrcaDecisionGate}.
     */
    public TaskLoop withOrcaGate(boolean publish) {
        return keepClock(new TaskLoop(processes, visualCheck, progress, workspace, publish,
                preparation, override));
    }

    public TaskLoop withPreparation(Preparation preparation) {
        return keepClock(new TaskLoop(processes, visualCheck, progress, workspace, orcaGate,
                preparation == null ? Preparation.NONE : preparation, override));
    }

    public TaskLoop withOverride(RunOverride next) {
        return keepClock(new TaskLoop(processes, visualCheck, progress, workspace, orcaGate,
                preparation, next == null ? RunOverride.NONE : next));
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress) {
        this(processes, visualCheck, progress, Workspace.NONE, false);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace) {
        this(processes, visualCheck, progress, workspace, false);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace, boolean orcaGate) {
        this(processes, visualCheck, progress, workspace, orcaGate, Preparation.NONE);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace, boolean orcaGate, Preparation preparation) {
        this(processes, visualCheck, progress, workspace, orcaGate, preparation, RunOverride.NONE);
    }

    public TaskLoop(ProcessRunner processes, VisualCheck visualCheck, Progress progress,
                    Workspace workspace, boolean orcaGate, Preparation preparation,
                    RunOverride override) {
        this.processes = processes;
        this.visualCheck = visualCheck;
        this.progress = progress;
        this.workspace = workspace;
        this.orcaGate = orcaGate;
        this.preparation = preparation == null ? Preparation.NONE : preparation;
        this.override = override == null ? RunOverride.NONE : override;
    }

    /**
     * What a previous run's recorded decision carries into this one.
     *
     * A rejection used to be a full stop. The reason a person typed went into `decision.json`
     * and was read by nobody: the next run started from an empty context and the implementer
     * was left to rediscover, or not, what a human had already said was wrong with its work.
     * That is the one piece of feedback in the whole loop that cost a person's attention
     * rather than a vendor call, and it was the only one thrown away.
     *
     * @param fromRunId    the run whose decision this is, named in the evidence
     * @param rejectionNote what the person said when they rejected that candidate, or null
     */
    public record Continuation(String fromRunId, String rejectionNote, boolean reuseJudgements,
                               boolean rejection) {
        public static final Continuation NONE = new Continuation(null, null, false, false);

        /** A continuation that is not a rejection. Advance carries a note this way. */
        public Continuation(String fromRunId, String rejectionNote, boolean reuseJudgements) {
            this(fromRunId, rejectionNote, reuseJudgements, false);
        }

        /** A rejection: the note is what the person refused, and the next run is told so. */
        public Continuation(String fromRunId, String rejectionNote) {
            this(fromRunId, rejectionNote, false, true);
        }

        public boolean carriesRejection() {
            return rejection && rejectionNote != null && !rejectionNote.isBlank();
        }

        /** A note the next implementer should read, whether it came from a rejection or an advance. */
        public boolean carriesNote() {
            return rejectionNote != null && !rejectionNote.isBlank();
        }

        public boolean continuesRun() {
            return fromRunId != null && !fromRunId.isBlank();
        }
    }

    public static final String CONTRACT_AMENDMENT = RunFailurePolicy.CONTRACT_AMENDMENT;
    public static final String CONTROLLER_INTERRUPTED = RunFailurePolicy.CONTROLLER_INTERRUPTED;

    public static String infrastructureReason(String code) {
        return RunFailurePolicy.infrastructureReason(code);
    }

    public static boolean timedOutUnderDeadline(String roleCode, Map<String, Object> details) {
        return RunFailurePolicy.timedOutUnderDeadline(roleCode, details);
    }

    public static Map<String, Object> chainForPreflightFailure(Path root, String priorRunId,
                                                               boolean inherits, String runId) {
        return RunChain.forPreflightFailure(root, priorRunId, inherits, runId);
    }

    public static boolean isInfrastructureFailure(String roleCode) {
        return RunFailurePolicy.isInfrastructureFailure(roleCode);
    }

    public static final String AMENDMENT_RECEIPT = RunChain.AMENDMENT_RECEIPT;

    /**
     * The run whose contract gaps were already offered to the planner in the chain that ends
     * at {@code priorRunId}, or null when none were. Read from the prior summary's own
     * marker, from its stop reason, or from an amendment receipt in its directory.
     */
    public static String amendmentOffered(Path root, String priorRunId) {
        Path dir = root.resolve(".oseer/runs").resolve(priorRunId);
        try {
            Path file = dir.resolve("task-run.json");
            if (Files.isRegularFile(file)) {
                Map<String, Object> prior = Json.parseObject(Files.readString(file));
                if (prior.get("contract_amended_from") instanceof String earlier && !earlier.isBlank()) {
                    return earlier;
                }
                if (CONTRACT_AMENDMENT.equals(prior.get("reason"))) return priorRunId;
            }
        } catch (Exception unreadable) {
            // The receipt below still answers whether the planner was paid.
        }
        return Files.isRegularFile(dir.resolve(AMENDMENT_RECEIPT)) ? priorRunId : null;
    }

    /**
     * The open `contract_gap` findings the policy sends to the planner before the gate, or an
     * empty list. Empty when the route is `gate`, when no planner is on the roster, or when
     * this run is itself the amended one: the amendment is offered once per chain.
     */
    static List<Map<String, Object>> contractGapsToPlan(UserConfig user, Map<String, Object> summary) {
        if (user == null || user.policy() == null || !"plan".equals(user.policy().contractGaps())) {
            return List.of();
        }
        if (!user.policy().roles().containsKey("planner")) return List.of();
        if (summary.get("contract_amended_from") != null) return List.of();
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Map<String, Object> finding : Findings.openByStage(summary)) {
            if ("contract_gap".equals(finding.get("category"))) gaps.add(finding);
        }
        return gaps;
    }

    public static boolean isAboutTheWork(String reason) {
        return RunFailurePolicy.isAboutTheWork(reason);
    }

    public static String reasonForContinuation(Map<String, Object> prior) {
        return RunFailurePolicy.reasonForContinuation(prior);
    }

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun)
            throws Exception {
        return run(loaded, user, runId, dryRun, Map.of());
    }

    /**
     * @param authorizedFailover role to profile substitutions a human already agreed to on an
     *                           earlier run. Empty for an ordinary run, which is why the
     *                           default policy stops and asks instead of switching.
     */
    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun,
                       Map<String, String> authorizedFailover) throws Exception {
        return run(loaded, user, runId, dryRun, authorizedFailover, Continuation.NONE);
    }

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun,
                       Map<String, String> authorizedFailover, Continuation carried) throws Exception {
        try (var interruption = new ApiInterruption();
             var execution = dryRun ? dev.oseer.execution.WorktreeLease.preview()
                : dev.oseer.execution.WorktreeLease.acquire(loaded.root(), runId)) {
            // Orca's adapter owns exact attach/reconciliation. With an Orca route available,
            // defer worker admission to that adapter (local roles and machine gates still
            // check immediately before execution). A blanket refusal blocks valid resumes.
            if (!dryRun && user.profiles().values().stream().noneMatch(p -> "orca".equals(p.runner()))) {
                dev.oseer.execution.WorktreeLease.requireLocalDispatch(loaded.root());
            }
            return runOwned(loaded, user, runId, dryRun, authorizedFailover, carried, interruption);
        }
    }

    /** Defer an API interrupt until receipts and the worktree lease have been persisted. */
    private static final class ApiInterruption implements AutoCloseable {
        private boolean interrupted;

        void defer() {
            if (Thread.currentThread().isInterrupted()) {
                interrupted = true;
                Thread.interrupted();
            }
        }

        @Override public void close() {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    // The executor has already cancelled its pending call and excluded retry. Clear the flag
    // before RoleRunner writes receipts or fingerprints the tree.
    private static dev.oseer.execution.RoleExecutor.Result deferApiInterruption(
            dev.oseer.execution.RoleExecutor.Result result, ApiInterruption interruption) {
        if ("role_api_interrupted".equals(result.code())) interruption.defer();
        return result;
    }

    private Outcome runOwned(ConfigLoader.Loaded loaded, UserConfig user, String runId, boolean dryRun,
                             Map<String, String> authorizedFailover, Continuation carried,
                             ApiInterruption interruption) throws Exception {
        Path root = loaded.root();
        TaskSpec.ResolvedTask task = loaded.resolved();
        EvidenceLedger ledger = new EvidenceLedger(root, runId, user.home());
        if (carried.continuesRun()) {
            ledger.bindParent(EvidenceLedger.runInstanceIdOf(root, carried.fromRunId()));
        }
        // Reservation is the first mutation. A duplicate controller is refused before it can
        // overwrite evidence, spend a token, or start a second Orca worker in the same tree.
        // Preparation already reserved when the planner ran: that call wrote evidence into
        // this directory, and reserving again would refuse the rest of the run as a duplicate.
        // A second process — Conductor's inner `oseer run`, or `oseer run` after
        // `--draft-only` — did not make that reservation itself, so it joins it.
        Preparation prior = preparation;
        Path reservation = ledger.runDirectory().resolve("run.json");
        if (prior.runReserved()) {
            // The in-process caller knows the spend; the reservation on disk knows what the
            // plan was measured against before the contract existed. Both are kept.
            Preparation onDisk = Preparation.fromReservation(ledger.claimPreparedLoop(task.id()));
            prior = new Preparation(prior.mode(), true, prior.roleRuns(), prior.costUsd(),
                    prior.unpriced(), prior.includedInPlan(), onDisk.estimateAtReservation(),
                    onDisk.reservationMatchedContract());
        } else if (Files.isRegularFile(reservation)) {
            prior = Preparation.fromReservation(ledger.claimPreparedLoop(task.id()));
        } else {
            ledger.reserveWorkflowRun(task.id());
        }
        // Only now does this run write into `orca.json`. The chain's Run and the watch tab's
        // handle both live there, and the reservation refuses a directory that already holds
        // it. When a chain was given one Orca Run, both were written before the reservation,
        // and from then on every continuation, every fresh `oseer run --watch` and every
        // `oseer do --watch` that prepared nothing was refused as a duplicate of itself with
        // `run_id_exists` — measured on Orca 1.4.209, where a Retry answered on the decision
        // page could never start. Written after it, a duplicate that is refused opens no tab
        // either, so it cannot replace the handle of the run it duplicated.
        if (!dryRun) {
            if (carried.continuesRun()) {
                // One chain, one Orca Run: the continuation's stage rows, workers and gate go
                // on the Run its predecessor used rather than on a new one per attempt.
                try {
                    OrcaLifecycle.inheritRun(root, carried.fromRunId(), runId);
                } catch (Exception notOurProblem) {
                    // A run without an Orca history is still a run.
                }
            }
            // Not for a preview. A dry run answers in seconds and dispatches nobody; opening
            // a window on the operator's board for it is the same overreach as moving its card.
            if (watchNarration != null) workspace.watch(watchNarration, runId);
        }
        // Both values are selected before any vendor can write. HEAD and the contract files
        // are mutable names/bytes; resolving them inside each later role or gate lets a worker
        // move the baseline or rewrite its own acceptance criteria.
        GitRepository git = new GitRepository(root, processes);
        String diffBaseCommit = git.mergeBase(task.baseRef());
        // The whole `.oseer` tree, not two files: a second task contract, a rewritten policy
        // or a deleted scenario file are all terms the run would then be judged by.
        Map<String, String> configSnapshot = OverseerTree.snapshot(root, runId);
        String contractHash = OverseerTree.digest(configSnapshot);
        RunBudget budget = new RunBudget(task.budget().maxRoleRuns(), task.budget().enforcedMaxCostUsd(),
                task.budget().maxElapsedMinutes(), clock);
        budget.alreadySpent(prior.roleRuns(), prior.costUsd(), prior.unpriced());
        // Only a continuation that carries verdicts continues the chain; see RunChain.
        if (carried.continuesRun() && carried.reuseJudgements()) {
            budget.inherit(RunChain.after(root, carried.fromRunId()));
        }
        // One runner for the whole loop: a vendor that ran out at implement time must not be
        // dispatched again at review time. The gate makes the budget count vendor calls, not
        // role invocations, so a failover cannot spend more than the task allowed, and it
        // lowers each call's wall clock to what the chain's deadline leaves.
        RoleRunner.DispatchGate dispatchGate = new RoleRunner.DispatchGate() {
            @Override public void requireDispatch() { budget.requireRoleRun(); }

            @Override public java.time.Duration wallClockCap() { return budget.wallClockCap(); }

            @Override public void requireRetryWait(long pauseMillis) { budget.requireRetryWait(pauseMillis); }

            // Asked before a failover dispatch. Answering no makes the role return the spent
            // call as its outcome, so the loop still charges it; throwing from the next
            // dispatch instead lost that call's cost and its unpriced count.
            @Override public boolean hasRoom() { return budget.hasRoom(); }

            @Override public void reserve(Double declaredBound) { budget.reserveSelected(declaredBound); }

            @Override public void settleAttempt(Object costUsd, Double declaredBound) {
                budget.settleAttempt(costUsd, declaredBound);
            }

            @Override public void release() { budget.releaseRoleRun(); }
        };
        RoleRunner roles = new RoleRunner(processes, dispatchGate, diffBaseCommit, runId,
                authorizedFailover, progress).observing(workspace::activity);
        if (sleeper != null) roles.withSleeper(sleeper);
        roles.withExecutors((profile, runner, repository) -> {
            dev.oseer.execution.Executors.Factory factory = executors == null
                    ? dev.oseer.execution.Executors::forProfile : executors;
            var executor = factory.forProfile(profile, runner, repository);
            // Both methods are forwarded. RoleRunner dispatches through the two-argument form,
            // and a one-method lambda would drop the activity sink there, so direct CLI tool
            // and output events would never reach the workspace card.
            return new dev.oseer.execution.RoleExecutor() {
                @Override public Result execute(Request request) throws Exception {
                    return deferApiInterruption(executor.execute(request), interruption);
                }

                @Override public Result execute(Request request,
                        java.util.function.Consumer<dev.oseer.execution.ExecutionActivity.Update> activity)
                        throws Exception {
                    return deferApiInterruption(executor.execute(request, activity), interruption);
                }
            };
        });
        if (availability != null) roles.availability(availability);
        if (carried.continuesRun() && !authorizedFailover.isEmpty()
                && "switch".equals(new ApprovalStore(root).read(carried.fromRunId()).decision())) {
            Map<String, Object> previous = Json.parseObject(Files.readString(root.resolve(".oseer/runs")
                    .resolve(carried.fromRunId()).resolve("task-run.json")));
            if (previous.get("failover_pending") instanceof Map<?, ?> pending) {
                if (StepQueries.vendorFailureSwitch(pending)) {
                    // Not the process-lifetime exhausted set. Every profile that already
                    // failed this stage is skipped for this stage's attempt only, and the
                    // approved successor is pinned. vendor_failovers is how many
                    // substitutions that stage has already used. A spent subscription is
                    // the other list: quota_exhausted_profiles is restored for the rest of
                    // this process. A pending record from before that field existed has no
                    // list, which is an empty exclusion.
                    Map<String, Object> copy = new LinkedHashMap<>();
                    pending.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    roles.honourConfirmedVendorFailure(copy);
                    if (pending.get("quota_exhausted_profiles") instanceof List<?> quotaSpent) {
                        java.util.Set<String> excluded = new java.util.LinkedHashSet<>();
                        for (Object name : quotaSpent) {
                            if (name != null) excluded.add(String.valueOf(name));
                        }
                        if (!excluded.isEmpty()) roles.excluding(excluded);
                    }
                } else {
                    if (pending.get("exhausted_profiles") instanceof List<?> names) {
                        java.util.Set<String> excluded = new java.util.LinkedHashSet<>();
                        names.forEach(name -> excluded.add(String.valueOf(name)));
                        roles.excluding(excluded);
                    }
                    // Quota confirm after an automatic vendor-failure switch. The approved
                    // profile is pinned, and the profile that already produced nothing stays
                    // out of this stage only. A spent subscription above stays spent for
                    // the rest of the process; a dropped connection does not.
                    if (pending.get("vendor_failed_profiles") instanceof List<?> failed
                            && !failed.isEmpty()) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        pending.forEach((key, value) -> copy.put(String.valueOf(key), value));
                        java.util.List<Object> onlyFailed = new java.util.ArrayList<>();
                        failed.forEach(onlyFailed::add);
                        copy.put("exhausted_profiles", onlyFailed);
                        roles.honourConfirmedVendorFailure(copy);
                    }
                }
            }
        }
        // Contract, then whatever the outer command left for this run id, then this
        // process's own flags. Conductor's inner `oseer run` has no flags of its own, so
        // the middle term is how `oseer do --conductor --use ...` reaches the roster at all.
        // The overlay is who the operator asked for. Advance does not reuse verdicts —
        // the tree or the contract may have changed — and it still has to keep the pin.
        // A fresh `oseer run --continue` and the decision page's own continuation do not
        // retype --use/--host; the previous run's summary is where those choices live.
        RunOverride inheritedSelection=carried.continuesRun()?priorModelSelection(root,carried.fromRunId(),user):RunOverride.NONE;
        RunOverride overlay = inheritedSelection
                .merged(loaded.task().use())
                .merged(carried.continuesRun() ? priorOverride(root, carried.fromRunId()) : RunOverride.NONE)
                .merged(handedOver(root, runId))
                .merged(this.override);
        GateRunner gates = new GateRunner(processes).withHome(user.home());

        Workflow workflow = user.policy() != null ? user.policy().workflow() : Workflow.builtIn();
        if (task.visualQa().agentEvidence()) workflow = workflow.forAgentEvidence();
        overlay = overlay.withWriterFixes(workflow);
        overlay.pin(roles);
        roles.overlay(overlay);
        boolean reviewByRisk = user.policy() != null && user.policy().reviewRequired(task.risk());
        boolean reviewRequired = task.reviewOnly() || (reviewByRisk
                && user.policy().roles().containsKey("reviewer"));
        // Opt-in, and only meaningful for a task that asked for visual QA at all.
        boolean visualRoleConfigured = user.policy() != null
                && user.policy().roles().containsKey("visual_qa");
        // Built here, from the same predicate the engine routes by, and consulted before the
        // first dispatch as well as before every repair. The preparation reservation uses
        // the same helper so run.json cannot describe a different chain from this summary.
        CallPlan callPlan = planFor(workflow, user, task,
                prior.includedInPlan() ? dev.oseer.run.Preparation.payingStages(user) : List.of());

        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", task.id());
        if (!overlay.isEmpty()) summary.put("run_override", overlay.toMap());
        summary.put("diff_base_commit", diffBaseCommit);
        summary.put("contract_sha256", contractHash);
        // The narrower hash a later run compares against when deciding whether a verdict it
        // already paid for still describes this tree. See TaskSpec.ResolvedTask.
        summary.put("acceptance_sha256", OverseerTree.acceptanceDigest(configSnapshot,
                root.relativize(loaded.taskFile()).toString().replace('\\', '/'),
                task.acceptanceFingerprint()));
        summary.put("risk", task.risk());
        if (loaded.task().mode() != null) summary.put("mode", loaded.task().mode());
        summary.put("dry_run", dryRun);
        summary.put("prepare", prior.mode());
        summary.put("max_fix_attempts", task.maxFixAttempts());
        summary.put("budget_max_role_runs", task.budget().maxRoleRuns());
        summary.put("budget_max_cost_usd", task.budget().maxCostUsd());
        if (task.budget().maxElapsedMinutes() != null) {
            summary.put("budget_max_elapsed_minutes", task.budget().maxElapsedMinutes());
        }
        RunChain.describe(summary, budget, runId, task);
        // The planner is offered a chain's contract gaps once. A retry after the amended run
        // stopped on a quota is the same chain, and must not send the same gaps back.
        if (carried.continuesRun() && carried.reuseJudgements()) {
            String offered = amendmentOffered(root, carried.fromRunId());
            if (offered != null) summary.put("contract_amended_from", offered);
        }
        summary.put("review_required", reviewRequired);
        summary.put("visual_qa_required", task.visualQa().required());
        summary.put("visual_qa_role", visualRoleConfigured);
        summary.put("baseline_required", !task.baselineCommands().isEmpty());
        summary.put("baseline_commands", task.baselineCommands());
        summary.put("acceptance_commands", task.acceptanceCommands());
        // What the readers were told already passed. Absent when the task recorded nothing,
        // so an older summary and a task without the key look the same.
        if (task.acceptanceEvidence().present()) {
            summary.put("acceptance_evidence", task.acceptanceEvidence().toMap());
        }
        summary.put("workflow_declared", user.policy() != null && user.policy().workflowDeclared());
        summary.put("failover_mode", user.policy() == null ? "confirm" : user.policy().failoverMode());
        summary.put("failover_on_vendor_failure",
                user.policy() == null ? "stop" : user.policy().vendorFailureMode());
        summary.put("vendor_failure_max_per_stage",
                user.policy() == null ? 1L : (long) user.policy().maxVendorFailovers());
        if (!authorizedFailover.isEmpty()) summary.put("authorized_failover", authorizedFailover);
        summary.put("workflow", workflow.toList());
        RunOverride frozenOverlay = overlay;
        summary.put("comparison_conditions", dev.oseer.ledger.ComparisonConditions.snapshot(
                workflow, user, task, overlay,
                stage -> selectableProfiles(stage, user, frozenOverlay, authorizedFailover),
                stage -> skipReason(stage, user, task, reviewByRisk)));
        Map<String, Object> plan = budgetPreview(callPlan, user, task);
        if (!budget.inherited().runs().isEmpty()) {
            plan.put("already_spent_by_chain", Map.of(
                    "runs", budget.inherited().runs(),
                    "role_runs", budget.inherited().roleRuns(),
                    "fix_attempts", budget.inherited().fixAttempts(),
                    "calls_remaining_under_cap", (long) budget.remaining()));
        }
        summary.put("budget_plan", plan);
        // The estimate the reservation was made on, when a planner compiled the contract
        // afterwards. It is kept beside the plan the loop measures by, with its own phase, so
        // a reader can see what was predicted before any contract existed and whether the
        // compiled contract changed the paying stages — rather than finding run.json and
        // task-run.json naming different stages with no record of why.
        if (prior.estimateAtReservation() != null) {
            summary.put("budget_plan_at_reservation", prior.estimateAtReservation());
            summary.put("reservation_matched_contract", prior.reservationMatchedContract());
        }
        // Which stages this task excludes, decided before the walk rather than during it.
        //
        // `skipped_stages` is a record of what the engine reached and passed over, so a run
        // that stops early records none of the exclusions after the stop — and every one of
        // them then reads as a stage that never got its turn. On a medium-risk task with no
        // visual contract that turned two stages nobody ever intended to run into two
        // outstanding obligations in the final report.
        List<Map<String, Object>> inapplicable = new ArrayList<>();
        for (Workflow.Stage stage : workflow.stages()) {
            String why = skipReason(stage, user, task, reviewByRisk);
            if (why != null) inapplicable.add(Map.of("stage", stage.name(), "reason", why));
        }
        summary.put("inapplicable_stages", inapplicable);
        summary.put("steps", steps);

        // Asked before the first dispatch, not after it. A worktree that was already dirty
        // outside the task's scope — a setup step that touched a lockfile, a half-finished
        // edit from yesterday — is the operator's to resolve, and no implementer can resolve
        // it: those paths are outside the blast radius it is allowed to touch. Discovering
        // that at the gates stage means having paid a vendor to find out.
        List<String> alreadyOutside = git.outsideScope(
                OverseerTree.sourcePaths(git.changedPaths(diffBaseCommit)), task.scopePaths());
        if (!alreadyOutside.isEmpty()) {
            summary.put("preexisting_violations", alreadyOutside);
            summary.put("resolution", "these paths were already changed before this run started "
                    + "and are outside the task's scope " + task.scopePaths() + ": "
                    + RunNarration.namedPaths(alreadyOutside) + ". Revert them, "
                    + "commit them, or widen the scope — an implementer cannot touch them.");
            if (!dryRun) return stop(ledger, summary, "preflight_outside_scope", steps, 0, budget);
            // A dry run has no candidate to protect, so this does not stop it — but it is
            // exactly what the preview is for. Measured: a fresh Orca worktree of an npm
            // project arrives with `npm install` having rewritten the lockfile, --dry-run
            // reported the whole chain resolving, and the real run refused before dispatching
            // anything. A preview that stays silent about the one thing that will stop the
            // run is worse than no preview.
            summary.put("would_stop", "preflight_outside_scope");
        }

        // The other thing that is the operator's to fix and nobody else's. A scenario that
        // states no assertion is a contract fault, and it used to be found by the browser
        // stage — after the implementer and the reviewer had been paid to produce and read a
        // candidate that could then not be looked at. Asked of the adapter itself, so the
        // grammar has one definition.
        String scenarioProblem = new VisualQaRunner(processes).contractProblem(loaded);
        if (scenarioProblem != null) {
            summary.put("resolution", scenarioProblem);
            if (!dryRun) return stop(ledger, summary, "visual_qa_contract_invalid", steps, 0, budget);
            summary.put("would_stop", "visual_qa_contract_invalid");
        }

        if (carried.continuesRun()) {
            summary.put("continued_from", carried.fromRunId());
        }
        if (carried.carriesRejection()) {
            summary.put("carries_human_rejection", true);
        }
        // Every reader the chain will need, asked about before the writer it will need to
        // differ from is paid. A two-vendor roster with one hat on each head used to find
        // out at the review stage, after the implementer had already written; the arithmetic
        // was available before anything was spent, and a person is entitled to it then.
        // A strict money cap has to be provable before it is promised. Every paying profile
        // on the roster declares an upper bound per call, or the cap cannot be enforced and
        // the run says so instead of spending under a number that was never a cap.
        if (task.budget().strictCostCap()) {
            Map<String, Object> unbounded = strictCapGap(workflow, user, task, reviewByRisk,
                    overlay, authorizedFailover);
            if (unbounded != null) {
                summary.put("cost_cap", "strict");
                summary.put("cost_cap_gap", unbounded);
                summary.put("resolution", String.valueOf(unbounded.get("message")));
                if (!dryRun) return stop(ledger, summary, "cost_cap_unenforceable", steps, 0, budget);
                summary.putIfAbsent("would_stop", "cost_cap_unenforceable");
            } else {
                summary.put("cost_cap", "strict");
                budget.strictBounds(strictBounds(workflow, user, task, reviewByRisk,
                        overlay, authorizedFailover));
            }
        }
        // A policy file that exists and does not parse is not the same as no policy. Treating
        // it as none ran the built-in chain, skipped every role stage as `role_not_configured`
        // and previewed `ok` over a candidate nobody would write and nobody would read.
        // Measured on a dry run whose multi-line flow mapping the strict YAML reader refused.
        if (user.policyPresent() && user.policy() == null) {
            String problem = user.problems() == null ? null : user.problems().get("policy.yaml");
            summary.put("policy_problem", problem);
            summary.put("resolution", "policy.yaml does not parse: " + problem + ". Fix the file "
                    + "(oseer doctor and oseer roster both list the problem) before anything "
                    + "is dispatched; the built-in chain is not a substitute for a policy you wrote.");
            progress.line("      policy.yaml does not parse: " + problem);
            progress.line("      stopping before the first dispatch rather than running the "
                    + "built-in chain over it");
            return stop(ledger, summary, "policy_invalid", steps, 0, budget);
        }
        Map<String, Object> overlayGap = overlayGap(root, workflow, user, task, reviewByRisk, roles,
                overlay);
        if (overlayGap != null) {
            summary.put("unavailable_role", overlayGap);
            summary.put("resolution", String.valueOf(overlayGap.get("message")));
            progress.line("      " + overlayGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, "run_override_invalid", steps, 0, budget);
            summary.putIfAbsent("would_stop", "run_override_invalid");
        }
        Map<String, Object> judgeGap = judgeGap(workflow, user, task, reviewByRisk, roles);
        if (judgeGap != null) {
            summary.put("unavailable_role", judgeGap);
            summary.put("resolution", String.valueOf(judgeGap.get("message")));
            progress.line("      " + judgeGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, RoleResolver.JUDGE_NOT_READ_ONLY, steps, 0, budget);
            summary.putIfAbsent("would_stop", RoleResolver.JUDGE_NOT_READ_ONLY);
        }
        Map<String, Object> toolingGap = toolingGap(root, workflow, user, task, reviewByRisk, roles);
        if (toolingGap != null) {
            String stopReason = String.valueOf(toolingGap.get("stop_reason"));
            summary.put("unavailable_role", toolingGap);
            summary.put("resolution", String.valueOf(toolingGap.get("message")));
            progress.line("      " + toolingGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, stopReason, steps, 0, budget);
            summary.putIfAbsent("would_stop", stopReason);
        }
        boolean selectModels=!dryRun && taskRouting!=null && !task.budget().strictCostCap() && modelSelectionEnabled(user);
        Map<String, Object> readerGap = selectModels && !task.reviewOnly() ? null
                : readerGap(root, workflow, user, task, reviewByRisk, roles);
        if (readerGap != null) {
            summary.put("unavailable_role", readerGap);
            summary.put("resolution", String.valueOf(readerGap.get("message")));
            progress.line("      " + readerGap.get("message"));
            progress.line("      stopping before the first dispatch rather than after it");
            if (!dryRun) return stop(ledger, summary, "independent_review_unavailable", steps, 0, budget);
            summary.putIfAbsent("would_stop", "independent_review_unavailable");
        }
        String candidateFingerprint = git.sourceFingerprint(diffBaseCommit);
        summary.put("candidate_fingerprint", candidateFingerprint);
        // After the preflights, so a stage nobody can fill has already been reported as the
        // fault it is rather than as a blank in a table, and before the header, because the
        // header is the last thing printed before money starts being spent.
        List<Map<String, Object>> cast = cast(root, workflow, user, task, reviewByRisk, roles, overlay);
        summary.put("cast", cast);
        List<Map<String,Object>> effortWarnings=List.of();
        if(!dryRun && taskRouting!=null){
            List<TaskRouting.Target> targets=new ArrayList<>();
            RunOverride configuredSelection=RunOverride.NONE;
            if(selectModels){
                var active=new ArrayList<Workflow.Stage>();
                for(var stage:workflow.stages())if(stage.kind()==Workflow.Kind.ROLE && skipReason(stage,user,task,reviewByRisk)==null)active.add(stage);
                var prepared=ModelRoutingPlan.prepare(root,user,task,active,workflow,roles,overlay,readiness,progress::line,carried.continuesRun()?carried.fromRunId():null);
                summary.put("model_selection_checks",prepared.checks());
                if(prepared.gap()!=null){
                    summary.put("unavailable_role",prepared.gap());
                    return stop(ledger,summary,"model_selection_unavailable",steps,0,budget);
                }
                targets.addAll(prepared.targets());
                var defaults=new LinkedHashMap<String,String>();
                for(var target:targets)defaults.put(target.stage(),target.profile().name());
                configuredSelection=new RunOverride(defaults,Map.of(),Map.of());
            } else for(var stage:workflow.stages()){
                if(stage.kind()!=Workflow.Kind.ROLE || skipReason(stage,user,task,reviewByRisk)!=null)continue;
                var selected=roles.peek(root,user,stage.name(),stage.role(),RoleResolver.Writers.NONE,workflow.rotationPositionOf(stage));
                if(selected!=null)targets.add(new TaskRouting.Target(stage.name(),stage.role(),selected));
            }
            var routed=taskRouting.route(user,root,task,targets,progress::line);
            var routeReport=new LinkedHashMap<String,Object>(routed.report());
            boolean profileAdvice=routed.overrides().toMap().get("profile") instanceof Map<?,?> pins&&!pins.isEmpty();
            if(selectModels?!ModelRoutingPlan.permitted(routed.overrides(),targets,overlay,user,task):profileAdvice){
                routeReport.put("model_selection_rejected","final_cast_not_admissible");
                routeReport.put("applied_profiles",Map.of());routeReport.put("applied_efforts",Map.of());
                routed=new TaskRouting.Result(RunOverride.NONE,routeReport);
            }
            if(!routed.overrides().isEmpty() || !configuredSelection.isEmpty()){
                // Explicit task/CLI/handover choices remain authoritative over classifier advice.
                overlay=configuredSelection.merged(routed.overrides()).merged(overlay).withWriterFixes(workflow);overlay.pin(roles);roles.overlay(overlay);
                cast=cast(root,workflow,user,task,reviewByRisk,roles,overlay);summary.put("cast",cast);
                Map<String,Object> effective=new LinkedHashMap<>();
                for(var row:cast)if(row.get("effort")!=null)effective.put(String.valueOf(row.get("stage")),row.get("effort"));
                routeReport.put("effective_efforts",effective);routeReport.put("operator_overrides_take_precedence",true);
                var suggested=routeReport.get("applied_efforts");routeReport.put("suggested_efforts",suggested);
                Map<String,Object> applied=new LinkedHashMap<>();
                if(suggested instanceof Map<?,?> levels)for(var entry:levels.entrySet())
                    if(java.util.Objects.equals(effective.get(entry.getKey()),entry.getValue()))applied.put(String.valueOf(entry.getKey()),entry.getValue());
                routeReport.put("applied_efforts",applied);
            }
            cast=cast(root,workflow,user,task,reviewByRisk,roles,overlay);summary.put("cast",cast);
            var effectiveProfiles=new LinkedHashMap<String,Object>();
            for(var row:cast)if(row.get("profile")!=null)effectiveProfiles.put(String.valueOf(row.get("stage")),row.get("profile"));
            Object suggestions=routeReport.get("suggested_profiles");
            if(suggestions==null)suggestions=routeReport.getOrDefault("applied_profiles",Map.of());
            routeReport.put("suggested_profiles",suggestions);routeReport.put("effective_profiles",effectiveProfiles);
            var appliedProfiles=new LinkedHashMap<String,Object>();
            if(suggestions instanceof Map<?,?> proposed)for(var entry:proposed.entrySet())
                if(java.util.Objects.equals(entry.getValue(),effectiveProfiles.get(entry.getKey())))appliedProfiles.put(String.valueOf(entry.getKey()),entry.getValue());
            routeReport.put("applied_profiles",appliedProfiles);
            if(selectModels){
                summary.put("model_selection_override",Map.of("profile",effectiveProfiles));
                if(!inheritedSelection.isEmpty())routeReport.put("selection_reused_from",carried.fromRunId());
            }
            // Only levels that survive explicit overrides are warned about; printed before the header.
            Object appliedNow=routeReport.get("applied_efforts");
            effortWarnings=TaskRouting.keptWarnings(routeReport.get("effort_warnings"),appliedNow instanceof Map<?,?> m?m:Map.of());
            routeReport.put("effort_warnings",effortWarnings);
            if(!effortWarnings.isEmpty())summary.put("effort_warnings",effortWarnings);
            summary.put("task_routing",routeReport);
            dev.oseer.execution.ModelCheck.atomicWrite(ledger.runDirectory().resolve("task-routing.json"),Json.write(routeReport));
            if(selectModels&&!ModelRoutingPlan.permitted(RunOverride.NONE,targets,overlay,user,task)){
                summary.put("unavailable_role",Map.of("code","final_cast_not_admissible"));
                return stop(ledger,summary,"independent_review_unavailable",steps,0,budget);
            }
        }
        if(selectModels){
            var finalGap=readerGap(root,workflow,user,task,reviewByRisk,roles);
            if(finalGap!=null){summary.put("unavailable_role",finalGap);return stop(ledger,summary,"independent_review_unavailable",steps,0,budget);}
        }
        // After routing, because routing can set the effort a stage runs at, and a kept verdict
        // has to be compared with the effort this run would dispatch. Measured 2026-10-28: a
        // continuation built the pool against the profile's default `xhigh`, declined the
        // implementer that had run at Jev's `medium`, and paid for it again at `medium`.
        ContinuationEvidence evidence = new ContinuationEvidence(root, progress);
        Map<String, Map<String, Object>> reusable = carried.reuseJudgements()
                ? evidence.judgements(carried.fromRunId(), contractHash,
                        String.valueOf(summary.get("acceptance_sha256")), candidateFingerprint,
                        workflow, user, summary, overlay, authorizedFailover)
                : Map.of();
        if (!dryRun && readiness != null) {
            List<dev.oseer.config.Profile> checks = new ArrayList<>();
            var selected = new LinkedHashSet<String>();
            RoleResolver.Writers wouldWrite = initialPreviewWriters(task);
            for (Workflow.Stage stage : workflow.stages()) {
                if (stage.kind() != Workflow.Kind.ROLE || skipReason(stage, user, task, reviewByRisk) != null) continue;
                RoleResolver.Writers judgedBy = judgesCandidate(stage, task) ? wouldWrite : RoleResolver.Writers.NONE;
                dev.oseer.config.Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                        judgedBy, workflow.rotationPositionOf(stage));
                if (chosen != null) {
                    checks.add(chosen);
                    selected.add(chosen.name());
                    wouldWrite = withPreviewWriter(wouldWrite, chosen);
                }
                // A review-only task runs no repair round, so the repairer is never dispatched.
                // Checking it anyway stopped the first real review of an outside branch with
                // model_check_failed on an implementer CLI that was signed out.
                if (stage.fixWith() != null && !task.reviewOnly()) {
                    dev.oseer.config.Profile fixer = roles.peek(root, user, stage.name() + "/fix", stage.fixWith(),
                            RoleResolver.Writers.NONE, RoleRunner.UNSTAGED);
                    if (fixer != null) {
                        checks.add(fixer);
                        selected.add(fixer.name());
                    }
                    var fixSpec = user.policy() == null ? null : user.policy().roles().get(stage.fixWith());
                    if (!selectModels && fixSpec != null) for (String name : fixSpec.profiles()) {
                        var alternative = user.profiles().get(name);
                        if (alternative != null && !roles.excludedForRouting(alternative.name()) && alternative.verified() && alternative.role().equals(stage.fixWith())
                                && (fixer == null || !alternative.name().equals(fixer.name()))) checks.add(alternative);
                    }
                }
                // All verified roster alternatives for an active role can be selected by rotation
                // or explicit failover. A failed ping never changes that routing automatically.
                var spec = user.policy() == null ? null : user.policy().roles().get(stage.role());
                if (!selectModels && spec != null) for (String name : spec.profiles()) {
                    var alternative = user.profiles().get(name);
                    if (alternative != null && !roles.excludedForRouting(alternative.name()) && alternative.verified() && alternative.role().equals(stage.role())
                            && (chosen == null || !alternative.name().equals(chosen.name()))) checks.add(alternative);
                }
            }
            Map<String, Object> checked = readiness.check(user, root, checks, task.budget().strictCostCap(), progress::line);
            if(!effortWarnings.isEmpty()){checked=new LinkedHashMap<>(checked);checked.put("effort_warnings",effortWarnings);}
            summary.put("model_check", checked);
            dev.oseer.execution.ModelCheck.atomicWrite(ledger.runDirectory().resolve("model-check.json"), Json.write(checked));
            var standby = Boolean.TRUE.equals(checked.get("ok")) ? null
                    : dev.oseer.execution.ModelCheck.standbyReadiness(checked, user.policy(), selected, java.util.Set.of());
            if (standby != null) {
                RunReadiness.narrateStandbys(standby, progress::line);
                if (!standby.covered().isEmpty()) summary.put("model_check_covered_by_fallback", standby.covered());
                if (!standby.unavailable().isEmpty()) summary.put("model_check_unavailable_standbys", standby.unavailable());
            } else if (!Boolean.TRUE.equals(checked.get("ok"))) {
                progress.line("check stopped the run; restore authentication/model access and check again");
                return stop(ledger, summary, "model_check_failed", steps, 0, budget);
            }
        }
        for (var warning : effortWarnings) progress.line("warn  " + warning.get("message"));
        RunNarration.header(progress, workspace, task, runId, workflow, dryRun, callPlan, budget, cast);
        if (carried.carriesRejection()) {
            progress.line("note  starting from the rejection recorded on " + carried.fromRunId()
                    + "; the implementer is given its reason verbatim");
            progress.blank();
        } else if (carried.carriesNote()) {
            progress.line("note  starting from the advance recorded on " + carried.fromRunId()
                    + "; the implementer is given that note verbatim");
            progress.blank();
        }
        ContinuationEvidence.BaselineReceipt baselineReceipt = !dryRun && !task.baselineCommands().isEmpty()
                && carried.continuesRun()
                ? evidence.baseline(carried.fromRunId(), task.id(), contractHash,
                        diffBaseCommit, candidateFingerprint, summary)
                : null;
        if (!task.baselineCommands().isEmpty()) {
            if (dryRun) {
                steps.add(Map.of("step", "baseline", "attempt", 0L, "dry_run", true));
            } else if (baselineReceipt != null) {
                summary.put("baseline_ok", true);
                summary.put("baseline_code", "reused");
                summary.put("baseline_reused_from", baselineReceipt.runId());
                summary.put("baseline_receipt_run", baselineReceipt.runId());
                summary.put("baseline_report", baselineReceipt.report().toString());
                summary.put("baseline_report_sha256", baselineReceipt.sha256());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("step", "baseline");
                entry.put("attempt", 0L);
                entry.put("ok", true);
                entry.put("code", "reused");
                entry.put("report", baselineReceipt.report().toString());
                entry.put("reused", true);
                entry.put("reused_from", baselineReceipt.runId());
                steps.add(entry);
                progress.line("base  reused the green project baseline from "
                        + baselineReceipt.runId());
                workspace.note("baseline · green on " + baselineReceipt.runId() + " · reused");
                progress.blank();
            } else {
                progress.line("base  project tests before the first vendor: "
                        + String.join(", ", task.baselineCommands()));
                workspace.note("baseline · project tests · running");
                long started = System.nanoTime();
                GateRunner.Outcome baseline;
                try (Heartbeat alive = Heartbeat.over("baseline gates", progress, workspace)) {
                    baseline = gates.runBaseline(loaded, StageIds.stepRunId(runId, "baseline", 0),
                            configSnapshot, diffBaseCommit);
                }
                long millis = (System.nanoTime() - started) / 1_000_000L;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("step", "baseline");
                entry.put("attempt", 0L);
                entry.put("ok", baseline.ok());
                entry.put("code", baseline.code());
                entry.put("report", baseline.report().toString());
                steps.add(entry);
                summary.put("baseline_ok", baseline.ok());
                summary.put("baseline_code", baseline.code());
                summary.put("baseline_report", baseline.report().toString());
                summary.put("baseline_report_sha256",
                        GitRepository.contentSha256(baseline.report()));
                if (baseline.ok()) {
                    summary.put("baseline_receipt_run", runId);
                    progress.line("      ok    " + Progress.elapsed(millis));
                    workspace.note("baseline · project tests · ok · " + Progress.elapsed(millis));
                    progress.blank();
                } else if ("gate_environment_error".equals(baseline.code())) {
                    progress.line("      FAILED  " + Progress.elapsed(millis) + "   "
                            + baseline.code());
                    workspace.note("baseline · project tests · environment error · no vendor dispatched");
                    GateStage.recordEnvironmentError(summary, baseline);
                    return stop(ledger, summary, "gate_environment_error", steps, 0, budget);
                } else {
                    progress.line("      FAILED  " + Progress.elapsed(millis) + "   "
                            + baseline.code());
                    workspace.note("baseline · project tests · failed " + baseline.code()
                            + " · no vendor dispatched");
                    summary.put("resolution", "Project checks failed before the first vendor "
                            + "dispatch. Fix the pre-existing failure or deliberately change "
                            + "the project baseline contract, then retry; no agent was asked "
                            + "to repair an unknown earlier breakage.");
                    return stop(ledger, summary, "baseline_failed", steps, 0, budget);
                }
            }
        }
        if (!task.reproduceCommands().isEmpty()) {
            summary.put("reproduction_commands", task.reproduceCommands());
            if (dryRun) {
                steps.add(Map.of("step", "reproduction", "attempt", 0L, "dry_run", true));
                progress.line("red   must fail before any vendor is dispatched: "
                        + String.join(", ", task.reproduceCommands()));
            } else if (!OverseerTree.sourcePaths(git.changedPaths(diffBaseCommit)).isEmpty()) {
                // A candidate cannot demonstrate the original defect. Only a verified receipt
                // from this continuation can speak for the base; missing proof is made visible.
                ContinuationEvidence.ReproductionReceipt receipt = carried.continuesRun()
                        ? evidence.reproduction(carried.fromRunId(), task.id(),
                                String.valueOf(summary.get("acceptance_sha256")), diffBaseCommit)
                        : null;
                if (receipt == null) {
                    summary.put("reproduction_status", "not_run_candidate_present");
                    steps.add(Map.of("step", "reproduction", "attempt", 0L,
                            "status", "not_run_candidate_present"));
                    progress.line("red   not_run_candidate_present: no verified base proof; "
                            + "reproduction cannot run on a candidate");
                } else {
                    summary.put("reproduction_ok", true);
                    summary.put("reproduction_status", "reused");
                    summary.put("reproduction_reused_from", receipt.runId());
                    summary.put("reproduction_receipt_run", receipt.runId());
                    summary.put("reproduction_report", receipt.report().toString());
                    summary.put("reproduction_report_sha256", receipt.sha256());
                    summary.put("reproduction_results", receipt.commands());
                    steps.add(Map.of("step", "reproduction", "attempt", 0L, "ok", true,
                            "reused", true, "reused_from", receipt.runId(),
                            "report", receipt.report().toString()));
                    progress.line("red   reused verified base proof from " + receipt.runId());
                }
            } else {
                progress.line("red   checking acceptance fails before the first vendor: "
                        + String.join(", ", task.reproduceCommands()));
                GateRunner.Outcome reproduction;
                try (Heartbeat alive = Heartbeat.over("reproduction gates", progress, workspace)) {
                    reproduction = gates.runReproduction(loaded, StageIds.stepRunId(runId, "reproduction", 0),
                            configSnapshot, diffBaseCommit);
                }
                steps.add(Map.of("step", "reproduction", "attempt", 0L, "ok", reproduction.ok(),
                        "code", reproduction.code(), "report", reproduction.report().toString()));
                summary.put("reproduction_ok", reproduction.ok());
                summary.put("reproduction_code", reproduction.code());
                summary.put("reproduction_report", reproduction.report().toString());
                summary.put("reproduction_report_sha256", GitRepository.contentSha256(reproduction.report()));
                summary.put("reproduction_results", reproduction.data().getOrDefault("commands", List.of()));
                if (!reproduction.ok()) {
                    String reason = "reproduction_passed_before_change".equals(reproduction.code())
                            ? "reproduction_passed_before_change" : "reproduction_inconclusive";
                    summary.put("resolution", "reproduction_passed_before_change".equals(reason)
                            ? "Acceptance already passed on the unchanged tree: "
                                    + reproduction.data().get("passed_commands")
                            : "Reproduction did not prove the defect: " + reproduction.code());
                    progress.line("red   " + reason + "; no vendor dispatched");
                    return stop(ledger, summary, reason, steps, 0, budget);
                }
                summary.put("reproduction_receipt_run", runId);
                summary.put("reproduction_status", "proved_on_base");
                progress.line("red   every reproduction command failed on the unchanged tree");
            }
        }
        WorkflowPlan workflowPlan = new WorkflowPlan(loaded, user, workflow, callPlan, dryRun);
        CandidateBaseline baseline = new CandidateBaseline(git, diffBaseCommit, configSnapshot);
        WorkflowEvidence workflowEvidence = new WorkflowEvidence(ledger, steps, summary, carried, reusable);
        WorkflowChecks checks = new WorkflowChecks(workflowPlan, baseline, workflowEvidence, gates,
                new VisualStage(processes, visualCheck));
        WorkflowEngine engine = new WorkflowEngine(workflowPlan, baseline, workflowEvidence, budget, roles, checks,
                new StageProgress(workflowPlan, progress, workspace));
        try (InterruptionGuard guard = new InterruptionGuard(
                () -> recordInterruption(ledger, summary, steps, engine.attempt(), budget), !dryRun)) {
        try {
            try {
                engine.run();
            } finally {
                if (!guard.conclude()) InterruptionGuard.yieldToShutdown();
            }
        } catch (StopException stopped) {
            if ("contract_mutated".equals(stopped.reason())) {
                ContractWatch.recordContractChanges(loaded, configSnapshot, summary);
            }
            return stop(ledger, summary, stopped.reason(), steps, engine.attempt(), budget);
        } catch (RunBudget.ExceededException exceeded) {
            summary.put("budget_stop", exceeded.getMessage());
            summary.put("budget_limit_hit", exceeded.limit());
            return stop(ledger, summary, "budget_exhausted", steps, engine.attempt(), budget);
        } catch (dev.oseer.execution.WorktreeLease.Refused refused) {
            summary.put("resolution", refused.getMessage());
            summary.put("execution_state", refused.report().get("execution_state"));
            return stop(ledger, summary, refused.code(), steps, engine.attempt(), budget);
        } catch (HomeCorpus.UnavailableException unavailable) {
            summary.put("corpus_status", "error");
            summary.put("corpus_error", unavailable.getMessage());
            return stop(ledger, summary, "ledger_unavailable", steps, engine.attempt(), budget);
        } catch (Exception unexpected) {
            summary.put("unexpected_error", Map.of(
                    "type", unexpected.getClass().getName(),
                    "message", String.valueOf(unexpected.getMessage())));
            return stop(ledger, summary, "unexpected_error", steps, engine.attempt(), budget);
        }
        }
        int attempt = engine.attempt();
        summary.putIfAbsent("skipped_stages", engine.skipped());

        if (!dryRun && !ContractWatch.contractMatches(loaded, configSnapshot)) {
            ContractWatch.recordContractChanges(loaded, configSnapshot, summary);
            return stop(ledger, summary, "contract_mutated", steps, attempt, budget);
        }

        summary.put("attempts_used", (long) attempt);
        summary.put("total_cost_usd", budget.spent());
        summary.put("cost_status",budget.unpriced()>0?(budget.spent()>0?"partial":"unknown"):"reported");
        summary.put("role_runs", (long) budget.runs());
        summary.put("unpriced_calls", (long) budget.unpriced());
        if (budget.unpricedCharge() > 0) {
            summary.put("cost_cap_unpriced_charge_usd", budget.unpricedCharge());
        }
        summary.put("cost_ceiling_binding", budget.chainUnpriced() == 0);
        RunChain.describe(summary, budget, runId, task, attempt);
        // Which judging stages did not see the tree as it finally stands. A workflow may
        // legitimately choose not to pay for a second review, but "every stage passed" must
        // not be allowed to mean "every stage passed something, at some point".
        List<String> stale = StepQueries.staleJudgements(steps);
        if (!stale.isEmpty()) summary.put("stale_judgements", stale);
        // A preview completes nothing, so it has no completion to describe. Reporting every
        // stage as outstanding would be true and useless, and would read as a warning.
        if (!dryRun) RunCompletion.describeCompletion(summary);
        summary.put("ok", true);
        if (dryRun) {
            // A preview has not produced a candidate a human can accept. Persisting a real
            // pending decision here makes status noisy and, worse, makes a dry run look like
            // an authorization boundary was actually reached.
            summary.put("reason", "dry_run");
            summary.put("next_action", "none");
            Path file = ledger.writeReport("task-run", summary);
            ledger.append("task_dry_run", summary);
            ledger.recordCorpusVisibility(summary);
            ledger.writeReport("task-run", summary);
            progress.blank();
            progress.line("done  dry_run   nothing was dispatched and nothing was spent");
            // The preview's whole job is to answer "will this work" before it costs anything,
            // and the cheapest way for it to be wrong is arithmetic. A cap that cannot reach
            // the end of the declared chain is knowable here, for free, and used to be
            // discovered an hour and several vendor calls later.
            long cap = task.budget().maxRoleRuns();
            progress.line("      requested cap " + (cap > 0 ? cap + " vendor call(s)" : "none")
                    + "; a clean pass needs " + callPlan.minimumToFinish()
                    + " (" + String.join(", ", callPlan.payingStages()) + ")");
            if (cap > 0 && cap < callPlan.minimumToFinish()) {
                progress.line("      that cap cannot finish this chain"
                        + (task.reviewOnly() ? "" : " even with no repairs"));
            }
            progress.line("      max_cost_usd " + Progress.money(task.budget().maxCostUsd())
                    + (task.budget().maxCostUsd() == 0 ? " disables the money limit"
                    : task.budget().strictCostCap() ? " is a strict cap reserved against declared call bounds"
                    : " is a threshold on the spend vendors report, not a strict cap"));
            if (task.reviewOnly()) {
                progress.line("      review mode: findings stop the run for a person; no repair round is paid for");
            }
            for (Map<String, Object> branch : task.reviewOnly() ? List.<Map<String, Object>>of() : StepQueries.recoveryBranches(summary)) {
                progress.line("      if " + branch.get("stage") + " sends the work back: "
                        + branch.get("calls_to_repair_and_finish") + " call(s) to repair and "
                        + "finish — " + (Boolean.TRUE.equals(branch.get("reachable_under_cap"))
                            ? "affordable" : "NOT affordable under this cap"));
            }
            if (summary.get("would_stop") != null) {
                progress.line("      a real run would stop here: " + summary.get("would_stop"));
                progress.line("      " + summary.get("resolution"));
            }
            progress.blank();
            return new Outcome(true, "dry_run", "none", file, summary);
        }

        // A passing candidate whose readers found the acceptance too weak to have proved it
        // goes to the planner first when the policy routes contract gaps there: once per
        // chain, before a person is asked to accept on terms a reader said were short.
        List<Map<String, Object>> gaps = task.reviewOnly() ? List.of() : contractGapsToPlan(user, summary);
        if (!gaps.isEmpty()) {
            List<Object> ids = new ArrayList<>();
            for (Map<String, Object> gap : gaps) ids.add(gap.get("id"));
            // The planner and the plan reviewer are calls of this chain, and so is every
            // reading the amended run takes again. Routing there is a promise to pay for all of
            // it; a chain that cannot keeps its gaps listed at the gate instead.
            int calls = dev.oseer.run.Preparation.mostAmendmentCalls(user)
                    + callPlan.readingsWithout("implementer");
            String unaffordable = budget.strict() ? "strict_cost_cap"
                    : !budget.hasRoom() ? "budget"
                    : budget.remaining() < calls ? "max_role_runs" : null;
            if (unaffordable == null) {
                summary.put("contract_gaps_to_plan", ids);
                summary.put("contract_amendment_calls", (long) calls);
                progress.line("      " + gaps.size() + " reader finding(s) say the acceptance is too weak "
                        + "to have proved this candidate; the planner amends it before the gate");
                return stop(ledger, summary, CONTRACT_AMENDMENT, steps, engine.attempt(), budget);
            }
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("reason", unaffordable);
            skipped.put("gaps", ids);
            skipped.put("calls_needed", (long) calls);
            if (budget.remaining() != Integer.MAX_VALUE) skipped.put("calls_remaining", (long) budget.remaining());
            summary.put("contract_amendment_skipped", skipped);
            progress.line("      " + gaps.size() + " reader finding(s) say the acceptance is too weak, but "
                    + ("strict_cost_cap".equals(unaffordable)
                        ? "a strict cost cap cannot bound what the planner would spend"
                        : "the chain cannot pay for the planner (" + calls + " call(s) at most, with "
                            + "the readings taken again)")
                    + "; they go to the gate as listed findings");
        }

        summary.put("reason", "ready_for_human");
        summary.put("safe_next_step", "oseer approve " + runId + " --decision accept|reject");
        summary.put("next_step", NextStep.of("ready_for_human", summary, root));
        summary.put("next_action", "human_gate");
        Path file = ledger.writeReport("task-run", summary);
        // Named, not asserted. The chain is declared now, so "all machine, review and visual
        // gates passed" was a sentence that could describe a run in which review never ran.
        // What the human is being asked to accept is what actually executed.
        String verdict = "every stage that ran passed: " + String.join(", ", StepQueries.executedStages(steps));
        if (summary.get("baseline_reused_from") != null) {
            verdict += "; project baseline reused from " + summary.get("baseline_reused_from");
        }
        // The person accepting this is entitled to know which of those verdicts were reached
        // in this run and which were carried over. They are about the same bytes — that is
        // what let them be carried — but "passed" and "passed, earlier, elsewhere" are
        // different sentences and the gate should not blur them.
        if (summary.get("reused_judgements") instanceof Map<?, ?> reused) {
            verdict += ". " + reused.get("stages") + " were not re-run: they passed this exact "
                    + "tree on run " + reused.get("from") + " and neither the source nor the "
                    + "contract has changed since";
            if (summary.get("contract_change_budget_only") instanceof Map<?, ?> budgetOnly) {
                verdict += " — except its call ceiling, raised from "
                        + budgetOnly.get("prior_max_role_runs") + " to "
                        + budgetOnly.get("now_max_role_runs") + ", which is not part of what "
                        + "the work is judged by";
            }
        }
        if (!stale.isEmpty()) {
            verdict += ". WARNING: " + String.join(", ", stale) + " last judged an earlier tree; "
                    + "code changed after that and was not judged again";
        }
        // Said at the gate, because it is the one label a person accepting the work most
        // needs and the one a same-vendor roster is most likely to have quietly weakened.
        if (summary.get("review_assurance") instanceof String assurance) {
            verdict += ". Review assurance: " + assurance
                    + ("independent".equals(assurance) ? ""
                        : "same_vendor_peer".equals(assurance)
                            ? " (a declared same-vendor pair read it; that is not an independent review)"
                            : "unproven".equals(assurance)
                                ? unprovenReason(summary)
                                : " (a co-author of the candidate read it)");
        }
        // A chain can reach the human gate with stages still owed — a conditional stage that
        // ran out of room, say. The person being asked to accept is entitled to that sentence
        // rather than to a list of what happened to pass.
        if (summary.get("pending_stages") instanceof List<?> owed && !owed.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object row : owed) {
                if (row instanceof Map<?, ?> stage) names.add(String.valueOf(stage.get("stage")));
            }
            verdict += ". The declared workflow did not finish: " + String.join(", ", names)
                    + " never completed";
        }
        HumanDecision decision = new ApprovalStore(root).createSuccess(runId, task.id(),
                verdict, file, git.sourceFingerprint(diffBaseCommit),
                task.budget().gateTtlHours() == null ? null
                        : java.time.Duration.ofHours(task.budget().gateTtlHours()));
        if (decision.expiresAt() != null) summary.put("gate_expires_at", decision.expiresAt().toString());
        DecisionRecord.addDecision(summary, root, decision);
        DecisionRecord.publishGate(orcaGate, processes, summary, root, decision, task.goal());
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", runId, "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(runId)).toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        ledger.recordCorpusVisibility(summary);
        ledger.writeReport("task-run", summary);
        RunNarration.footer(progress, workspace, root, runId, "ready_for_human", "human_gate", budget, summary);
        return new Outcome(true, "ready_for_human", "human_gate", file, summary);
    }


    /** Plans the same set of stages for preparation reservations and execution. */
    static CallPlan planFor(Workflow workflow, UserConfig user, TaskSpec.ResolvedTask task,
                            List<String> preparationStages) {
        return WorkflowAdmission.planFor(workflow, user, task, preparationStages);
    }

    /** Lists the roles the saved task settings would dispatch. */
    public static List<Map<String, Object>> dispatchPreview(Path root, ConfigLoader.Loaded loaded,
                                                            UserConfig user,
                                                            RoleResolver.Availability availability) {
        return WorkflowPreview.dispatchPreview(root, loaded, user, availability);
    }

    /** Lists the roles after applying this invocation's settings. */
    public static List<Map<String, Object>> dispatchPreview(Path root, ConfigLoader.Loaded loaded,
                                                            UserConfig user,
                                                            RoleResolver.Availability availability,
                                                            RunOverride invocation) {
        return WorkflowPreview.dispatchPreview(root, loaded, user, availability, invocation);
    }

    /** Returns the roster, estimated call count, and settings problems for a fresh run. */
    public static Map<String, Object> settingsPreview(Path root, ConfigLoader.Loaded loaded,
                                                      UserConfig user, RunOverride invocation,
                                                      RoleResolver.Availability availability) {
        return WorkflowPreview.settingsPreview(root, loaded, user, invocation, availability);
    }


    /**
     * The overlay the outer command left in this run's directory, or none.
     *
     * A malformed handover refuses dispatch: silently falling back could spend a different
     * subscription from the one the operator chose.
     */
    static RunOverride handedOver(Path root, String runId) {
        Path file = root.resolve(".oseer/runs").resolve(runId)
                .resolve(dev.oseer.ledger.EvidenceLedger.RUN_OVERRIDE);
        if (!Files.isRegularFile(file)) return RunOverride.NONE;
        try {
            return RunOverride.fromMap(Json.parseObject(Files.readString(file)));
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("cannot read run override " + file, unreadable);
        }
    }

    /** Returns the admitted roster for manual classification. */
    public static List<TaskRouting.Target> classificationTargets(Path root, ConfigLoader.Loaded loaded,
                                                                 UserConfig user,
                                                                 RoleResolver.Availability availability)
            throws Exception {
        return WorkflowPreview.classificationTargets(root, loaded, user, availability);
    }

    private static RunOverride priorModelSelection(Path root,String runId,UserConfig user)throws java.io.IOException{
        Path file=root.resolve(".oseer/runs").resolve(runId).resolve("task-run.json");
        if(!Files.isRegularFile(file))return RunOverride.NONE;
        var summary=Json.parseObject(Files.readString(file));Object raw=summary.get("model_selection_override");
        if(!(raw instanceof Map<?,?> map) || !(map.get("profile") instanceof Map<?,?> pins))return RunOverride.NONE;
        var allowed=new LinkedHashMap<String,String>();
        for(var entry:pins.entrySet()){
            String stage=String.valueOf(entry.getKey()),name=String.valueOf(entry.getValue());
            var definition=user.policy().workflow().stages().stream().filter(s->s.name().equals(stage)).findFirst().orElse(null);
            if(definition==null || definition.role()==null)return RunOverride.NONE;
            var roster=user.policy().roles().get(definition.role());
            if(roster==null || !roster.profiles().contains(name))return RunOverride.NONE;
            allowed.put(stage,name);
        }
        return new RunOverride(allowed,Map.of(),Map.of());
    }
    public static RunOverride priorOverride(Path root, String runId) throws java.io.IOException {
        Map<String, Object> summary = Json.parseObject(Files.readString(root.resolve(".oseer/runs")
                .resolve(runId).resolve("task-run.json")));
        Object raw = summary.get("run_override");
        if (raw == null) return RunOverride.NONE;
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("invalid prior run override");
        Map<String, Object> value = new LinkedHashMap<>();
        map.forEach((key, item) -> value.put(String.valueOf(key), item));
        return RunOverride.fromMap(value);
    }

    /** Leave an overlay for the run {@code runId} will be, before its process exists. */
    public static void handOver(Path root, String runId, RunOverride overlay)
            throws java.io.IOException {
        if (overlay == null || overlay.isEmpty()) return;
        Path directory = root.resolve(".oseer/runs").resolve(runId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(dev.oseer.ledger.EvidenceLedger.RUN_OVERRIDE),
                Json.writePretty(overlay.toMap()) + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    public static boolean roleFailureRequiresOperator(String code) {
        return RunFailurePolicy.roleFailureRequiresOperator(code);
    }


    public static RoleRunner.DispatchGate amendmentGate(Path root, String runId, TaskSpec.Budget ceilings,
                                                        java.util.function.LongSupplier nanos) {
        return RunBudget.amendmentGate(root, runId, ceilings, nanos);
    }

    static RoleRunner.DispatchGate preparationGate(TaskSpec.Budget ceilings) {
        return RunBudget.preparationGate(ceilings);
    }


    public static String reasonFor(RoleRunner.Outcome step, String genericReason) {
        return RunFailurePolicy.reasonFor(step, genericReason);
    }

    static String vendorCallFailed(Map<String, Object> summary) {
        return SafeNextStep.vendorCallFailed(summary);
    }

    /**
     * Why a reading is `unproven`, with what would fix it. The usual cause is a run that
     * started on a tree that already differed from the diff base (an existing PR branch):
     * this run's own writers are known, the starting tree's are not (#46).
     */
    public static String unprovenReason(Map<String, Object> summary) {
        String provenance = String.valueOf(summary.get("writer_provenance"));
        String these = summary.get("writer_vendors") instanceof List<?> vendors && !vendors.isEmpty()
                ? "; this run's writers were " + String.join(", ", vendors.stream().map(String::valueOf).toList())
                : "";
        if ("unknown_preexisting_candidate".equals(provenance)) {
            return " (the tree already differed from the diff base when the run started, and nobody "
                    + "recorded who wrote that part" + these + ". If you know its authors, name their "
                    + "vendors in the task's prior_writers and start a new run)";
        }
        if ("unknown_tree_moved".equals(provenance)) {
            return " (the tree changed outside Overseer since the run this one continues" + these + ")";
        }
        return " (who wrote the candidate is not recorded, so no reading can be called independent)";
    }


    /**
     * Concludes a run whose controller is being stopped by a signal. The stages that finished
     * are written with their receipts and a failure decision is opened, so {@code --continue}
     * can keep what was already judged instead of refusing an unknown run.
     */
    private void recordInterruption(EvidenceLedger ledger, Map<String, Object> summary,
                                    List<Map<String, Object>> steps, int attempt, RunBudget budget) {
        List<Map<String, Object>> finished = null;
        for (int tries = 0; finished == null && tries < 5; tries++) {
            try {
                finished = new ArrayList<>(steps);
            } catch (java.util.ConcurrentModificationException moving) {
                Thread.onSpinWait();
            }
        }
        if (finished == null) finished = List.of();
        summary.put("interrupted", Map.of(
                "at", java.time.Instant.now().toString(),
                "detail", "the controller was stopped by a signal while a stage was running; "
                        + "stages that had finished keep their receipts"));
        try {
            stop(ledger, summary, CONTROLLER_INTERRUPTED, finished, attempt, budget);
        } catch (Exception failed) {
            throw new IllegalStateException(failed.getMessage(), failed);
        }
    }

    /**
     * A run that gave up owes the same accounting as one that finished. Without it the
     * summary of a failure said what went wrong but not what it cost to find out, which is
     * the number an operator needs before deciding to run it again.
     */
    private Outcome stop(EvidenceLedger ledger, Map<String, Object> summary, String reason,
                         List<Map<String, Object>> steps, int attempt, RunBudget budget) throws Exception {
        summary.put("ok", false);
        summary.put("reason", reason);
        summary.put("next_action", "human_escalation");
        summary.put("attempts_used", (long) attempt);
        summary.put("total_cost_usd", budget.spent());
        summary.put("role_runs", (long) budget.runs());
        summary.put("unpriced_calls", (long) budget.unpriced());
        if (budget.unpricedCharge() > 0) {
            summary.put("cost_cap_unpriced_charge_usd", budget.unpricedCharge());
        }
        summary.put("cost_ceiling_binding", budget.chainUnpriced() == 0);
        RunChain.describe(summary, budget, String.valueOf(summary.get("run_id")),
                null, attempt);
        summary.put("steps", steps);
        // A stop is where the three questions are most often confused, so it is where they are
        // most worth separating: a candidate that passed review can sit inside a run that
        // stopped, and saying only that the run stopped throws that away.
        RunCompletion.describeCompletion(summary);
        RunFailurePolicy.describeInfrastructureStop(reason, summary, steps);
        summary.put("safe_next_step", SafeNextStep.of(reason, summary));
        summary.put("next_step", NextStep.of(reason, summary, ledger.projectRoot()));
        Map<String, Object> proposal = ContractProposal.prepare(ledger.projectRoot(), summary);
        if (!proposal.isEmpty()) summary.put("contract_proposal", proposal);
        Path file = ledger.writeReport("task-run", summary);
        Path root = ledger.projectRoot();
        Object base = summary.get("diff_base_commit");
        String fingerprint = base instanceof String commit
                ? new GitRepository(root, processes).sourceFingerprint(commit) : null;
        // A named successor — a spent subscription, or a vendor call that produced no
        // artifact when the policy allows a switch — is a different question from a
        // failure of the work. What is being asked is who may finish the role.
        Map<String, Object> pending = StepQueries.pendingFailover(steps);
        ApprovalStore store = new ApprovalStore(root);
        String runIdentity = String.valueOf(summary.get("run_id"));
        String taskIdentity = String.valueOf(summary.get("task_id"));
        HumanDecision decision;
        if (pending != null) {
            summary.put("failover_pending", pending);
            decision = store.createFailover(runIdentity, taskIdentity,
                    StepQueries.failoverQuestion(pending), file, fingerprint);
        } else if (!proposal.isEmpty()) {
            decision = store.createPending(runIdentity, taskIdentity, HumanDecision.Kind.CONTRACT_CHANGE,
                    ContractProposal.question(proposal), file, fingerprint);
        } else {
            decision = store.createFailure(runIdentity, taskIdentity, reason, file, fingerprint);
        }
        DecisionRecord.addDecision(summary, root, decision);
        DecisionRecord.publishGate(orcaGate, processes, summary, root, decision, String.valueOf(summary.get("task_id")));
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", decision.runId(), "kind", decision.kind().jsonValue(),
                "path", root.relativize(new ApprovalStore(root).decisionPath(decision.runId()))
                        .toString().replace('\\', '/')));
        ledger.append("task_run", summary);
        ledger.recordCorpusVisibility(summary);
        ledger.writeReport("task-run", summary);
        RunNarration.footer(progress, workspace, root, runIdentity, reason, "human_escalation", budget, summary);
        return new Outcome(false, reason, "human_escalation", file, summary);
    }


}
