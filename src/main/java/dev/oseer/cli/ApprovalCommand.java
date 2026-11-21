package dev.oseer.cli;

import dev.oseer.approval.ApprovalException;
import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.approval.StatusCommand;
import dev.oseer.config.ConfigLoader;
import dev.oseer.config.RunOverride;
import dev.oseer.config.UserConfig;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.process.ProcessRunner;
import dev.oseer.run.ContractProposal;
import dev.oseer.run.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static dev.oseer.cli.ContinuationCommand.startAdvance;
import static dev.oseer.cli.ContinuationCommand.supervisorAlive;
import static dev.oseer.cli.ContinuationPolicy.advanceWouldRepeat;
import static dev.oseer.cli.ContinuationPolicy.continues;
import static dev.oseer.cli.ContinuationPolicy.rejectedWithNote;
import static dev.oseer.cli.DecisionProjection.updateDecisionProjection;
import static dev.oseer.cli.GateWait.GatePoll;
import static dev.oseer.cli.GateWait.MappedGate;
import static dev.oseer.cli.GateWait.pollGate;
import static dev.oseer.cli.GateWait.skippedGateWait;
import static dev.oseer.cli.RunPresentation.narrateDecision;

public final class ApprovalCommand {
    private ApprovalCommand() {}

    static int approve(String[] args, RunObservation observation) throws Exception {
        ApproveOutcome outcome = approveDecision(Path.of("."), args, ApproveEnv.realtime().withObservation(observation));
        CliOutput.json(outcome.report());
        return outcome.ok() ? 0 : 1;
    }

    /**
     * Thin wrapper around the same waiter {@code oseer run --wait-for-gate} uses. Never
     * starts a run: it imports one published gate or says why the wait was skipped.
     */
    public static Map<String, Object> waitForPublishedGate(Path root, String runId,
                                                    Map<String, Object> summary, int waitMinutes,
                                                    ApproveEnv env) throws Exception {
        Object published = summary == null ? null : summary.get("orca_gate");
        if (!(published instanceof Map<?, ?> gate) || !Boolean.TRUE.equals(gate.get("published"))) {
            String why;
            if (!(published instanceof Map<?, ?> map)) why = "no_orca_gate";
            else if (map.get("reason") != null) why = String.valueOf(map.get("reason"));
            else why = "gate_not_published";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("gate_wait", skippedGateWait(why));
            return result;
        }
        ApproveOutcome imported = approveDecision(root,
                new String[] {"approve", runId, "--from-orca", "--wait-minutes",
                        Integer.toString(waitMinutes), "--no-start"},
                env);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate_import", imported.report());
        return result;
    }

    public record ApproveOutcome(boolean ok, Map<String, Object> report) {}

    public static ApproveOutcome approveDecision(Path start, String[] args, ApproveEnv env) throws Exception {
        ApprovalRequest request = ApprovalRequest.parse(args);
        String runId = request.runId();
        String choice = request.choice();
        String actor = request.actor();
        String note = request.note();

        Optional<Path> found = new ConfigLoader().locateProjectRoot(start);
        if (found.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>(StatusCommand.notAOverseerProject(start));
            result.put("lands", false);
            return new ApproveOutcome(false, result);
        }
        Path root = found.get();
        ApprovalStore store = new ApprovalStore(root);
        try {
            HumanDecision pending = store.read(runId);
            Map<String, Object> gateReport = null;
            if (request.fromOrca()) {
                if (pending.state() != HumanDecision.State.PENDING) {
                    throw new ApprovalException("duplicate_decision",
                            "run " + runId + " is already resolved");
                }
                GatePoll poll = pollGate(root, runId, store, env, request.waitMinutes());
                if (poll.timeout() != null) return new ApproveOutcome(false, poll.timeout());
                MappedGate mapped = poll.mapped();
                choice = mapped.choice();
                pending = mapped.pending();
                if (actor == null) actor = "orca-gate:" + mapped.gate().gateId();
                gateReport = mapped.gateReport();
            }
            String expected = request.expectedUpdatedAt() == null
                    ? pending.updatedAt().toString() : request.expectedUpdatedAt();

            // Waive closes a contract_gap-only stop. Refused before the decision is written,
            // so a product defect stays pending and the gate can still be answered another way.
            ContractGapWaiver waiver = null;
            if ("waive".equals(choice)) {
                waiver = ContractGapWaiver.validate(root, pending, request.finding());
            }
            // A gate past its declared life can still be closed, but not accepted on the
            // strength of evidence that was current when it was published: a live page, a
            // deployment, a network observation may all have moved since. Reject, abort and
            // retry remain available, and an explicit acknowledgement re-opens accept.
            boolean expired = pending.expiredAt(Instant.ofEpochMilli(env.clock.nowMillis()));
            if ("accept".equals(choice) && expired && !request.acknowledgeExpired()) {
                throw new ApprovalException("gate_expired", "run " + runId + " asked its question "
                        + "on " + pending.createdAt() + " and its gate expired on "
                        + pending.expiresAt() + " (budgets.gate_ttl_hours). Re-check anything "
                        + "that can go stale, then accept with --acknowledge-expired, or answer "
                        + "reject/retry/abort; no answer is never an acceptance.");
            }
            if ("waive".equals(choice) && expired && !request.acknowledgeExpired()) {
                throw new ApprovalException("gate_expired", "run " + runId + " asked its question "
                        + "on " + pending.createdAt() + " and its gate expired on "
                        + pending.expiresAt() + " (budgets.gate_ttl_hours). Re-check anything "
                        + "that can go stale, then waive with --acknowledge-expired, or answer "
                        + "retry/abort; no answer is never a waiver.");
            }
            // Acceptance, and a waiver of a contract gap, are valid only for the exact
            // candidate the human inspected. Reject, abort and retry remain available when
            // the tree moved, because they grant no land.
            if (("accept".equals(choice) || "waive".equals(choice))
                    && pending.candidateFingerprint() != null) {
                Path summaryPath = Path.of(pending.summaryPath());
                if (!summaryPath.isAbsolute()) summaryPath = root.resolve(summaryPath);
                Map<String, Object> summary = Json.parseObject(Files.readString(summaryPath));
                Object base = summary.get("diff_base_commit");
                if (!(base instanceof String commit)) {
                    throw new ApprovalException("candidate_unverifiable",
                            "task summary has no immutable diff_base_commit");
                }
                String current = new GitRepository(root, new ProcessRunner())
                        .sourceFingerprint(commit);
                if (!pending.candidateFingerprint().equals(current)) {
                    throw new ApprovalException("candidate_changed",
                            "worktree fingerprint changed after the decision was shown; run gates again");
                }
            }

            // Checked before the decision is recorded, whoever starts the next run. With
            // `--no-start` the caller starts it — the decision page's supervisor does — and a
            // refusal found after the gate was closed left nothing to answer and no run.
            if ("advance".equals(choice)) {
                String repeat = advanceWouldRepeat(root, pending);
                if (repeat != null) throw new ApprovalException("advance_would_repeat", repeat);
            }
            final boolean applyProposal = "apply".equals(choice);
            boolean waiterObserved = request.startUnlessWaiting() && supervisorAlive(root, runId);
            if (waiterObserved && !RunOverride.fromArgs(args).isEmpty()) {
                throw new ApprovalException("continuation_supervised",
                        "a process is already waiting; clear the stage overrides or stop that process before answering");
            }
            HumanDecision resolved = store.resolve(runId, expected, choice, actor, note, current -> {
                if (applyProposal) ContractProposal.apply(root, current);
            });
            Map<String, Object> waiverView = waiver == null ? null : waiver.view(actor, note);
            boolean summaryProjectionUpdated = updateDecisionProjection(root, resolved, waiverView);
            long waitMillis = Math.max(0L,
                    Duration.between(resolved.createdAt(), resolved.updatedAt()).toMillis());
            Map<String, Object> recorded = new LinkedHashMap<>();
            recorded.put("decision", choice);
            recorded.put("actor", actor);
            recorded.put("source", request.fromOrca() ? "orca_gate" : "cli");
            if (gateReport != null) recorded.put("orca_gate", gateReport);
            recorded.put("created_at", resolved.createdAt().toString());
            recorded.put("decided_at", resolved.updatedAt().toString());
            recorded.put("wait_millis", waitMillis);
            recorded.put("updated_at", resolved.updatedAt().toString());
            recorded.put("lands", false);
            if (waiverView != null) {
                recorded.put("finding_id", waiverView.get("finding_id"));
                recorded.put("waived_findings", waiverView.get("waived_findings"));
                recorded.put("cause", "contract_gap");
            }
            UserConfig user = env.user();
            EvidenceLedger decisionLedger = new EvidenceLedger(root, runId, user.home());
            decisionLedger.append("human_decision", recorded);
            if (waiverView != null) decisionLedger.append("contract_gap_waived", waiverView);
            // The card and the tab stop asking. `accept`, `abort`, `waive` and a rejection
            // with nothing to carry close the run. Waive does not start another run: the
            // same gap would stop it again. Every other decision expects another run, and
            // the card goes to the running column when that run actually starts, not here:
            // set here, it said "running" over a retry nobody had started and a continuation
            // that was refused. The tab was opened by another process; its handle is on disk.
            boolean closes = closesRun(resolved);
            Workspace card = Workspace.guarded(env.observation.forStatus(args).at(root));
            card.adopt(runId);
            if (closes) card.state(Workspace.State.SETTLED);
            else card.answered(choice);
            card.note(runId + " · " + choice + " by " + actor
                    + (note.isBlank() ? "" : " · " + note) + " · nothing was landed");
            narrateDecision(root, runId, choice, actor, closes);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("code", "decision_recorded");
            result.put("decision", resolved.toMap());
            if (gateReport != null) result.put("orca_gate", gateReport);
            result.put("summary_projection_updated", summaryProjectionUpdated);
            result.putAll(decisionLedger.corpusVisibility());
            result.put("lands", false);
            if ("advance".equals(choice) || "apply".equals(choice)
                    || "replan".equals(choice) || "proceed".equals(choice)) {
                Map<String, Object> started = startAdvance(root, resolved, args, env, waiterObserved);
                result.put("next_run", started);
                if (Boolean.TRUE.equals(started.get("waiter"))) {
                    result.put("waiter", true);
                    result.put("next", started.get("message"));
                } else {
                    result.put("next", started.get("command"));
                }
            } else if ("waive".equals(choice)) {
                result.put("next", "the contract_gap stop was waived; nothing was landed. "
                        + "A product defect cannot be waived.");
            } else if (request.startUnlessWaiting() && continues(resolved)
                    && (waiterObserved || supervisorAlive(root, runId))) {
                result.put("waiter", true);
                result.put("next", "a process is already waiting on this decision and will start the next run");
            } else {
                result.put("next", "accept".equals(choice)
                        ? "inspect the exact diff and land it manually if desired"
                        : "retry".equals(choice)
                            ? "start a new run id after addressing the recorded blocker"
                            : "no changes were landed");
            }
            return new ApproveOutcome(true, result);
        } catch (ApprovalException failure) {
            Map<String, Object> result = new CliOutput.Failure(failure.code(), failure.getMessage()).toJson();
            result.put("project", root.toString());
            result.put("lands", false);
            return new ApproveOutcome(false, result);
        }
    }

    private static boolean closesRun(HumanDecision decision) {
        return switch (decision.decision()) {
            case "accept", "abort", "waive" -> true;
            case "reject" -> !rejectedWithNote(decision.kind(), decision.decision(), decision.note());
            default -> false;
        };
    }
}
