package dev.oseer.execution.orca;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static dev.oseer.execution.orca.OrcaClient.nestedString;
import static dev.oseer.execution.orca.OrcaClient.tail;

/** Polls a known Orca dispatch and accounts for its delivery and termination. */
final class OrcaWorkerMonitor {
    private static final Duration CHECK_WINDOW = Duration.ofSeconds(60);
    private static final Duration CHECK_MARGIN = Duration.ofSeconds(5);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SETTLEMENT_RPC_TIMEOUT = Duration.ofSeconds(30);

    record Dispatch(Path root, String taskId, String dispatchId, String runId, String coordinatorHandle) {}

    private final OrcaClient orca;

    OrcaWorkerMonitor(OrcaClient orca) {
        this.orca = orca;
    }

    /**
     * The settlement reason for a start that Orca could not see and whose screen then showed a
     * first-run question. The caller fences it as a start failure, not as a stalled worker.
     */
    static final String TURN_START_BLOCKED = "turn_start_blocked";

    OrcaSettlement.Outcome await(Dispatch dispatch, Supplier<Duration> timeLeft, boolean turnPending,
                                 Map<String, Object> evidence) throws Exception {
        Path root = dispatch.root();
        String taskId = dispatch.taskId();
        String dispatchId = dispatch.dispatchId();
        OrcaSettlement.Outcome last = new OrcaSettlement.Outcome(
                OrcaSettlement.Kind.CHECKPOINT, taskId, dispatchId, null, Map.of(), "not_waited");
        OrcaSettlement.Outcome lastProvenAgentWait = null;
        OrcaSettlement.Outcome latestAgentObservation = null;
        OrcaSettlement.Outcome lastQuestion = null;
        while (positive(timeLeft.get())) {
            Duration beforeCheck = timeLeft.get();
            Duration window = bounded(beforeCheck, CHECK_WINDOW);
            List<String> checkArgs = new ArrayList<>(List.of(
                    "orchestration", "check",
                    "--wait",
                    "--types", "worker_done,escalation,question",
                    "--timeout-ms", String.valueOf(Math.max(1, window.toMillis()))));
            if (dispatch.runId() != null) {
                checkArgs.add("--run");
                checkArgs.add(dispatch.runId());
            }
            checkArgs.add("--terminal");
            checkArgs.add(dispatch.coordinatorHandle());
            Duration checkBudget = bounded(timeLeft.get(), window.plus(CHECK_MARGIN));
            if (!positive(checkBudget)) break;
            OrcaClient.Rpc checked = orca.invoke(root, checkBudget, checkArgs);
            last = OrcaSettlement.fromCheck(checked.envelope(), taskId, dispatchId);
            if (terminal(last)) {
                return last;
            }
            if (last.kind() == OrcaSettlement.Kind.QUESTION) {
                if (turnPending) {
                    turnPending = false;
                    recordTurn(evidence, "question");
                }
                // A question is a healthy wait, not an ending: the agent is alive and blocked on
                // an answer. Take delivery of it and keep watching, so an answer given while
                // this controller is still running lets the same worker finish here — and so
                // the FIFO advances instead of replaying this question to every later attach.
                acknowledge(dispatch, last.deliveryId(), evidence);
                lastQuestion = new OrcaSettlement.Outcome(last.kind(), last.taskId(),
                        last.dispatchId(), last.type(), last.payload(), last.reason(),
                        null, last.messageId());
                evidence.put("question_pending", last.messageId());
                continue;
            }
            // Agent Dashboard can prove that the exact native agent session is parked on a
            // prompt only a person can answer. That wait is healthy: keep this controller
            // alive so answering it from Orca/mobile lets the same worker continue. Remember
            // the last proven state only so a wall-clock expiry says "needs a person" rather
            // than pretending the agent merely ran out of time. An absent observation never
            // clears a prior wait; only Orca's explicit null does.
            Duration observationBudget = bounded(timeLeft.get(), OBSERVATION_TIMEOUT);
            if (dispatchId != null && positive(observationBudget)) {
                OrcaClient.Rpc worker = show(root, dispatchId, observationBudget);
                OrcaSettlement.Outcome observed = OrcaSettlement.fromWorkerShow(worker.envelope());
                latestAgentObservation = observed;
                evidence.put("agent_wait_observation", observed.reason());
                if (observed.kind() == OrcaSettlement.Kind.QUESTION) {
                    lastProvenAgentWait = observed;
                    evidence.put("agent_wait", observed.payload());
                } else if (observed.kind() == OrcaSettlement.Kind.CHECKPOINT
                        && "agent_wait_checked_clear".equals(observed.reason())) {
                    lastProvenAgentWait = null;
                    evidence.remove("agent_wait");
                }
                if (turnPending) {
                    // Until a turn is seen, the screen is still a start screen and is read for
                    // what it waits on. Not after: a working agent's screen is its work, and a
                    // file that says "update now" is not a prompt the agent is stuck on.
                    String seen = OrcaSettlement.turnObserved(worker.envelope());
                    if (seen != null) {
                        turnPending = false;
                        recordTurn(evidence, seen);
                    } else if (noteScreen(nestedString(worker.result(), "terminal", "preview"),
                            evidence) != null) {
                        return new OrcaSettlement.Outcome(OrcaSettlement.Kind.UNKNOWN, taskId,
                                dispatchId, "turn_start", Map.of("blocked_on",
                                        evidence.get("agent_blocked_on")), TURN_START_BLOCKED);
                    }
                }
            }
            Duration showBudget = bounded(timeLeft.get(), OBSERVATION_TIMEOUT);
            if (!positive(showBudget)) break;
            OrcaClient.Rpc shown = orca.invoke(root, showBudget,
                    List.of("orchestration", "dispatch-show", "--task", taskId,
                            "--from", dispatch.coordinatorHandle()));
            OrcaSettlement.Outcome shownOutcome = OrcaSettlement.fromDispatchShow(shown.envelope());
            if (terminal(shownOutcome)) {
                return shownOutcome;
            }
            last = shownOutcome.kind() == OrcaSettlement.Kind.UNKNOWN ? last : shownOutcome;
        }
        evidence.put("wait_exhausted", true);
        // An unanswered question outranks every other reason the clock ran out: the worker did
        // not stall, it asked something and nobody replied inside the role's budget.
        if (lastQuestion != null) return lastQuestion;
        if (latestAgentObservation != null
                && latestAgentObservation.kind() == OrcaSettlement.Kind.QUESTION) {
            return latestAgentObservation;
        }
        if (lastProvenAgentWait != null && latestAgentObservation != null
                && latestAgentObservation.kind() == OrcaSettlement.Kind.UNKNOWN) {
            Map<String, Object> payload = new LinkedHashMap<>(lastProvenAgentWait.payload());
            payload.put("latest_observation", latestAgentObservation.reason());
            return new OrcaSettlement.Outcome(OrcaSettlement.Kind.QUESTION, taskId, dispatchId,
                    "agent_wait", payload, "agent_wait_last_seen_unverifiable");
        }
        return last;
    }

    private static boolean terminal(OrcaSettlement.Outcome settlement) {
        return switch (settlement.kind()) {
            case COMPLETED, ESCALATED, FAILED -> true;
            default -> false;
        };
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static Duration bounded(Duration remaining, Duration cap) {
        if (!positive(remaining)) return Duration.ZERO;
        return remaining.compareTo(cap) < 0 ? remaining : cap;
    }

    OrcaClient.Rpc show(Path root, String dispatchId) throws Exception {
        return show(root, dispatchId, CONTROL_TIMEOUT);
    }

    // The compact Orca view omits the durable launch receipt and worker observation.
    private OrcaClient.Rpc show(Path root, String dispatchId, Duration timeout) throws Exception {
        return orca.invoke(root, timeout, List.of("orchestration", "worker-show", "--dispatch", dispatchId, "--full"));
    }

    OrcaClient.Rpc readTranscript(Path root, String dispatchId, Duration timeout) {
        if (dispatchId == null || timeout.isZero() || timeout.isNegative()) return null;
        try {
            return orca.invoke(root, bounded(timeout, CONTROL_TIMEOUT),
                    List.of("orchestration", "worker-read", "--dispatch", dispatchId, "--source", "auto"));
        } catch (Exception ignored) {
            return null;
        }
    }

    boolean releaseWorker(Path root, String dispatchId, Map<String, Object> evidence) throws Exception {
        OrcaClient.Rpc released = orca.invoke(root, SETTLEMENT_RPC_TIMEOUT,
                List.of("orchestration", "worker-release", "--dispatch", dispatchId));
        evidence.put("worker_release_ok", released.ok());
        evidence.put("worker_release", released.result());
        if (!released.ok()) {
            evidence.put("worker_release_error_code", OrcaSettlement.errorCode(released.envelope()));
            evidence.put("worker_release_stderr", tail(released.stderr(), 1000));
        }
        return released.ok();
    }

    boolean acknowledge(Dispatch dispatch, String deliveryId, Map<String, Object> evidence) {
        if (deliveryId == null) return true;
        try {
            List<String> args = new ArrayList<>(List.of(
                    "orchestration", "check", "--ack", deliveryId,
                    "--terminal", dispatch.coordinatorHandle()));
            if (dispatch.runId() != null) args.addAll(List.of("--run", dispatch.runId()));
            OrcaClient.Rpc acknowledged = orca.invoke(dispatch.root(), CONTROL_TIMEOUT, args);
            evidence.put("delivery_ack_ok", acknowledged.ok());
            return acknowledged.ok();
        } catch (Exception failure) {
            evidence.put("delivery_ack_ok", false);
            evidence.put("delivery_ack_error", String.valueOf(failure.getMessage()));
            return false;
        }
    }

    /**
     * The agent tab's last screen for a worker whose first turn never started, and what it was
     * waiting on when Overseer recognises it.
     *
     * Measured 2026-09-22 on Orca 1.4.207: a Claude reviewer in a fresh worktree sat on Claude
     * Code's once-per-repository trust question until Overseer fenced it, and the report said
     * only `role_orca_start_failed` with Orca's receipt. The question itself was in the
     * worker's `terminal.preview`, and it names the one click that fixes it.
     */
    String recordAgentScreen(Path root, String dispatchId, boolean watchTurn,
                             Map<String, Object> evidence) {
        if (dispatchId == null) return null;
        try {
            OrcaClient.Rpc shown = show(root, dispatchId);
            String seen = watchTurn ? OrcaSettlement.turnObserved(shown.envelope()) : null;
            if (seen != null) {
                recordTurn(evidence, seen);
                return seen;
            }
            noteScreen(nestedString(shown.result(), "terminal", "preview"), evidence);
        } catch (Exception unreadable) {
            // The screen is a diagnosis, not a condition of fencing; the stop goes ahead.
        }
        return null;
    }

    private static void recordTurn(Map<String, Object> evidence, String observation) {
        evidence.put("worker_turn", "observed");
        evidence.put("worker_turn_observed_by", observation);
        evidence.put("worker_turn_observed_at", Instant.now().toString());
    }

    /** Keep a start screen's tail and name what it waits on; returns that name, or null. */
    private static String noteScreen(String screen, Map<String, Object> evidence) {
        if (screen == null || screen.isBlank()) return null;
        evidence.put("agent_screen_tail", tail(screen, 1500));
        String blockedOn = blockedOn(screen);
        if (blockedOn == null) return null;
        evidence.put("agent_blocked_on", blockedOn);
        evidence.put("resolution", "folder_trust".equals(blockedOn)
                ? "the agent CLI stopped on its first-run question for this repository "
                        + "(Claude Code: \"Is this a project you created or one you trust?\"). "
                        + "Answer Yes once in an Orca tab in this worktree; Claude records it "
                        + "for the whole repository, so later worktrees do not ask again"
                : "the agent CLI asked to update before it would start. Choose Skip or update "
                        + "it once in an Orca tab, then retry");
        return blockedOn;
    }

    /** What a first-run screen is waiting on, or null when Overseer does not recognise it. */
    static String blockedOn(String screen) {
        if (screen == null) return null;
        String lower = screen.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("trust this folder") || lower.contains("one you trust")
                || lower.contains("do you trust")) {
            return "folder_trust";
        }
        if (lower.contains("update available") || lower.contains("update now")) {
            return "cli_update_prompt";
        }
        return null;
    }

    /**
     * Fence one dispatch, and say why if it could not be fenced.
     *
     * A failed fence is the one outcome that blocks every later stage in this worktree, so the
     * operator is owed more than a false: the first attempt at this recorded only the boolean,
     * and a live timeout then reported an unaccounted lifecycle with nothing to act on.
     */
    boolean stopWorker(Path root, String dispatchId, Map<String, Object> evidence) {
        try {
            OrcaClient.Rpc stopped = orca.invoke(root, SETTLEMENT_RPC_TIMEOUT,
                    List.of("orchestration", "worker-stop", "--dispatch", dispatchId));
            evidence.put("worker_stop_ok", stopped.ok());
            evidence.put("worker_stop", stopped.result());
            if (stopped.ok()) {
                closeWorkerTab(root, dispatchId, evidence);
                return true;
            }
            evidence.put("worker_stop_exit_code", (long) stopped.exitCode());
            evidence.put("worker_stop_timed_out", stopped.timedOut());
            evidence.put("worker_stop_error_code", OrcaSettlement.errorCode(stopped.envelope()));
            evidence.put("worker_stop_stderr", tail(stopped.stderr(), 1000));
            evidence.put("worker_stop_stdout", tail(stopped.stdout(), 1000));
            boolean fenced = fencedAnyway(root, dispatchId, evidence);
            if (fenced) closeWorkerTab(root, dispatchId, evidence);
            return fenced;
        } catch (Exception failure) {
            evidence.put("worker_stop_ok", false);
            evidence.put("worker_stop_error", String.valueOf(failure.getMessage()));
            return false;
        }
    }

    /**
     * Ask the dispatch itself whether it is still running, after Orca refused to stop it.
     *
     * A refusal is not a failure to fence. A worker that timed out and had its terminal closed
     * answers {@code dispatch_inactive} — Orca declining to stop something that is already
     * stopped — and treating that as an unaccounted lifecycle blocks every later stage in the
     * worktree over a worker that is provably gone. Measured on Orca 1.4.196: the timed-out
     * dispatch reported {@code status: failed}, {@code stage: process_exited},
     * {@code observation.status: exited}, and {@code worker-list} showed nothing in flight.
     *
     * The reverse still fails closed. If the probe says the dispatch is live, or says nothing
     * usable, the fence is unproven and the caller must not let anything else share the tree.
     */
    private boolean fencedAnyway(Path root, String dispatchId, Map<String, Object> evidence) {
        try {
            OrcaClient.Rpc shown = show(root, dispatchId);
            OrcaRecovery.Assessment after = OrcaRecovery.assess(shown.envelope(), dispatchId);
            boolean fenced = after.verdict() == OrcaRecovery.Verdict.SETTLED
                    || after.verdict() == OrcaRecovery.Verdict.GONE;
            Map<String, Object> verification = new LinkedHashMap<>();
            verification.put("method", "worker_show_after_refused_stop");
            verification.put("verdict", after.verdict().name().toLowerCase());
            verification.put("reason", after.reason());
            verification.put("fenced", fenced);
            evidence.put("worker_stop_verification", verification);
            return fenced;
        } catch (Exception unreachable) {
            evidence.put("worker_stop_verification", Map.of(
                    "method", "worker_show_after_refused_stop",
                    "fenced", false,
                    "error", String.valueOf(unreachable.getMessage())));
            return false;
        }
    }

    /**
     * Close the agent tab a fence just stopped.
     *
     * {@code worker-stop} ends the dispatch and leaves the terminal. Measured 2026-09-26
     * on Orca 1.4.212: a Codex worker fenced on its update screen stayed open as a
     * {@code pwsh.exe} tab sitting on "Update available". The coordinator tab is closed
     * separately; this is the worker's own tab.
     */
    private void closeWorkerTab(Path root, String dispatchId, Map<String, Object> evidence) {
        if (dispatchId == null) return;
        try {
            OrcaClient.Rpc shown = show(root, dispatchId);
            String handle = OrcaSettlement.agentTerminalHandle(shown.envelope());
            if (handle == null) {
                evidence.put("worker_tab_close_ok", false);
                evidence.put("worker_tab_close_error", "worker-show named no agent terminal");
                return;
            }
            OrcaClient.Rpc closed = closeTab(root, handle);
            evidence.put("worker_tab_close_ok", closed.ok());
            evidence.put("worker_tab", handle);
        } catch (Exception failure) {
            evidence.put("worker_tab_close_ok", false);
            evidence.put("worker_tab_close_error", String.valueOf(failure.getMessage()));
        }
    }

    void closeCoordinator(Path root, String handle, Map<String, Object> evidence) {
        try {
            OrcaClient.Rpc closed = closeTab(root, handle);
            evidence.put("coordinator_close_ok", closed.ok());
        } catch (Exception failure) {
            evidence.put("coordinator_close_ok", false);
            evidence.put("coordinator_close_error", String.valueOf(failure.getMessage()));
        }
    }

    private OrcaClient.Rpc closeTab(Path root, String handle) throws Exception {
        return orca.invoke(root, CONTROL_TIMEOUT, List.of("terminal", "close", "--terminal", handle, "--tab"));
    }
}
