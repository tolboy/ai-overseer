package dev.oseer.run;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.Policy;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.config.Workflow;
import dev.oseer.gate.GateRunner;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.json.Json;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.ledger.Findings;
import dev.oseer.role.RoleContract;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;
import dev.oseer.run.TaskLoop.Continuation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.oseer.run.WorkflowAdmission.judgesCandidate;

/**
 * Walks the declared chain. Every routing decision here is a counter, an exit code or a
 * declared condition; nothing asks a model what to do next, and nothing lands anything.
 */
final class WorkflowEngine {
    private final WorkflowPlan plan;
    private final CandidateBaseline baseline;
    private final WorkflowEvidence evidence;
    private final RunBudget budget;
    private final RoleRunner roles;
    private final WorkflowChecks checks;
    private final StageProgress progress;
    private final CandidateWriters candidateWriters;
    private final WriterContinuation writerContinuation;

    private final ConfigLoader.Loaded loaded;
    private final UserConfig user;
    private final TaskSpec.ResolvedTask task;
    private final Workflow workflow;
    private final boolean dryRun;
    private final CallPlan callPlan;
    private final EvidenceLedger ledger;
    private final String runId;
    private final List<Map<String, Object>> steps;
    private final Map<String, Object> summary;
    private final Continuation carried;

    /**
     * Readings of this task's chain that objected with blocking findings, and the rung
     * of the escalation ladder the chain stands on. Both are inherited by a continuation
     * and climbed forward only: a rung is never revisited, and a reading that objected on
     * an earlier run still counts towards the next rung.
     */
    private long blockingReviewsSeen;
    private int rung;
    /** Why the inherited rung could not be re-pinned, checked before the first stage runs. */
    private String escalationProblem;
    private final List<Map<String, Object>> skipped = new ArrayList<>();
    /**
     * Stages that finished, by name.
     *
     * "Every stage that ran passed" was a sentence assembled from the step rows, and step
     * rows are labelled by role: two review stages wrote the same label, and a chain that
     * reached only the first of them read afterwards as if review were done. Names are
     * unique in a workflow, so this is the one identity that cannot collapse.
     */
    private final java.util.Set<String> completed = new java.util.LinkedHashSet<>();
    /**
     * Judging stages that had passed and whose candidate a later repair then changed.
     *
     * Kept apart from "never ran": both are outstanding, and they send a reader to
     * different places. One has a verdict on disk about a tree that no longer exists.
     */
    private final java.util.Set<String> staleAfterFix = new java.util.LinkedHashSet<>();
    /**
     * Stages whose earlier verdict this run actually consumed, in the order it did.
     *
     * Recorded here rather than at pool build, because a stage that looked reusable when
     * the pool was assembled can be invalidated before its turn — an ordinary implementer
     * dispatch earlier in this same resume moves the tree out from under a later review.
     * `reused_judgements` names what was consumed, so it is written as consumption happens.
     */
    private final java.util.Set<String> reusedStages = new java.util.LinkedHashSet<>();
    /** Every judging round this run performed, per stage, with what it objected to. */
    private final List<Map<String, Object>> findingHistory = new ArrayList<>();
    /** What the most recent repair reported doing, and whether the tree agrees. */
    private Map<String, Object> lastRepair;
    /** Per judging stage: whether it ran, whether it passed, and what it still objects to. */
    private final Map<String, Map<String, Object>> coverage = new LinkedHashMap<>();
    private int attempt;
    WorkflowEngine(WorkflowPlan plan, CandidateBaseline baseline, WorkflowEvidence evidence,
                   RunBudget budget, RoleRunner roles, WorkflowChecks checks, StageProgress progress)
            throws Exception {
        this.plan = plan;
        this.baseline = baseline;
        this.evidence = evidence;
        this.budget = budget;
        this.roles = roles;
        this.checks = checks;
        this.progress = progress;
        this.loaded = plan.loaded();
        this.user = plan.user();
        this.task = plan.task();
        this.workflow = plan.workflow();
        this.dryRun = plan.dryRun();
        this.callPlan = plan.callPlan();
        this.ledger = evidence.ledger();
        this.runId = evidence.runId();
        this.steps = evidence.steps();
        this.summary = evidence.summary();
        this.carried = evidence.continuation();
        this.candidateWriters = new CandidateWriters(loaded.root(), runId, task,
                baseline.diffBaseCommit(), summary);
        this.writerContinuation = new WriterContinuation(evidence, baseline);
        restoreFindingHistory();
        candidateWriters.restore(carried, baseline::hasSourceChanges);
        this.blockingReviewsSeen = budget.inherited().blockingReviewsSeen();
        this.rung = (int) budget.inherited().escalationRung();
        restoreEscalation();
        roles.occupying(progress::named);
    }

    /**
     * Re-pin the rung a continued chain already climbed to. The pins live in the runner,
     * which is new every run, so the summary carries which stage was pinned and the chain
     * carries the rung; both have to agree with the policy in force now, or the run stops
     * before it spends anything on a rung nobody can fill.
     */
    private void restoreEscalation() {
        if (rung <= 0 || !carried.continuesRun()) return;
        Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
        if (ladder == null || rung > ladder.rungs().size()) {
            escalationProblem = "the chain stands on escalation rung " + rung
                    + " but the policy now declares "
                    + (ladder == null ? "no ladder" : ladder.rungs().size() + " rung(s)");
            return;
        }
        String atStage = null;
        try {
            Path file = loaded.root().resolve(".oseer/runs").resolve(carried.fromRunId())
                    .resolve("task-run.json");
            Map<String, Object> prior = Json.parseObject(Files.readString(file));
            if (prior.get("escalation") instanceof Map<?, ?> state
                    && state.get("at_stage") instanceof String stage) {
                atStage = stage;
            }
        } catch (Exception unreadable) {
            atStage = null;
        }
        Policy.Escalation.Rung current = ladder.rungs().get(rung - 1);
        pinRung(ladder, current, atStage, "inherited_from_chain");
    }

    /** Pin one rung's pair and record the ladder's state on the summary. */
    private void pinRung(Policy.Escalation ladder, Policy.Escalation.Rung current,
                         String atStage, String reason) {
        roles.assignRole("implementer", current.implementer());
        if (atStage != null) roles.assignStage(atStage, current.reviewer());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("rungs_declared", (long) ladder.rungs().size());
        state.put("after_blocking_reviews", ladder.afterBlockingReviews());
        state.put("blocking_reviews_seen", blockingReviewsSeen);
        state.put("rung", (long) rung);
        state.put("quality_attempts", (long) rung);
        state.put("implementer", current.implementer());
        state.put("reviewer", current.reviewer());
        state.put("at_stage", atStage);
        state.put("fix_round", (long) attempt);
        state.put("reason", reason);
        summary.put("escalation", state);
    }

    /**
     * Climb, or refuse to. Called at the moment a reading has objected and a repair is
     * about to be paid for; nothing here dispatches.
     *
     * Below the threshold the same writer repairs, as it always did. At or above it the
     * next rung's writer takes the repair and its reader re-reads the objecting stage,
     * both pinned for the rest of the chain. A rung whose pair cannot be filled now stops
     * before the repair rather than after it. A chain that has climbed every rung and is
     * still objected to has exhausted the quality it was allowed to buy.
     */
    private void maybeEscalate(Workflow.Stage stage, int index) {
        Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
        if (ladder == null) return;
        if (rung >= ladder.rungs().size() && rung > 0) throw qualityExhausted(stage, ladder);
        if (blockingReviewsSeen < ladder.afterBlockingReviews()) return;
        rung++;
        Policy.Escalation.Rung next = ladder.rungs().get(rung - 1);
        pinRung(ladder, next, stage.name(), "blocking_reviews_reached_threshold");
        boolean writerReady = roles.canFill(user, stage.name() + "/fix", stage.fixWith(),
                RoleResolver.Writers.NONE, RoleRunner.UNSTAGED);
        boolean readerReady = roles.canFill(user, stage.name(), stage.role(), candidateWriters.readersAvoid(),
                workflow.rotationPositionOf(stage));
        if (!writerReady || !readerReady) {
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("rung", (long) rung);
            gap.put("implementer", next.implementer());
            gap.put("reviewer", next.reviewer());
            gap.put("implementer_fillable", writerReady);
            gap.put("reviewer_fillable", readerReady);
            gap.put("exhausted_profiles", List.copyOf(roles.exhaustedProfiles()));
            summary.put("unavailable_role", gap);
            summary.put("resolution", "The escalation ladder's rung " + rung + " names "
                    + next.label() + ", and " + (writerReady ? "its reviewer" : "its implementer")
                    + " cannot be dispatched now (missing, unverified, not on the path, or "
                    + "spent). Verify or replace that profile, then continue this run: the "
                    + "verdicts already reached on this tree are kept.");
            progress.line("      escalation rung " + rung + " (" + next.label()
                    + ") cannot be filled; stopping before the repair");
            throw new StopException("escalation_unavailable");
        }
        progress.line("      -> escalation rung " + rung + " of " + ladder.rungs().size()
                + " after " + blockingReviewsSeen + " blocking reading(s): "
                + next.implementer() + " repairs, " + next.reviewer() + " re-reads "
                + stage.name() + " as a co-author");
        progress.note("escalation " + rung + "/" + ladder.rungs().size() + " · "
                + next.implementer() + " writes, " + next.reviewer() + " reads " + stage.name());
    }

    private StopException qualityExhausted(Workflow.Stage stage, Policy.Escalation ladder) {
        summary.put("resolution", "Every rung of the escalation ladder (" + ladder.rungs().size()
                + ") has written and been read, and '" + stage.name() + "' still objects. "
                + "No further writer is declared, so the open findings go to a person: read "
                + "them, change the task or the finding's premise, and start a new run.");
        noteNonactionableStop(stage);
        progress.line("      the last rung of the ladder still objects; nothing further is "
                + "allowed to write");
        return new StopException("quality_exhausted");
    }

    private boolean ladderExhausted() {
        Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
        return ladder != null && rung > 0 && rung >= ladder.rungs().size();
    }

    /** Keep lifecycle memory on an authorised continuation, even when content needs re-review. */
    private void restoreFindingHistory() throws Exception {
        if (!carried.continuesRun()) return;
        Path file = loaded.root().resolve(".oseer/runs").resolve(carried.fromRunId()).resolve("task-run.json");
        if (!Files.isRegularFile(file)) return;
        Map<String, Object> prior = Json.parseObject(Files.readString(file));
        if (!task.id().equals(prior.get("task_id"))) return;
        if (!java.util.Objects.equals(summary.get("contract_sha256"), prior.get("contract_sha256"))
                && !(summary.get("acceptance_sha256") instanceof String hash
                && hash.equals(prior.get("acceptance_sha256")))) return;
        if (prior.get("finding_history") instanceof List<?> rows) for (Object item : rows) {
            if (!(item instanceof Map<?, ?> entry) || !(entry.get("stage") instanceof String stage)) continue;
            Map<String, Object> rebuilt = findingRound(stage,
                    entry.get("attempt") instanceof Number n ? n.longValue() : 0,
                    entry.get("candidate_fingerprint") instanceof String fp ? fp : null,
                    Findings.recorded(entry.get("findings")),
                    Boolean.TRUE.equals(entry.get("finding_ids_scoped_to_stage")));
            rebuilt.put("source_run", entry.get("source_run") instanceof String source
                    ? source : carried.fromRunId());
            findingHistory.add(rebuilt);
        }
        if (!findingHistory.isEmpty()) summary.put("finding_history", List.copyOf(findingHistory));
    }

    private Map<String, Object> findingRound(String stage, long atAttempt, String fingerprint,
                                            List<Findings.Finding> findings, boolean idsScoped) {
        if (!task.reviewOnly()) {
            return Findings.roundOf(stage, atAttempt, fingerprint, findings, List.copyOf(findingHistory));
        }
        // Blind readers have separate finding and evidence lifecycles. The scoped IDs
        // still let the report merge and address every stage's findings unambiguously.
        List<Map<String, Object>> stageHistory = findingHistory.stream()
                .filter(round -> stage.equals(round.get("stage"))).toList();
        List<Findings.Finding> scoped = idsScoped ? findings : findings.stream()
                .map(finding -> scopeReviewFinding(stage, finding)).toList();
        Map<String, Object> round = Findings.roundOf(stage, atAttempt, fingerprint, scoped, stageHistory);
        round.put("finding_ids_scoped_to_stage", true);
        return round;
    }

    private static Findings.Finding scopeReviewFinding(String stage, Findings.Finding finding) {
        Map<String, Object> raw = new LinkedHashMap<>(finding.raw());
        raw.put("reviewer_finding_id", finding.id());
        if (raw.get("supersedes") instanceof List<?> links) {
            raw.put("supersedes", links.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(id -> !id.isBlank()).map(id -> reviewFindingId(stage, id.trim())).toList());
        }
        return new Findings.Finding(reviewFindingId(stage, finding.id()), finding.idSource(),
                finding.category(), finding.categorySource(), finding.severity(), finding.path(),
                finding.message(), raw);
    }

    private static String reviewFindingId(String stage, String id) {
        byte[] key = Json.write(List.of(stage, id)).getBytes(StandardCharsets.UTF_8);
        return "review-" + java.util.UUID.nameUUIDFromBytes(key);
    }

    int attempt() { return attempt; }

    /**
     * Fix rounds the whole chain has used, this run's included. `attempt` stays per run:
     * it names evidence directories and gates verdict reuse to the first pass.
     */
    long fixRoundsUsed() { return budget.inherited().fixAttempts() + attempt; }

    List<Map<String, Object>> skipped() { return List.copyOf(skipped); }

    void run() throws Exception {
        if (escalationProblem != null) {
            summary.put("resolution", escalationProblem + ". Restore the ladder in "
                    + "policy.yaml or start a new run without --continue.");
            throw new StopException("escalation_unavailable");
        }
        List<Workflow.Stage> stages = workflow.stages();
        for (int index = 0; index < stages.size(); index++) {
            Workflow.Stage stage = stages.get(index);
            String reason = plan.skipReason(stage);
            if (reason != null) {
                progress.line("[" + (index + 1) + "/" + stages.size() + "] "
                        + RunNarration.pad(stage.name(), 10) + "skipped: " + reason);
                skipped.add(Map.of("stage", stage.name(), "reason", reason));
                // Written as we go: a run that stops early still owes an answer to
                // "why did the reviewer never run", and that is the run that needs it.
                summary.put("skipped_stages", List.copyOf(skipped));
                continue;
            }
            runStage(stage, index);
        }
        // Review-only: every reader has had its turn, so the objections stop the run now,
        // together, instead of the first one hiding what the later readers would have said.
        if (deferredFindingsStop != null) throw new StopException(deferredFindingsStop);
    }

    /** Review-only: the first findings stop reason, held until every reader has run. */
    private String deferredFindingsStop;
    private final List<String> objectingStages = new ArrayList<>();

    private void runStage(Workflow.Stage stage, int index) throws Exception {
        Object outcome = execute(stage);
        if (dryRun) return;
        // A role whose verdict was carried over from an earlier run on this exact tree
        // dispatched nobody, so there is no outcome to route and nothing new to object.
        if (outcome == null) { finished(stage); return; }

        while (failed(outcome) && "fix".equals(stage.onFail()) && RunFailurePolicy.canRepair(stage, outcome)
                && fixRoundsUsed() < task.maxFixAttempts()) {
            fixRound(stage, index, failureContext(stage, outcome));
            outcome = execute(stage);
        }
        if (failed(outcome)) throw new StopException(terminalReason(stage, outcome));
        if (!judgesCandidate(stage, task)) {
            finished(stage);
            return;
        }

        // A role that succeeded can still object. Findings are the reason to run a second
        // vendor at all, so they route like any other failing check instead of being
        // printed for somebody to notice.
        RoleRunner.Outcome reviewed = (RoleRunner.Outcome) outcome;
        long blocking = recordFindings(stage, reviewed);
        while (repairableFindings(stage) > 0 && "fix".equals(stage.onFindings())
                && fixRoundsUsed() < task.maxFixAttempts()
                && (blockingRepairable(stage) > 0 || optionalRepairAffordable(stage, index))) {
            // Who repairs is decided here, before the repair is costed and paid for. Off
            // the ladder this is a no-op; on it, the next rung's pair is pinned or the
            // run stops for a reason that names the rung.
            maybeEscalate(stage, index);
            fixRound(stage, index, RepairBrief.reviewContext(loaded.root(), reviewed, repairSeverities(),
                    task.manualVerification(), task.acceptanceEvidence().recordsRealAcceptance()));
            outcome = execute(stage);
            if (failed(outcome)) throw new StopException(terminalReason(stage, outcome));
            reviewed = (RoleRunner.Outcome) outcome;
            blocking = recordFindings(stage, reviewed);
            // Laundering of a P1 is already refused inside recordFindings, including on
            // this stage's first reading. This stall detector is specifically about a
            // repair that changed nothing and brought the same blockers back.
            requireFindingsProgress(stage);
        }
        if (blocking > 0) {
            if (task.reviewOnly()) {
                objectingStages.add(stage.name());
                summary.put("objecting_stages", List.copyOf(objectingStages));
                if (deferredFindingsStop == null) deferredFindingsStop = stage.findingsReason();
                return;
            }
            if (ladderExhausted()) {
                throw qualityExhausted(stage, user.policy().escalation());
            }
            noteNonactionableStop(stage);
            throw new StopException(stage.findingsReason());
        }
        finished(stage);
    }

    /** A stage that reached the end of its own routing with nothing left outstanding. */
    private void finished(Workflow.Stage stage) {
        completed.add(stage.name());
        staleAfterFix.remove(stage.name());
        summary.put("completed_stages", List.copyOf(completed));
        summary.put("stages_judging_an_earlier_candidate", List.copyOf(staleAfterFix));
    }

    /**
     * A repair is about to change the candidate, so every verdict about the old one stops
     * counting as finished business.
     *
     * Marking them complete through a fix round was wrong in the direction that matters.
     * A reviewer that passed, was re-dispatched after a browser repair and came back with
     * a P1 correctly stopped the run — and the stage it had just failed was still listed
     * in `completed_stages`, absent from `pending_stages`, and therefore reported as a
     * stage that had passed. Reproduced on run `rk1`.
     *
     * The rechecked stages earn their place back by passing again. The ones the workflow
     * did not ask to recheck stay here, which is the honest answer: they judged a tree
     * that no longer exists, and `stale_judgements` has always said so separately without
     * anything acting on it.
     */
    private void candidateAboutToChange() {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE || stage.onFindings() == null) continue;
            if (!completed.remove(stage.name())) continue;
            staleAfterFix.add(stage.name());
            coverage.remove(stage.name());
        }
        summary.put("completed_stages", List.copyOf(completed));
        summary.put("stages_judging_an_earlier_candidate", List.copyOf(staleAfterFix));
        summary.put("review_coverage", List.copyOf(coverage.values()));
    }

    /**
     * One fix round: hand the exact failure back, then re-establish every earlier stage
     * that asked to be rechecked. Without the recheck a fix could satisfy the stage that
     * complained by breaking one that had already passed.
     */
    private void fixRound(Workflow.Stage stage, int index, String context) throws Exception {
        // Both halves of what a repair commits to, asked in cost order: the calls it needs
        // are arithmetic, the readers it needs cost a probe apiece.
        requireReserve(stage, index);
        requireJudgesRemain(stage, index);
        attempt++;
        // Before the dispatch, not after it. A fix that fails part way through has still
        // touched the tree, and the verdicts about the tree it started from are no more
        // current than if it had succeeded.
        candidateAboutToChange();
        progress.announceRepair(stage, attempt, fixRoundsUsed());
        String beforeRepair = baseline.fingerprint();
        Path file = RepairBrief.writeContext(ledger, attempt, stage.contextKind(),
                repairPackage(stage, context));
        String role = stage.fixWith();
        Workflow.Stage writerStage = writerStageOf(role);
        budget.dispatching(writerStage == null ? stage.name() + "/fix" : writerStage.name());
        RoleRunner.Outcome fix = progress.repair(stage, attempt,
                () -> roles.atStage(stage.name() + "/fix").run(loaded, user, role,
                        StageIds.stepRunId(runId, role, attempt), null, file, false));
        StepRecord.record(steps, budget, role, attempt, fix);
        // Which stage sent the work back and which round this was. A fix row used to be
        // indistinguishable from a stage row except by the absence of a stage, and a
        // continuation needs to know that this row is the writer's latest product.
        steps.get(steps.size() - 1).put("fix_for", stage.name());
        steps.get(steps.size() - 1).put("fix_round", (long) attempt);
        // Same stamp as an ordinary dispatch: the tree this repair produced is the tree a
        // later resume must match before it may reuse this implementer's work.
        stampFingerprint();
        // The routing of the stage this writer owns, so a continuation can compare the
        // row against the writer stage's current terms the way it compares a stage row.
        if (writerStage != null) stampRouting(writerStage);
        candidateWriters.recordWriterOutcome(fix);
        if (!fix.ok()) {
            requireDeadlineNotTheCause(fix);
            throw new StopException(RunFailurePolicy.reasonFor(fix, "fix_attempt_failed"));
        }
        recordRepairReceipt(fix, beforeRepair);
        baseline.requireUnchangedContract(loaded);
        for (Workflow.Stage earlier : workflow.recheckBefore(index)) {
            if (plan.skipReason(earlier) != null) continue;
            Object rechecked = execute(earlier);
            if (failed(rechecked)) {
                throw new StopException(earlier.kind() == Workflow.Kind.MACHINE_GATES
                        ? "gates_not_satisfied_after_fix" : terminalReason(earlier, rechecked));
            }
            // A recheck asks whether the fix broke a verdict that had already been given,
            // and until now it only ever heard the half of that answer a process exit code
            // can carry. A reviewer that read the new diff, objected to it in a P1 and
            // returned cleanly was recorded as a passing recheck — the run paid for the
            // objection and then discarded it, and the human gate went on to say every
            // stage that ran had passed. Findings are the whole reason this stage is
            // re-dispatched; not reading them made the recheck a paid formality.
            if (earlier.kind() == Workflow.Kind.ROLE && earlier.onFindings() != null
                    && rechecked instanceof RoleRunner.Outcome verdict
                    && recordFindings(earlier, verdict) > 0) {
                // Deliberately a stop rather than another repair. This objection was found
                // from inside a repair round already under way for a different stage, and
                // repairing from inside a repair is how a bounded loop stops being one.
                // The findings are on disk and the stop names them.
                progress.line("      the fix broke a verdict " + earlier.name()
                        + " had already given; stopping rather than repairing recursively");
                throw new StopException(earlier.findingsReason());
            }
            // It has re-established itself against the changed candidate, so it earns back
            // the completion the fix took away. Without this, a recheck that passed left
            // its stage listed as outstanding for the rest of the run — the mirror of the
            // bug that let a failed recheck stay listed as passed.
            finished(earlier);
        }
    }

    /**
     * Everything a repair needs, in the order it needs it, and nothing it has to go and
     * find.
     *
     * The failure text on its own was a fix round's whole briefing, which made every round
     * after the first strictly worse informed than the first: an implementer was handed an
     * objection with no memory of what it had already closed, no statement of the goal it
     * was still working towards, and no idea how much room was left. So it re-read
     * everything, sometimes undid a closed finding, and could not tell a request it should
     * refuse from one it should act on.
     *
     * The goal is quoted from the contract rather than summarised, because a goal restated
     * by a model is a goal that drifts. The budget is stated so a repair knows whether it
     * is the last one. What the round already closed is stated so it stays closed.
     */
    private String repairPackage(Workflow.Stage stage, String failure) {
        StringBuilder out = new StringBuilder();
        out.append("# The goal, unchanged\n\n").append(task.goal()).append("\n\n");
        if (!task.nonGoals().isEmpty()) {
            out.append("Explicitly not part of it:\n");
            for (String no : task.nonGoals()) out.append("- ").append(no).append('\n');
            out.append('\n');
        }

        Map<String, Object> last = lastFindingRound(stage.name());
        List<String> alreadyClosed = last == null ? List.of() : stringsOf(last.get("closed_ids"));
        if (!alreadyClosed.isEmpty()) {
            out.append("# Already closed by an earlier round — keep them closed\n\n");
            for (String id : alreadyClosed) out.append("- ").append(id).append('\n');
            out.append(Json.writePretty(last.get("finding_registry"))).append("\n");
            out.append("\nA repair that reopens one of these has not made progress; it has "
                    + "traded one finding for another.\n\n");
        }
        if (lastRepair != null) {
            out.append("# What the previous repair reported doing\n\n")
                    .append("- summary: ").append(lastRepair.get("summary")).append('\n');
            List<String> touched = stringsOf(lastRepair.get("files_changed"));
            if (!touched.isEmpty()) {
                out.append("- files it said it changed: ")
                        .append(String.join(", ", touched)).append('\n');
            }
            out.append("- the candidate ")
                    .append(Boolean.TRUE.equals(lastRepair.get("moved_candidate"))
                            ? "did change as a result" : "did not change at all")
                    .append("\n\n");
        }

        out.append(failure);

        out.append("\n# What you may not do\n\n")
                .append("- Files outside ").append(String.join(", ", task.scopePaths()))
                .append(" are outside this task's blast radius and a change to one fails the run.\n")
                .append("- The `.oseer` contract is hashed. Editing the task, the acceptance "
                        + "commands or the scenarios to make a check agree aborts the run.\n")
                .append("- Authority for this task: workspace_write=")
                .append(task.authority().workspaceWrite())
                .append(", network=").append(task.authority().network())
                .append(", land=").append(task.authority().land()).append(".\n");

        int remaining = budget.remaining();
        out.append("\n# What is left\n\n")
                .append("- This is fix round ").append(attempt).append(" of ")
                .append(task.maxFixAttempts()).append(".\n");
        if (remaining != Integer.MAX_VALUE) {
            out.append("- Vendor calls remaining after this one: ")
                    .append(Math.max(0, remaining - 1)).append(".\n");
        }
        out.append("- Nothing is landed by this loop; a person decides at the end.\n");
        return out.toString();
    }

    /**
     * What a judging stage is told so it can check closure rather than start again.
     *
     * Two memories, kept distinct. A stage that has already read this candidate is asked
     * whether its own findings closed, and is allowed a narrowed reading while the tree
     * is the tree it read. A later stage's first reading is independent — its verdict is
     * its own — but it still receives the registry and the repair receipt, with the stage
     * that filed each finding preserved, so a closed requirement cannot re-enter as a
     * new one on the original evidence.
     *
     * A reviewer given no memory re-investigates from scratch every round, which costs a
     * full reading each time and produces findings that drift — the same defect described
     * differently, or a new objection to code nobody touched. It is also the reason a
     * second round could not be compared to the first.
     */
    private String reviewerPackage(Workflow.Stage stage) {
        // A review-only task asks each reader for its own reading of a branch nobody here
        // will repair: there is no closure to check and no repair receipt. Handing a later
        // reader the earlier readers' registry made three "independent" readings of the
        // first outside pull request repeat the first reader's finding ids and wording.
        if (task.reviewOnly()) return null;
        Map<String, Object> own = lastFindingRound(stage.name());
        Map<String, Object> last = own != null ? own : lastInheritedFindingRound(stage.name());
        if (last == null) return null;
        boolean firstReadingOfThisStage = own == null;
        StringBuilder out = new StringBuilder();
        if (firstReadingOfThisStage) {
            out.append("# A previous stage has already read this candidate\n\n");
            out.append("This is your first reading of this task. Another reviewer already "
                    + "read this candidate");
            if (lastRepair != null) {
                out.append(" and an implementer has since been given what they filed");
            }
            out.append(". Their verdict is not yours — read the code. The registry below is "
                    + "so you can reuse ids, check closures, and not raise a closed "
                    + "requirement as a new one without new evidence.\n\n");
        } else {
            out.append("# You have already read this candidate\n\n");
            out.append("This is not a fresh review. You looked at this task on an earlier round "
                    + "and filed the findings below");
            if (lastRepair != null) {
                out.append("; an implementer has since been given them.\n\n");
            } else {
                // A resumed run reaches this branch with its reading restored and no repair
                // behind it: `lastRepair` belongs to this run, and a restore does not carry
                // one. Claiming a repair briefed a paid reviewer on something untrue, on the
                // one round where dropping a finding is refused precisely because the tree
                // did not move.
                out.append(". No repair has run since. This is a resumed run and the reading "
                        + "below was restored from the run before it; the candidate itself ")
                        .append(movedSince(last) ? "has changed since that reading."
                                : "**has not changed at all** since that reading.")
                        .append("\n\n");
            }
        }

        out.append("## Cumulative finding registry (including closed findings)\n\n")
                .append(Json.writePretty(Findings.registryOf(findingHistory))).append("\n\n")
                .append("Reuse IDs. Link renamed findings with supersedes. Reopening or a new "
                        + "P1 after a closure requires new evidence_refs; bare verdicts stop the run.\n\n");
        out.append(firstReadingOfThisStage
                ? "## What they filed\n\n" : "## What you filed last time\n\n");
        Object listed = firstReadingOfThisStage
                ? Findings.registryOf(findingHistory) : last.get("findings");
        if (listed instanceof List<?> rows && !rows.isEmpty()) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> finding)) continue;
                out.append("- `").append(finding.get("id")).append("` (")
                        .append(finding.get("severity")).append(", ")
                        .append(finding.get("category"));
                if (finding.get("status") instanceof String status && !status.isBlank()) {
                    out.append(", ").append(status);
                }
                out.append(") ").append(finding.get("path")).append(" — ")
                        .append(finding.get("message"));
                if (finding.get("recorded_at_stage") instanceof String filed
                        && !filed.isBlank()) {
                    out.append(" [stage ").append(filed).append(']');
                }
                out.append('\n');
            }
        } else {
            out.append("- (nothing)\n");
        }

        if (lastRepair != null) {
            out.append("\n## What the implementer says it did\n\n")
                    .append(lastRepair.get("summary")).append('\n');
            List<String> touched = stringsOf(lastRepair.get("files_changed"));
            if (!touched.isEmpty()) {
                out.append("\nFiles it reports changing: ")
                        .append(String.join(", ", touched)).append('\n');
            }
            out.append("\nThe candidate itself ")
                    .append(Boolean.TRUE.equals(lastRepair.get("moved_candidate"))
                            ? "did change since " + (firstReadingOfThisStage
                                    ? "the last reading." : "your last reading.")
                            : "**did not change at all** since "
                                    + (firstReadingOfThisStage
                                            ? "the last reading." : "your last reading."))
                    .append('\n');
        }

        if (firstReadingOfThisStage) {
            out.append("""

                    ## What is being asked of you now

                    Your verdict is independent of theirs. Two extra obligations come with
                    the registry: a finding they closed stays closed unless you have new
                    evidence, and a new P1 after a closure needs new evidence too. A
                    regression is a new finding, not a footnote.

                    Reuse the finding ids above for the same defect, so the run can tell a
                    surviving objection from a fresh one. Give a new id only to something
                    genuinely new.

                    Do not lower a severity to let the run pass, and do not drop a P1 you
                    filed to let it pass either. If a P1 was wrong, say it was wrong and why;
                    a P1 that quietly becomes a P2, or vanishes, on a candidate that did not
                    change is refused by the controller, not believed.
                    """);
        } else {
            out.append("""

                    ## What is being asked of you now

                    Two questions, in this order. Is each finding above actually closed, judged
                    against the code rather than against the implementer's account of it? And did
                    the repair break something that was working — a regression is a new finding,
                    not a footnote.

                    Reuse the finding ids above for anything still open, so the run can tell a
                    surviving objection from a fresh one. Give a new id only to something genuinely
                    new.

                    Do not lower a severity to let the run pass, and do not drop a P1 you
                    filed to let it pass either. If a P1 was wrong, say it was wrong and why;
                    a P1 that quietly becomes a P2, or vanishes, on a candidate that did not
                    change is refused by the controller, not believed.

                    You do not have to repeat the whole investigation to answer those two
                    questions. You do have to repeat it if the scope, the acceptance commands or
                    the contract have changed since your last reading, or if your earlier verdict
                    rested on an observation that can go stale — a live page, a deployment, a
                    network call. Say which of those applies in your summary.
                    """);
        }
        return out.toString();
    }

    private static List<String> stringsOf(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof List<?> rows) {
            for (Object row : rows) items.add(String.valueOf(row));
        }
        return items;
    }

    /**
     * Refuse a pass that was bought by relabelling or by dropping a finding, rather than
     * by fixing.
     *
     * A reviewer can end a run by calling its own P1 a P2, or by omitting it. On a
     * candidate that changed, that may be an honest reconsideration in the light of a
     * repair. On a candidate that did not change, nothing new was learned about the code,
     * and the only thing that moved is the report standing between the run and the human
     * gate.
     *
     * The controller does not overrule the reviewer's judgement of the defect — it refuses
     * to treat the relabelling or the retraction as a pass, even when another blocker is
     * still open in the same round, and even on a stage's first reading of a resumed run.
     * Restoring the history on `--continue` is what makes that comparison possible; a
     * first reading that then drops or relabels a P1 on an unchanged candidate is the
     * same pass-by-rewriting-the-report as one that follows a no-op repair. A throwaway
     * P1 kept alive for one reading is not a reason to let the original drop through. A
     * reviewer that believes the P1 was wrong should say so and why, which reads as a
     * `review_disagreement` a person can adjudicate.
     */
    private void requireNoSeverityLaundering(Workflow.Stage stage) {
        Map<String, Object> now = lastFindingRound(stage.name());
        if (now == null || !Boolean.FALSE.equals(now.get("candidate_moved"))) return;
        List<String> weakened = List.copyOf(stringsOf(now.get("severity_downgraded")));
        List<String> dropped = List.copyOf(stringsOf(now.get("blocking_retracted")));
        if (weakened.isEmpty() && dropped.isEmpty()) return;
        Map<String, Object> laundered = new LinkedHashMap<>();
        laundered.put("at_stage", stage.name());
        laundered.put("downgraded_ids", weakened);
        laundered.put("retracted_ids", dropped);
        laundered.put("candidate_fingerprint", now.get("candidate_fingerprint"));
        summary.put("severity_downgraded_without_change", laundered);
        String how = dropped.isEmpty() ? "came back at a lower severity"
                : weakened.isEmpty() ? "vanished from the report"
                : "came back at a lower severity or vanished from the report";
        summary.put("resolution", "'" + stage.name() + "' stopped blocking only because a "
                + "finding it had called P1 " + how + ", on a candidate "
                + "that did not change. Nothing new was learned about the code between the "
                + "two readings. If the P1 was wrong, that is a disagreement for a person to "
                + "settle on the evidence, not a pass.");
        progress.line("      a P1 became non-blocking on an unchanged candidate; not "
                + "treating a dropped or relabelled finding as a fixed one");
        throw new StopException("severity_downgraded_without_change");
    }

    /**
     * P1s the implementer can be expected to close by editing the candidate. A
     * contract_gap, access grant, quota or disagreement still blocks acceptance, but
     * handing the diff back will not close it.
     */
    /**
     * Product defects this stage's last reading left that the policy sends back to the
     * implementer: P1 always, P2 and P3 when `review.repair_severities` names them.
     */
    private long repairableFindings(Workflow.Stage stage) {
        return productDefects(stage, repairSeverities());
    }

    /** The P1 product defects: the ones that stop the loop if they are not repaired. */
    private long blockingRepairable(Workflow.Stage stage) {
        return productDefects(stage, java.util.Set.of("P1"));
    }

    private long productDefects(Workflow.Stage stage, java.util.Set<String> severities) {
        Map<String, Object> now = lastFindingRound(stage.name());
        if (now == null || !(now.get("findings") instanceof List<?> rows)) return 0;
        long n = 0;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> finding)) continue;
            // A citation the tree does not support is not something to send back for repair.
            if (Boolean.TRUE.equals(finding.get("unverified"))) continue;
            if (severities.contains(String.valueOf(finding.get("severity")))
                    && "product_defect".equals(String.valueOf(finding.get("category")))) {
                n++;
            }
        }
        return n;
    }

    private java.util.Set<String> repairSeverities() {
        return user.policy() == null ? Policy.DEFAULT_REPAIR_SEVERITIES : user.policy().repairSeverities();
    }

    /**
     * Whether a repair that only P2/P3 findings ask for can be paid and judged.
     *
     * A P1 repair that cannot be afforded stops the run, because the candidate cannot be
     * accepted with it open. A P2 or P3 is not a reason to stop: the candidate goes to the
     * person with the finding listed, and the reason the repair was skipped is recorded.
     */
    private boolean optionalRepairAffordable(Workflow.Stage stage, int index) {
        String mode = user.policy() == null ? "full" : user.policy().repairReserve();
        String skipped = null;
        if (budget.remaining() < callPlan.requiredFor(index, mode) || !budget.hasRoom()) {
            skipped = "budget";
        } else {
            Object priorGap = summary.get("unavailable_role");
            Object priorResolution = summary.get("resolution");
            try {
                requireJudgesRemain(stage, index);
            } catch (StopException noJudge) {
                if (priorGap == null) summary.remove("unavailable_role");
                else summary.put("unavailable_role", priorGap);
                if (priorResolution == null) summary.remove("resolution");
                else summary.put("resolution", priorResolution);
                skipped = "no_independent_judge";
            }
        }
        if (skipped == null) return true;
        @SuppressWarnings("unchecked")
        List<Object> notes = summary.get("optional_repairs_skipped") instanceof List<?> list
                ? new ArrayList<>((List<Object>) list) : new ArrayList<>();
        notes.add(Map.of("stage", stage.name(), "reason", skipped));
        summary.put("optional_repairs_skipped", notes);
        progress.line("      the " + stage.name() + " findings left open are P2/P3 and the "
                + ("budget".equals(skipped) ? "remaining budget" : "roster")
                + " cannot pay for and judge a repair; they go to the gate as listed findings");
        return false;
    }

    /** Name the leftover when the only remaining P1s are not the implementer's. */
    private void noteNonactionableStop(Workflow.Stage stage) {
        if (repairableFindings(stage) > 0) return;
        Map<String, Object> now = lastFindingRound(stage.name());
        if (now == null || !(now.get("findings") instanceof List<?> rows)) return;
        List<String> leftover = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> finding
                    && "P1".equals(String.valueOf(finding.get("severity")))
                    && !"product_defect".equals(String.valueOf(finding.get("category")))) {
                leftover.add(String.valueOf(finding.get("id")));
            }
        }
        if (leftover.isEmpty()) return;
        summary.put("nonactionable_blocking_ids", leftover);
        summary.put("resolution", "The remaining P1(s) are categorised as something an "
                + "implementer cannot close by editing the candidate. A person has to change "
                + "the task, grant access, or settle the disagreement; another fix round "
                + "would not.");
    }

    /**
     * Stop a repair loop that is not repairing anything.
     *
     * The condition is the plan's, and both halves of it matter: the same confirmed
     * blockers came back, *and* the evidence they are about did not move. Either alone is
     * a bad signal. A repair that changes the tree and still leaves the finding open may
     * have closed half of it, and stopping there would throw away real progress; a
     * different finding on an unchanged tree means the reviewer looked somewhere new.
     * Together they mean the round bought nothing, and the next one would buy the same.
     *
     * Measured live on 2026-09-07: a repair cost $0.031 and changed no bytes, and the
     * round then spent $1.46 for one reviewer to reach the same pass and another the same
     * fail. Nothing compared the tree before the repair with the tree after it, so the
     * loop would have gone on doing that until `max_fix_attempts` ran out.
     *
     * Checked after the re-read rather than straight after the fix. Stopping the moment a
     * repair writes nothing would be cheaper and is wrong: the stage that objected has not
     * spoken yet, and it is the only thing that can say whether the objection still
     * stands.
     */
    private void requireFindingsProgress(Workflow.Stage stage) {
        Map<String, Object> now = lastFindingRound(stage.name());
        if (now == null) return;
        if (!(now.get("blocking_ids") instanceof List<?> blockers) || blockers.isEmpty()) return;
        // `candidate_moved` is absent on a stage's first round, and a first round cannot
        // be a repeat of anything.
        if (!Boolean.FALSE.equals(now.get("candidate_moved"))) return;
        boolean closedNothing = now.get("closed") instanceof List<?> closed && closed.isEmpty();
        boolean foundNothingNew = now.get("new_findings") instanceof List<?> fresh && fresh.isEmpty();
        if (!closedNothing || !foundNothingNew) return;

        Map<String, Object> stalled = new LinkedHashMap<>();
        stalled.put("at_stage", stage.name());
        stalled.put("fix_attempt", (long) attempt);
        stalled.put("candidate_fingerprint", now.get("candidate_fingerprint"));
        stalled.put("open_blocking_ids", blockers.stream().map(String::valueOf).toList());
        summary.put("repair_made_no_progress", stalled);
        summary.put("resolution", "The repair for '" + stage.name() + "' left the candidate "
                + "byte-for-byte unchanged and the same blocking finding came back, so "
                + "another round would re-read the same bytes for the same verdict. Read "
                + "what the finding actually asks for: one an implementer cannot act on — a "
                + "weak acceptance command, a missing access grant, a disagreement about the "
                + "contract — is resolved by a person changing the task, not by another fix.");
        progress.line("      the repair changed nothing and the same finding came back; "
                + "stopping rather than buying the same verdict again");
        throw new StopException("repair_made_no_progress");
    }

    /**
     * Refuse a repair the remaining allowance cannot pay for, on the terms the policy set.
     *
     * The budget already refused a call it could not afford. That check fires one call too
     * late to be useful: it stops the repair the run cannot pay for, having already spent
     * the allowance on repairs whose consequences it then could not judge. Measured on a
     * live run — six calls bought an implementer, two repairs and three reviews, and the
     * two stages that would have finished the workflow were never reached.
     *
     * How much has to fit is `budget.repair_reserve`, and the default is `full`: the
     * repair, every judgement it invalidates, and every stage still owed. A run either
     * completes or does not start the attempt.
     *
     * `partial` is the opt-in, and it is a real trade rather than a strictly better one.
     * In its favour: the unspent calls are not saved by declining to spend them, since
     * only raising the ceiling unlocks the rest of the chain, and a review that then
     * passes is a verdict a continuation reuses for nothing. Against it: a repair can
     * introduce a regression rather than remove one, and a person may decide not to
     * continue the task at all, in which case the money bought nothing. That judgement is
     * the operator's, so it is declared rather than inferred here.
     */
    private void requireReserve(Workflow.Stage stage, int index) {
        String mode = user.policy() == null ? "full" : user.policy().repairReserve();
        int required = callPlan.requiredFor(index, mode);
        int toFinish = callPlan.reserveForRepair(index);
        int remaining = budget.remaining();
        Map<String, Object> reserve = new LinkedHashMap<>();
        reserve.put("at_stage", stage.name());
        reserve.put("repair_reserve", mode);
        reserve.put("calls_required_here", (long) required);
        reserve.put("calls_needed_to_repair", (long) callPlan.repairCost(index));
        reserve.put("calls_needed_to_repair_and_be_judged",
                (long) callPlan.repairAndJudgeCost(index));
        reserve.put("calls_needed_to_repair_and_finish", (long) toFinish);
        reserve.put("calls_remaining", (long) remaining);
        reserve.put("calls_after_this_stage", (long) callPlan.callsAfter(index));
        reserve.put("cap", budget.maxRuns());
        if (remaining >= required) {
            // Allowed, but not silently. Under `partial` this is the case where the run
            // knows now that it will stop short, and saying so at the start beats
            // discovering it in the summary.
            if (remaining < toFinish) {
                reserve.put("will_finish", false);
                summary.put("budget_reserve", reserve);
                progress.line("      repair_reserve=" + mode + ": the remaining " + remaining
                        + " call(s) cover this repair and a judgement of it, but not the "
                        + callPlan.callsAfter(index) + " stage(s) after it; this run will "
                        + "stop with the chain unfinished");
            }
            return;
        }
        reserve.put("will_finish", false);
        summary.put("budget_reserve", reserve);
        summary.put("resolution", "The remaining " + remaining + " vendor call(s) do not "
                + "meet the repair reserve this policy requires (" + mode + ", needing "
                + required + "). Raise budgets.max_role_runs to at least "
                + (budget.chainRuns() + toFinish) + " and continue this run — the verdicts "
                + "already reached on this tree are kept, because a change to the call "
                + "ceiling is not a change to what the work is judged by."
                + ("full".equals(mode)
                    ? " Or set budget.repair_reserve: partial in policy.yaml to allow paid "
                      + "progress that this run cannot finish."
                    : ""));
        progress.line("      repair_reserve=" + mode + ": the remaining " + remaining
                + " call(s) do not meet the reserve of " + required + " this repair needs");
        throw new StopException("budget_insufficient_to_finish");
    }

    /**
     * Refuse a repair that nothing left on the roster could judge.
     *
     * The money is only half of what a repair commits the run to; the other half is a
     * reader for what it produces. Both used to be discovered late and in the wrong order:
     * the repair ran, moved the tree, and the review stage after it then failed to resolve
     * because the one remaining vendor was the one that had just written the code. The
     * run ended with an unjudged candidate and had paid for the privilege.
     *
     * Independence is the constraint that actually runs out. A two-vendor roster minus one
     * spent subscription is one vendor, and a reviewer required to differ from the
     * implementer has nobody — which is a fact about the roster, not about the diff, and
     * says so in its own stop reason.
     */
    private void requireJudgesRemain(Workflow.Stage stage, int index) {
        String writer = candidateWriters.vendorNamesOrNull();
        List<Workflow.Stage> needed = new ArrayList<>();
        for (Workflow.Stage earlier : workflow.recheckBefore(index)) {
            if (earlier.kind() == Workflow.Kind.ROLE && plan.skipReason(earlier) == null) {
                needed.add(earlier);
            }
        }
        if (stage.kind() == Workflow.Kind.ROLE) needed.add(stage);
        for (int position = index + 1; position < workflow.stages().size(); position++) {
            Workflow.Stage later = workflow.stages().get(position);
            if (later.kind() == Workflow.Kind.ROLE && plan.skipReason(later) == null) needed.add(later);
        }
        // The repair itself first: a fix role nobody can fill makes the rest moot.
        if (!roles.canFill(user, stage.name() + "/fix", stage.fixWith(),
                RoleResolver.Writers.NONE, RoleRunner.UNSTAGED)) {
            throw unfillable(stage.fixWith(), stage.name(), "the repair itself", null);
        }
        for (Workflow.Stage judge : needed) {
            RoleResolver.Writers avoid = "implementer".equals(judge.role())
                    ? RoleResolver.Writers.NONE : candidateWriters.readersAvoid();
            if (roles.canFill(user, judge.name(), judge.role(), avoid,
                    workflow.rotationPositionOf(judge))) {
                continue;
            }
            throw unfillable(judge.role(), stage.name(), judge.name(),
                    "implementer".equals(judge.role()) ? null : writer);
        }
    }

    /** The first stage that dispatches {@code role} as a writer, or null in a chain without one. */
    private Workflow.Stage writerStageOf(String role) {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() == Workflow.Kind.ROLE && role.equals(stage.role())) return stage;
        }
        return null;
    }

    private StopException unfillable(String role, String at, String forStage, String avoid) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("role", role);
        gap.put("needed_for", forStage);
        gap.put("blocked_at", at);
        gap.put("must_differ_from_vendor", avoid);
        gap.put("exhausted_profiles", List.copyOf(roles.exhaustedProfiles()));
        summary.put("unavailable_role", gap);
        summary.put("resolution", "Repairing here would produce a candidate that '" + forStage
                + "' could not then judge: no profile can fill role '" + role + "'"
                + (avoid == null ? "" : " that differs from every vendor which wrote this code ("
                    + avoid + ")")
                + ". Add a profile from another vendor, or wait for the quota window named "
                + "in the vendor message, then continue this run.");
        progress.line("      nothing left can fill " + role + " for " + forStage
                + "; stopping before the repair rather than after it");
        return new StopException("independent_review_unavailable");
    }

    private Object execute(Workflow.Stage stage) throws Exception {
        progress.announce(stage, attempt);
        long started = System.nanoTime();
        int previousStepCount = steps.size();
        Object outcome = progress.dispatch(stage, attempt, () -> dispatch(stage));
        long millis = (System.nanoTime() - started) / 1_000_000L;
        evidence.stampStage(stage, previousStepCount);
        progress.finished(stage, outcome, millis);
        return outcome;
    }

    private Object dispatch(Workflow.Stage stage) throws Exception {
        switch (stage.kind()) {
            case MACHINE_GATES -> {
                return checks.machineGates(stage, attempt);
            }
            case VISUAL_HARNESS -> {
                return checks.browserHarness(stage, attempt);
            }
            default -> {
                return runRole(stage);
            }
        }
    }

    /**
     * What this dispatch is told beyond its own prompt, or null when it needs nothing.
     *
     * Two different memories, and never both: an implementer starting from a person's
     * rejection, and a judging stage being handed findings — its own previous round, or
     * an earlier stage's registry on its first reading. Anything else gets the plain
     * prompt, because a role with nothing to remember should not be handed a preamble
     * telling it so.
     */
    private Path contextFor(Workflow.Stage stage) throws Exception {
        Path rejection = writerContinuation.contextFor(stage.role());
        if (rejection != null) return rejection;
        if (stage.onFindings() == null) return null;
        String pack = reviewerPackage(stage);
        if (pack == null) return null;
        // Shared roles need separate evidence for each stage at the same attempt.
        // Use the workflow position: operator-chosen names can have identical slugs.
        String kind = stage.contextKind();
        if (workflow.stagesFor(stage.role()).size() > 1) {
            kind += "-stage-" + (workflow.stages().indexOf(stage) + 1);
        }
        return RepairBrief.writeContext(ledger, attempt, kind + "-recheck", pack);
    }

    private RoleRunner.Outcome runRole(Workflow.Stage stage) throws Exception {
        roles.atStage(stage.name());
        String role = stage.role();
        // By stage first. The role name is accepted only as the legacy spelling of a
        // stage, and only when this workflow gives that role exactly one — otherwise the
        // first review stage would be free to spend the verdict the second one earned.
        Map<String, Object> standing = null;
        if (attempt == 0) {
            standing = evidence.takeReusable(stage.name());
            if (standing == null && workflow.stagesFor(role).size() == 1) {
                standing = evidence.takeReusable(role);
            }
        }
        // Currency is checked here, against the tree as it stands at this moment, not only
        // when the pool was built. A stage earlier in this same resume can have dispatched
        // for real and moved the tree — an implementer whose contract changed, say — and a
        // verdict about the tree before that dispatch is not a verdict about this
        // candidate. The pool build proved the prior run's final tree; only this proves the
        // tree the verdict will actually be spent on.
        if (standing != null) {
            Object judged = standing.get("candidate_fingerprint");
            String now = baseline.fingerprint();
            if (!(judged instanceof String stamped) || now == null || !stamped.equals(now)) {
                reuseDeclinedAtConsumption(stage,
                        judged instanceof String was ? was : null, now);
                standing = null;
            }
        }
        if (standing != null) {
            // Not a shortcut and not a cache: the fingerprint check just above says these
            // are the same bytes the earlier vendor read, as the tree stands right now. A
            // fix round moves the tree and a re-dispatched earlier stage can too, which is
            // why the check is here and not only at attempt 0's pool build.
            Map<String, Object> entry = new LinkedHashMap<>(standing);
            entry.put("reused_from", carried.fromRunId());
            entry.put("attempt", (long) attempt);
            steps.add(entry);
            if ("implementer".equals(role)) candidateWriters.recordReusedWriter(standing);
            reusedStages.add(stage.name());
            summary.put("reused_judgements", Map.of("from", carried.fromRunId(),
                    "stages", List.copyOf(reusedStages)));
            // The verdict itself, not just the row. Without this a run whose every stage
            // was carried over reported `candidate_review_passed: false` and no coverage
            // at all — so `oseer report --text` told the operator the candidate had not
            // passed review, about a candidate a reviewer had passed and whose reuse the
            // fingerprint had just been checked to justify. Reproduced on run `ru2`.
            carryCoverage(stage, standing);
            progress.line("      reused from " + carried.fromRunId() + ": "
                    + standing.get("profile") + (standing.get("fix_for") != null
                        ? " already produced this exact tree in a fix round for "
                            + standing.get("fix_for")
                        : " already passed this exact tree"));
            return null;
        }
        // Independence is measured against everyone who wrote the code, not the last
        // implementer alone; a writer is not measured against itself.
        RoleResolver.Writers avoid = "implementer".equals(role) ? RoleResolver.Writers.NONE : candidateWriters.readersAvoid();
        budget.dispatching(stage.name());
        RoleRunner.Outcome outcome;
        if ("visual_qa".equals(role)) {
            outcome = checks.visualReview(stage, attempt, roles, avoid, budget);
            // The same claim as for every other role: how the stage routes, and here also
            // who took the pictures, are terms the verdict was reached under.
            stampRouting(stage);
        } else {
            String evidenceRunId = StageIds.stageRunId(runId, workflow, stage, attempt);
            outcome = roles.run(loaded, user, role, evidenceRunId,
                    avoid, contextFor(stage), List.of(), dryRun, workflow.rotationPositionOf(stage));
            StepRecord.record(steps, budget, role, attempt, outcome, stage.name());
            if (StageIds.isDedicated(runId, evidenceRunId) && outcome.report() != null) {
                steps.getLast().put("evidence_dir", evidenceRunId);
                steps.getLast().put("report", outcome.report().toString());
            }
            // The role runner knows the profile and the roster; only the loop knows how
            // the stage that dispatched it routes. Both halves belong to the same claim
            // about the terms this verdict was reached under.
            stampRouting(stage);
        }
        if (dryRun) {
            if (outcome != null && !outcome.ok()) previewStop(stage, outcome);
            return outcome;
        }
        if (outcome == null) return outcome;
        candidateWriters.recordWriterOutcome(outcome);
        // The tree this role just judged, on the row itself, so a later run can prove the
        // verdict is about the candidate it is being spent on rather than about whatever
        // this run's final tree happened to be. Stamped for every role: an implementer's
        // fingerprint is the tree after it wrote, a reviewer's is the tree it read.
        stampFingerprint();
        // A read-only role that edited the contract has already invalidated the very
        // thing it is about to be believed for.
        baseline.requireUnchangedContract(loaded);
        return outcome;
    }

    /**
     * A preview does not stop for a role nobody can fill, because it has no candidate to
     * protect, but it has to say so.
     *
     * Measured on the pilot template: both roles came back `role_unresolved`, one profile
     * unverified and one whose executable was not on the path, and the preview still
     * finished `ok`. The refusal was in the steps for anyone who read them, and otherwise
     * in a live run that dispatched nobody. The reason recorded is the one the real run
     * stops with, and the first one wins, as it would there.
     */
    private void previewStop(Workflow.Stage stage, RoleRunner.Outcome outcome) {
        if (summary.get("would_stop") != null) return;
        summary.put("would_stop", terminalReason(stage, outcome));
        StringBuilder why = new StringBuilder("stage " + stage.name() + ": " + outcome.code());
        if (outcome.rejected() != null && !outcome.rejected().isEmpty()) {
            why.append(", refused profiles ").append(outcome.rejected());
        }
        Object message = outcome.details() == null ? null : outcome.details().get("message");
        if (message != null) why.append(". ").append(message);
        summary.put("resolution", why.toString());
    }

    /**
     * What the repair said it did, kept for the stage that has to check whether it did.
     *
     * The implementer's own account is not evidence — `moved_candidate` beside it is, and
     * the two are handed over together precisely so a reviewer can notice when they
     * disagree.
     */
    private void recordRepairReceipt(RoleRunner.Outcome fix, String before) throws Exception {
        Map<String, Object> artifact = RepairBrief.readArtifact(loaded.root(), fix);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("attempt", (long) attempt);
        receipt.put("profile", fix.profile());
        receipt.put("summary", artifact == null ? "(the implementer filed no artifact)"
                : String.valueOf(artifact.get("summary")));
        if (artifact != null && artifact.get("files_changed") != null) {
            receipt.put("files_changed", artifact.get("files_changed"));
        }
        String after = baseline.fingerprint();
        receipt.put("moved_candidate",
                before != null && after != null && !before.equals(after));
        receipt.put("candidate_fingerprint", after);
        lastRepair = receipt;
        summary.put("last_repair_receipt", receipt);
    }

    /** Record, on the last step row, the fingerprint of the tree the role just judged. */
    private void stampFingerprint() {
        if (steps.isEmpty()) return;
        steps.get(steps.size() - 1).put("candidate_fingerprint", baseline.fingerprint());
    }

    /** Fold this stage's routing into the contract the row already carries. */
    @SuppressWarnings("unchecked")
    private void stampRouting(Workflow.Stage stage) {
        if (steps.isEmpty()) return;
        Map<String, Object> row = steps.get(steps.size() - 1);
        if (!(row.get("role_contract") instanceof Map<?, ?> recorded)) return;
        Map<String, Object> contract = new LinkedHashMap<>((Map<String, Object>) recorded);
        contract.putAll(RoleContract.routingOf(stage));
        row.put("role_contract", contract);
    }

    /**
     * A verdict carried from an earlier run, restored as the judgement it was.
     *
     * Marked `reused` and not `executed`, and carrying the run it came from. Those are
     * different claims about the same conclusion, and a card or a report that showed a
     * carried verdict as a fresh dispatch would be inventing a vendor call that never
     * happened — the opposite mistake to the one this method fixes.
     */
    private void carryCoverage(Workflow.Stage stage, Map<String, Object> standing) {
        if (!judgesCandidate(stage, task)) return;
        Object blocking = standing.get("blocking_findings");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage.name());
        row.put("role", stage.role());
        row.put("source", "reused");
        row.put("reused_from", carried.fromRunId());
        row.put("ok", true);
        // Zero by construction: a row with blocking findings was excluded from reuse
        // before it got here. Copied rather than assumed, so the number in the report is
        // the number the earlier run actually recorded.
        row.put("blocking_findings", blocking instanceof Number number ? number.longValue() : 0L);
        row.put("profile", standing.get("profile"));
        row.put("vendor", standing.get("vendor"));
        row.put("assurance", assuranceOf(standing.get("independence"),
                String.valueOf(standing.get("vendor"))));
        // The fingerprint the earlier verdict actually judged, preserved — not overwritten
        // with this run's. It is equal to this run's tree by the time we are here, because
        // consumption declined reuse otherwise, but the coverage records what the reviewer
        // saw, and stamping the current run's fingerprint would only be right by accident.
        row.put("candidate_fingerprint", standing.get("candidate_fingerprint"));
        coverage.put(stage.name(), row);
        summary.put("review_coverage", List.copyOf(coverage.values()));
    }

    /**
     * A reusable verdict abandoned at the moment it would have been spent, because the tree
     * moved since it was reached. It falls through to a real dispatch; recording why keeps
     * a resume that quietly re-ran a stage from looking like one that skipped it.
     */
    private void reuseDeclinedAtConsumption(Workflow.Stage stage, String judged, String now) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage.name());
        row.put("judged_fingerprint", judged);
        row.put("current_fingerprint", now);
        row.put("reason", judged == null
                ? "the carried verdict recorded no candidate fingerprint"
                : "the tree changed after the pool was built, so this verdict is about "
                        + "another candidate");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declines = summary.get("reuse_declined_at_consumption")
                instanceof List<?> existing
                ? new ArrayList<>((List<Map<String, Object>>) (List<?>) existing)
                : new ArrayList<>();
        declines.add(row);
        summary.put("reuse_declined_at_consumption", declines);
        progress.line("note  the verdict carried for stage '" + stage.name() + "' judged a "
                + "tree this resume has since changed; re-running it rather than believing it");
    }

    /**
     * What this stage objected to this round, against what it objected to last round.
     *
     * Round deltas stay per stage, because two reviewers reading the same candidate reach
     * their own conclusions and a shared closed/persisted list would let one of them
     * appear to have closed the other's finding. The registry is the exception: a later
     * stage's first reading inherits it so a closed requirement cannot re-enter repair
     * on the original evidence. The comparison of one stage to itself is still what
     * makes "the repair achieved nothing" a fact the run can state.
     */
    private void recordFindingRound(Workflow.Stage stage, RoleRunner.Outcome outcome)
            throws Exception {
        List<Findings.Finding> found = RepairBrief.acknowledgedFindings(
                loaded.root(), outcome, task.manualVerification(),
                task.acceptanceEvidence().recordsRealAcceptance());
        Map<String, Object> row = findingRound(stage.name(), attempt, baseline.fingerprint(), found, false);
        findingHistory.add(row);
        summary.put("finding_history", List.copyOf(findingHistory));
        List<Map<String, Object>> unverified = RepairBrief.unverifiedFindings(findingHistory);
        if (unverified.isEmpty()) summary.remove("unverified_findings");
        else summary.put("unverified_findings", unverified);
        if (!stringsOf(row.get("protocol_violations")).isEmpty()) {
            summary.put("finding_protocol_failure", row.get("protocol_violations"));
            throw new StopException("finding_protocol_failure");
        }
    }

    /**
     * Whether the tree has moved since the round a re-reading is being compared against.
     * Asked only where there is no repair receipt to say it instead.
     */
    private boolean movedSince(Map<String, Object> last) {
        Object judged = last == null ? null : last.get("candidate_fingerprint");
        String now = baseline.fingerprint();
        return judged instanceof String fingerprint && now != null && !fingerprint.equals(now);
    }

    /** The most recent recorded round for this stage, or null when it has had none. */
    private Map<String, Object> lastFindingRound(String stage) {
        for (int index = findingHistory.size() - 1; index >= 0; index--) {
            if (stage.equals(findingHistory.get(index).get("stage"))) {
                return findingHistory.get(index);
            }
        }
        return null;
    }

    /**
     * The most recent round filed by any other stage. A later reviewer's first reading
     * has no previous round of its own; this is the registry and repair context it
     * still has to see.
     */
    private Map<String, Object> lastInheritedFindingRound(String stage) {
        for (int index = findingHistory.size() - 1; index >= 0; index--) {
            if (!stage.equals(findingHistory.get(index).get("stage"))) {
                return findingHistory.get(index);
            }
        }
        return null;
    }

    private long recordFindings(Workflow.Stage stage, RoleRunner.Outcome outcome) throws Exception {
        long blocking = RepairBrief.blockingFindings(loaded.root(), outcome, task.manualVerification(),
                task.acceptanceEvidence().recordsRealAcceptance());
        recordFindingRound(stage, outcome);
        // Preserve the legacy current-round field on writing loops. In review mode every
        // reader judges the same candidate, so the count includes each stage of that role.
        String findingsSummaryKey = findingsKey(stage);
        long earlier = task.reviewOnly() && summary.get(findingsSummaryKey) instanceof Number count
                ? count.longValue() : 0L;
        summary.put(findingsSummaryKey, earlier + blocking);
        // The same number, under the one identity that is unique in a workflow. Without it
        // "did review-second object" is a question the summary cannot answer, and a resume
        // deciding which verdict it may keep is answering it from the wrong stage.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", stage.name());
        row.put("role", stage.role());
        row.put("source", "executed");
        row.put("ok", outcome.ok());
        row.put("blocking_findings", blocking);
        row.put("profile", outcome.profile());
        row.put("vendor", outcome.vendor());
        row.put("assurance", assuranceOf(outcome.details() == null ? null
                : outcome.details().get("independence"), outcome.vendor()));
        // Which tree this verdict is about. A judgement without one is a claim that
        // cannot be checked against the candidate a person is later asked to accept.
        row.put("candidate_fingerprint", baseline.fingerprint());
        coverage.put(stage.name(), row);
        // It has now judged the candidate as it currently stands, whatever it concluded.
        // Leaving it marked as judging an earlier tree would report a stage that ran and
        // objected as one that never looked, which sends a reader past the finding.
        staleAfterFix.remove(stage.name());
        summary.put("stages_judging_an_earlier_candidate", List.copyOf(staleAfterFix));
        summary.put("review_coverage", List.copyOf(coverage.values()));
        // And on the step row itself, which is what a later run reads back when it asks
        // whether this exact judgement can stand without being paid for twice.
        for (int index = steps.size() - 1; index >= 0; index--) {
            Map<String, Object> step = steps.get(index);
            if (!stage.name().equals(step.get("stage"))) continue;
            step.put("blocking_findings", blocking);
            break;
        }
        if (blocking > 0) {
            // A role that passed its own run and still objects is the whole reason a
            // second vendor is paid, so it says so out loud rather than only in a file.
            progress.line("      " + blocking + " blocking finding(s) from " + stage.role()
                    + " — see oseer report for what it saw");
            // One objecting reading, whatever stage gave it and whether it was a first
            // reading or a recheck. The ladder counts readings, not findings.
            blockingReviewsSeen++;
            if (summary.get("escalation") instanceof Map<?, ?> state) {
                Map<String, Object> updated = new LinkedHashMap<>();
                state.forEach((key, value) -> updated.put(String.valueOf(key), value));
                updated.put("blocking_reviews_seen", blockingReviewsSeen);
                summary.put("escalation", updated);
            }
        }
        // Consulted here rather than only after a repair: a stage's first reading of a
        // resumed run has a previous round restored from the prior run, and that is the
        // one round of that run that would otherwise never reach the fix-loop guards.
        requireNoSeverityLaundering(stage);
        return blocking;
    }

    /**
     * What a verdict may claim about its reader, settled here rather than trusted from
     * the row: an unknown writer set degrades every label to `unproven`, and a reader
     * whose vendor is in a known writer set can only be what the resolver admitted it as.
     */
    private String assuranceOf(Object resolved, String vendor) {
        if (!candidateWriters.readersAvoid().known()) return RoleResolver.UNPROVEN;
        if (resolved instanceof String label && !label.isBlank()
                && !RoleResolver.NONE.equals(label) && !RoleResolver.UNPROVEN.equals(label)) {
            return label;
        }
        if (vendor != null && candidateWriters.contains(vendor)) return "coauthor";
        return RoleResolver.INDEPENDENT;
    }

    private String findingsKey(Workflow.Stage stage) {
        return switch (stage.role()) {
            case "reviewer" -> "blocking_findings";
            case "visual_qa" -> "visual_blocking_findings";
            default -> stage.role() + "_blocking_findings";
        };
    }

    private boolean failed(Object outcome) {
        if (outcome instanceof GateRunner.Outcome gate) return !gate.ok();
        if (outcome instanceof VisualQaRunner.Outcome visual) return !visual.ok();
        if (outcome instanceof RoleRunner.Outcome role) return !role.ok();
        return false;
    }

    /**
     * A call whose wall clock the chain deadline lowered, and which then ran out, stopped
     * because of the deadline. Naming it `role_timed_out` would send the operator to raise
     * a profile limit that was never the binding one.
     */
    private void requireDeadlineNotTheCause(RoleRunner.Outcome role) {
        if (!RunFailurePolicy.timedOutUnderDeadline(role.code(), role.details())) return;
        throw new RunBudget.ExceededException("max_elapsed_minutes", "max_elapsed_minutes of "
                + (budget.maxElapsedSeconds() / 60) + " reached: a " + role.profile()
                + " call was cut to the " + role.details().get("wall_clock_minutes")
                + " minute(s) the chain had left, and used them"
                + ("role_orca_timeout".equals(role.code())
                    ? "; its Orca worker must be settled before a continuation dispatches, "
                      + "and Orca's own lifecycle check refuses one until it is"
                    : ""));
    }

    private String terminalReason(Workflow.Stage stage, Object outcome) {
        if (outcome instanceof VisualQaRunner.Outcome visual) return visual.code();
        if (outcome instanceof RoleRunner.Outcome role) requireDeadlineNotTheCause(role);
        // A reader nobody may fill because every candidate wrote the code is a fact about
        // the writers, not a reviewer objecting; `reviewer_failed` would send the operator
        // to a transcript with no defect in it and a continuation would discard verdicts.
        if (outcome instanceof RoleRunner.Outcome role && "role_unresolved".equals(role.code())
                && role.rejected() != null && !role.rejected().isEmpty()
                && role.rejected().values().stream().allMatch(RoleResolver::isIndependenceReason)) {
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("role", stage.role());
            gap.put("needed_for", stage.name());
            gap.put("rejected_profiles", role.rejected());
            gap.put("writer_vendors", candidateWriters.vendors());
            summary.put("unavailable_role", gap);
            summary.putIfAbsent("resolution", "no profile can read this candidate as '"
                    + stage.name() + "' requires: " + role.rejected() + ". Every vendor that "
                    + "could fill the role wrote into the candidate (" + candidateWriters.vendorNames() + "), or the task does not admit "
                    + "the same-vendor peer the policy declares. Add a reader from another "
                    + "vendor, or set review_assurance: same_vendor_peer on the task if that "
                    + "weaker check is acceptable for it.");
            return "independent_review_unavailable";
        }
        if (outcome instanceof RoleRunner.Outcome role) return RunFailurePolicy.reasonFor(role, stage.failureReason());
        // A gate that found the contract moved is the same stop as a role that moved it, and
        // the operator needs the same next step: put the files back. Reported as
        // `gates_not_satisfied` it sent them to repair code that was never the cause.
        if (outcome instanceof GateRunner.Outcome gate && "contract_mutated".equals(gate.code())) {
            return "contract_mutated";
        }
        if (outcome instanceof GateRunner.Outcome gate && "gate_environment_error".equals(gate.code())) {
            GateStage.recordEnvironmentError(summary, gate);
            return "gate_environment_error";
        }
        return stage.failureReason();
    }

    private String failureContext(Workflow.Stage stage, Object outcome) throws Exception {
        if (outcome instanceof GateRunner.Outcome gate) return RepairBrief.gateContext(gate);
        if (outcome instanceof VisualQaRunner.Outcome visual) return RepairBrief.visualContext(visual);
        if (outcome instanceof RoleRunner.Outcome role) {
            return RepairBrief.reviewContext(loaded.root(), role, repairSeverities(), task.manualVerification(),
                    task.acceptanceEvidence().recordsRealAcceptance());
        }
        return "# " + stage.name() + " failed\n";
    }
}
