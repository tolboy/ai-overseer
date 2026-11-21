package dev.oseer.cli;

import dev.oseer.approval.ApprovalException;
import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.execution.orca.OrcaDecisionGate;
import dev.oseer.execution.orca.OrcaLifecycle;
import dev.oseer.json.Json;
import dev.oseer.run.NextStep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GateWait {
    private static final Duration INITIAL_POLL_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_POLL_DELAY = Duration.ofSeconds(10);
    private static final double BACKOFF_MULTIPLIER = 1.5;

    private GateWait() {}

    record MappedGate(HumanDecision pending, OrcaLifecycle.Gate gate,
                      String choice,
                      Map<String, Object> gateReport) {}

    record GatePoll(MappedGate mapped, Map<String, Object> timeout) {}

    /**
     * Read the published gate and map its free-text resolution onto one of Overseer's
     * options. {@code gate_pending} and {@code gate_unreadable} wait, with 5 s then
     * ×1.5 capped at 10 s, until {@code waitMinutes}; everything else fails on the
     * first poll, as a plain {@code --from-orca} already did.
     */
    public static GatePoll pollGate(Path root, String runId, ApprovalStore store, ApproveEnv env,
                             Integer waitMinutes) throws Exception {
        long start = env.clock.nowMillis();
        long deadline = waitMinutes == null ? start : start + Duration.ofMinutes(waitMinutes).toMillis();
        // The wait never outlives the gate's own life: polling past it would only find an
        // answer the store then refuses. The deadline is cut, the reason is said.
        try {
            HumanDecision pending = store.read(runId);
            if (pending.expiresAt() != null) {
                deadline = Math.min(deadline, pending.expiresAt().toEpochMilli());
            }
        } catch (Exception unreadable) {
            // The poll below reports an unreadable decision on its own terms.
        }
        long delayMs = INITIAL_POLL_DELAY.toMillis();
        int polls = 0;
        ApprovalException last = null;
        while (true) {
            polls++;
            try {
                return new GatePoll(readMappedGate(root, runId, store, env), null);
            } catch (ApprovalException failure) {
                if (!"gate_pending".equals(failure.code()) && !"gate_unreadable".equals(failure.code())) {
                    throw failure;
                }
                last = failure;
            }
            long now = env.clock.nowMillis();
            if (waitMinutes == null) throw last;
            if (now >= deadline) {
                return new GatePoll(null, gateTimeout(root, runId, store, start, now, polls, last));
            }
            long remaining = deadline - now;
            long sleep = Math.min(delayMs, remaining);
            if (sleep <= 0) {
                return new GatePoll(null, gateTimeout(root, runId, store, start, now, polls, last));
            }
            try {
                env.sleeper.sleep(sleep);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new GatePoll(null, gateTimeout(root, runId, store, start,
                        env.clock.nowMillis(), polls, last));
            }
            delayMs = Math.min(Math.round(delayMs * BACKOFF_MULTIPLIER), MAX_POLL_DELAY.toMillis());
        }
    }

    /**
     * One attempt: lifecycle, version token, Orca read, option mapping. The candidate
     * fingerprint and the store's duplicate protection still run on the answer that is
     * finally imported, not here.
     */
    private static MappedGate readMappedGate(Path root, String runId, ApprovalStore store, ApproveEnv env)
            throws Exception {
        HumanDecision pending = store.read(runId);
        if (pending.state() != HumanDecision.State.PENDING) {
            throw new ApprovalException("duplicate_decision",
                    "run " + runId + " is already resolved");
        }
        OrcaLifecycle.Gate gate;
        try {
            gate = new OrcaLifecycle(root, runId).read().gate();
        } catch (IOException unreadable) {
            // A record Overseer cannot read is not a record that says no gate exists.
            throw new ApprovalException("orca_lifecycle_unreadable",
                    "cannot read the Orca record for run " + runId + ": "
                            + unreadable.getMessage());
        }
        if (gate == null) {
            throw new ApprovalException("no_orca_gate",
                    "run " + runId + " never published a decision gate to Orca");
        }
        // The version token. A gate answers the question it was published about; if the
        // pending decision has moved since, the answer is about something else.
        if (!gate.decisionUpdatedAt().equals(pending.updatedAt().toString())) {
            throw new ApprovalException("stale_decision",
                    "gate " + gate.gateId() + " was published for an older version of run "
                            + runId);
        }
        OrcaDecisionGate.Answer answer = env.gates.read(root, gate);
        if (answer == null) {
            throw new ApprovalException("gate_unreadable",
                    "Orca did not return gate " + gate.gateId());
        }
        if (!answer.resolved()) {
            throw new ApprovalException("gate_pending",
                    "gate " + gate.gateId() + " has not been answered yet");
        }
        String choice = OrcaDecisionGate.choose(answer.resolution(), pending.options());
        if (choice == null) {
            // Orca accepts free text as a resolution. Overseer accepts one of its own
            // options and nothing else, because guessing what a sentence meant is the
            // judgement being delegated in the first place.
            throw new ApprovalException("gate_resolution_unmapped",
                    "gate " + gate.gateId() + " was resolved as "
                            + Json.write(answer.resolution()) + ", which is not one of "
                            + pending.options());
        }
        Map<String, Object> gateReport = new LinkedHashMap<>();
        gateReport.put("gate_id", gate.gateId());
        gateReport.put("task_id", gate.taskId());
        gateReport.put("orca_run_id", gate.orcaRunId());
        gateReport.put("resolution", answer.resolution());
        return new MappedGate(pending, gate, choice, gateReport);
    }

    private static Map<String, Object> gateTimeout(Path root, String runId, ApprovalStore store,
                                                   long startMillis, long nowMillis, int polls,
                                                   ApprovalException last) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("code", "gate_pending");
        try {
            HumanDecision pending = store.read(runId);
            if (pending.expiresAt() != null) {
                result.put("gate_expires_at", pending.expiresAt().toString());
                result.put("gate_expired", pending.expiredAt(Instant.ofEpochMilli(nowMillis)));
            }
        } catch (Exception unreadable) {
            // The timeout report stands without it.
        }
        result.put("waited_seconds", Math.max(0L, (nowMillis - startMillis) / 1000L));
        result.put("polls", (long) polls);
        result.put("last_error", last == null ? "gate_pending" : last.getMessage());
        result.put("next_step", timeoutNextStep(root, runId, store));
        result.put("project", root.toString());
        result.put("lands", false);
        result.put("message", "no answer is never accept or reject; the decision was not changed");
        return result;
    }

    private static Object timeoutNextStep(Path root, String runId, ApprovalStore store) {
        try {
            HumanDecision pending = store.read(runId);
            Path summary = Path.of(pending.summaryPath());
            if (!summary.isAbsolute()) summary = root.resolve(summary);
            if (Files.isRegularFile(summary)) {
                Map<String, Object> body = Json.parseObject(Files.readString(summary));
                if (body.get("next_step") != null) return body.get("next_step");
                Object reason = body.get("reason");
                if (reason instanceof String text) return NextStep.of(text, body, root);
            }
        } catch (Exception ignored) {
            // A timeout still has to name a next step even if the summary cannot be read.
        }
        return "oseer approve " + runId + " --from-orca --wait-minutes <N>";
    }

    static Map<String, Object> skippedGateWait(String reason) {
        Map<String, Object> skip = new LinkedHashMap<>();
        skip.put("skipped", true);
        skip.put("reason", reason);
        skip.put("detail", "the wait imports one Orca gate answer and never starts a run");
        return skip;
    }
}
