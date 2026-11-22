package dev.oseer.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Questions the step list can answer without the loop that wrote it. */
final class StepQueries {
    private StepQueries() {}

    /**
     * A confirm-mode question about a call that produced no artifact, as opposed to a
     * spent subscription. The two share a decision kind and must not share an exclusion:
     * a network error is not a quota.
     */
    static boolean vendorFailureSwitch(Map<?, ?> pending) {
        String cause = String.valueOf(pending.get("cause"));
        return "vendor_call_failed".equals(cause)
                || "role_timeout".equals(cause)
                || "role_stalled".equals(cause)
                || "artifact_invalid".equals(cause)
                || "vendor_unavailable".equals(cause);
    }

    /**
     * Judging stages whose last run predates the last change to the code.
     *
     * A role that reads a diff and passes it has judged one tree, not the task. If an
     * implementer runs afterwards — a browser fix round, a visual finding — the candidate a
     * human is then asked to accept contains a diff that role never saw. Declaring
     * `recheck_after_fix: true` closes it; this reports the gap for a workflow that chooses
     * not to, so the human is told rather than left to notice.
     */
    static List<String> staleJudgements(List<Map<String, Object>> steps) {
        long lastWrite = -1;
        Map<String, Long> lastJudged = new LinkedHashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            String label = String.valueOf(steps.get(index).get("step"));
            if ("implementer".equals(label)) lastWrite = index;
            else if (!"gates".equals(label) && steps.get(index).get("profile") != null) {
                lastJudged.put(label, (long) index);
            }
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastJudged.entrySet()) {
            if (entry.getValue() < lastWrite) stale.add(entry.getKey());
        }
        return stale;
    }

    /** The stage labels that ran, in order, each named once however often it repeated. */
    static List<String> executedStages(List<Map<String, Object>> steps) {
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            if (Boolean.TRUE.equals(step.get("reused"))) continue;
            String label = String.valueOf(step.get("step"));
            if (!labels.contains(label)) labels.add(label);
        }
        return labels.isEmpty() ? List.of("no stage") : labels;
    }

    /** The failover awaiting a human, if the run stopped for one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> pendingFailover(List<Map<String, Object>> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            Object pending = steps.get(index).get("failover_pending");
            if (pending instanceof Map<?, ?> map) return (Map<String, Object>) map;
        }
        return null;
    }

    /** What the human is actually being asked, in one line, with both vendors named. */
    static String failoverQuestion(Map<String, Object> pending) {
        String cause = String.valueOf(pending.get("cause"));
        String what = "role_quota_exhausted".equals(cause)
                ? "reported a spent subscription"
                : "produced no artifact (" + cause + ")";
        return "role " + pending.get("role") + ": " + pending.get("from_profile")
                + " (" + pending.get("from_vendor") + ") " + what + ". "
                + "Switch to " + pending.get("to_profile") + " (" + pending.get("to_vendor")
                + ")? " + pending.get("independence_after_switch");
    }

    /** The repair branches the plan costed, for a preview that has to print them. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> recoveryBranches(Map<String, Object> summary) {
        if (!(summary.get("budget_plan") instanceof Map<?, ?> plan)) return List.of();
        if (!(plan.get("recovery_branches") instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> branches = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> branch) branches.add((Map<String, Object>) branch);
        }
        return branches;
    }

}
