package dev.oseer.cli;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.json.Json;
import dev.oseer.run.TaskLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Continuation {
    private Continuation() {}

    /**
     * The substitutions a human authorised on an earlier run, read back from that run's own
     * decision record.
     *
     * The authorisation is the resolved decision, not a flag: a flag would let anyone grant
     * the switch, and a persisted "codex is out" would go stale the moment the quota window
     * rolled over. Naming a prior run ties the permission to one recorded human choice, for
     * one role, to one named profile.
     */
    public record Carried(Map<String, String> failover, TaskLoop.Continuation continuation) {}

    public static Carried continuation(Path root, String priorRunId) throws Exception {
        if (priorRunId == null) return new Carried(Map.of(), TaskLoop.Continuation.NONE);
        HumanDecision decision = new ApprovalStore(root).read(priorRunId);
        Map<String, String> priorSubstitutions = new LinkedHashMap<>();
        Path priorFile = root.resolve(decision.summaryPath());
        if (Files.isRegularFile(priorFile)) {
            Object previous = Json.parseObject(Files.readString(priorFile)).get("authorized_failover");
            if (previous instanceof Map<?, ?> map) map.forEach((key, value) ->
                    priorSubstitutions.put(String.valueOf(key), String.valueOf(value)));
        }
        // A rejection is feedback, not an authorisation: it grants nothing, it only tells the
        // next implementer what a person already objected to. Refusing to carry it meant the
        // one piece of human judgement in the loop was the one piece nobody downstream saw.
        if (decision.kind() == HumanDecision.Kind.SUCCESS && "reject".equals(decision.decision())) {
            if (decision.note() == null || decision.note().isBlank()) {
                throw new IllegalArgumentException("--continue " + priorRunId + " was rejected "
                        + "without a note, so there is nothing to carry. Record why with "
                        + "`oseer approve " + priorRunId + " --decision reject --note \"...\"`, "
                        + "or run without --continue");
            }
            return new Carried(Map.of(),
                    new TaskLoop.Continuation(priorRunId, decision.note()));
        }
        // A failure a person answered with `retry` is a request to run the same task again.
        // When that failure was never about the work, the roles that already passed judged
        // the very tree that is still sitting there, and TaskLoop re-checks that before it
        // believes any of it.
        if (decision.kind() == HumanDecision.Kind.FAILURE && "retry".equals(decision.decision())) {
            return new Carried(priorSubstitutions,
                    new TaskLoop.Continuation(priorRunId, null, true));
        }
        // The operator (or whoever answered the gate) dealt with the blocker and wants the
        // next run of this task now. Writers carry; verdicts do not — a contract or scope
        // edit is why advance exists, and those change what a reading is about.
        if ((decision.kind() == HumanDecision.Kind.FAILURE && "advance".equals(decision.decision()))
                || (decision.kind() == HumanDecision.Kind.CONTRACT_CHANGE && "apply".equals(decision.decision()))) {
            return new Carried(priorSubstitutions,
                    new TaskLoop.Continuation(priorRunId, decision.note(), false));
        }
        // A preparation stop: replan re-runs the planner; proceed accepts the objected
        // contract and runs writers. Neither reuses verdicts — there were none.
        if (decision.kind() == HumanDecision.Kind.PREPARATION
                && ("replan".equals(decision.decision()) || "proceed".equals(decision.decision()))) {
            return new Carried(priorSubstitutions,
                    new TaskLoop.Continuation(priorRunId, decision.note(), false));
        }
        if (decision.kind() != HumanDecision.Kind.FAILOVER) {
            throw new IllegalArgumentException("--continue " + priorRunId + " names a "
                    + decision.kind().jsonValue() + " decision that is neither a rejection, a "
                    + "retry, an advance, nor a preparation replan/proceed; only a failover "
                    + "decision authorises a vendor substitution");
        }
        // A failover answered `retry` is the person waiting out the quota window with the
        // same roster: no substitution is authorised, and the spent stop was never about the
        // work, so the verdicts already reached are kept.
        if (decision.state() == HumanDecision.State.RESOLVED && "retry".equals(decision.decision())) {
            return new Carried(priorSubstitutions, new TaskLoop.Continuation(priorRunId, null, true));
        }
        if (decision.state() != HumanDecision.State.RESOLVED || !"switch".equals(decision.decision())) {
            throw new IllegalArgumentException("--continue " + priorRunId + " has not been "
                    + "resolved as 'switch' or 'retry' (state=" + decision.state().jsonValue()
                    + ", decision=" + decision.decision() + "); record the choice first with "
                    + "`oseer approve " + priorRunId + " --decision switch|retry`");
        }
        Path summary = root.resolve(decision.summaryPath());
        Map<String, Object> priorSummary = Json.parseObject(Files.readString(summary));
        Object pending = priorSummary.get("failover_pending");
        if (!(pending instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("--continue " + priorRunId + ": that run's summary "
                    + "names no pending failover, so there is nothing to authorise");
        }
        Object role = map.get("role");
        Object profile = map.get("to_profile");
        if (role == null || profile == null) {
            throw new IllegalArgumentException("--continue " + priorRunId
                    + ": the recorded failover names no role and profile");
        }
        // A spent subscription is not a verdict, so the switch keeps what was already judged.
        // It used to carry nothing: run p2-planner-3 paid its writer thirty minutes to redo a
        // candidate it had already written, and both readers again, because the only thing
        // that changed was who would read next. TaskLoop still re-checks the tree, the
        // acceptance and each stage's roster; the stage whose vendor ran out has no passing
        // row and is dispatched to the profile the person just chose.
        Map<String, String> substitutions = new LinkedHashMap<>();
        if (priorSummary.get("authorized_failover") instanceof Map<?, ?> authorized) {
            authorized.forEach((key, value) -> substitutions.put(String.valueOf(key), String.valueOf(value)));
        }
        Object stage = map.get("stage");
        substitutions.put(stage instanceof String text ? text : String.valueOf(role), String.valueOf(profile));
        return new Carried(substitutions,
                new TaskLoop.Continuation(priorRunId, null, true));
    }
}
