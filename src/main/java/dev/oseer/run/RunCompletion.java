package dev.oseer.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Separates the three questions a summary used to answer with {@code ok: false}:
 * whether a reader passed the candidate, whether the chain finished, and how
 * independent the least independent reading was.
 */
final class RunCompletion {
    private RunCompletion() {}


    /**
     * Three different questions the summary used to answer with one word.
     *
     * `ok: false` meant, indiscriminately, that the investigation found nothing, that the
     * candidate was rejected by a reviewer, and that the chain ran out of room before it could
     * finish — and an operator reading it could not tell which. Measured on a live run: three
     * reviews, the last of them a clean pass with no blocking finding, a good candidate in the
     * worktree, and a summary that said `budget_exhausted` and nothing else. The work was
     * sound and the report gave nobody a reason to believe it.
     *
     * So they are separated. Whether the candidate has been judged and survived is
     * `candidate_review_passed`. Whether the declared chain got to the end is
     * `workflow_incomplete` plus the stages it still owes. Neither implies the other, and a
     * run can legitimately be a pass on the first and a no on the second.
     */
    @SuppressWarnings("unchecked")
    static void describeCompletion(Map<String, Object> summary) {
        List<String> declared = new ArrayList<>();
        if (summary.get("workflow") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> stage && stage.get("stage") != null) {
                    declared.add(String.valueOf(stage.get("stage")));
                }
            }
        }
        java.util.Set<String> completed = new java.util.LinkedHashSet<>();
        if (summary.get("completed_stages") instanceof List<?> rows) {
            for (Object row : rows) completed.add(String.valueOf(row));
        }
        java.util.Set<String> skipped = new java.util.LinkedHashSet<>();
        for (String key : List.of("skipped_stages", "inapplicable_stages")) {
            if (!(summary.get(key) instanceof List<?> rows)) continue;
            for (Object row : rows) {
                if (row instanceof Map<?, ?> entry) skipped.add(String.valueOf(entry.get("stage")));
            }
        }
        java.util.Set<String> attempted = new java.util.LinkedHashSet<>();
        if (summary.get("steps") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> step && step.get("stage") != null) {
                    attempted.add(String.valueOf(step.get("stage")));
                }
            }
        }
        java.util.Set<String> stale = new java.util.LinkedHashSet<>();
        if (summary.get("stages_judging_an_earlier_candidate") instanceof List<?> rows) {
            for (Object row : rows) stale.add(String.valueOf(row));
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        for (String stage : declared) {
            if (completed.contains(stage) || skipped.contains(stage)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            // Three outstanding states, not one. A stage that passed and was then overtaken by
            // a repair has a verdict on disk about a tree that no longer exists; a stage that
            // ran and objected has evidence to read; a stage that never got a turn has a
            // ceiling to raise. Collapsing them sends an operator to the wrong one of three.
            row.put("reason", stale.contains(stage) ? "judged_an_earlier_candidate"
                    : attempted.contains(stage) ? "did_not_pass" : "not_reached");
            pending.add(row);
        }
        summary.put("pending_stages", pending);
        summary.put("workflow_incomplete", !pending.isEmpty());

        long blockers = 0;
        boolean anyJudged = false;
        boolean allClean = true;
        if (summary.get("review_coverage") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> entry)) continue;
                anyJudged = true;
                long found = entry.get("blocking_findings") instanceof Number number
                        ? number.longValue() : 0;
                blockers += found;
                if (found > 0 || !Boolean.TRUE.equals(entry.get("ok"))) allClean = false;
            }
        }
        summary.put("open_blocking_findings", blockers);
        // Never true by default. A chain whose judging stages were all skipped or never
        // reached has not passed review; it has not had one.
        summary.put("candidate_review_passed", anyJudged && allClean);
        // The weakest label among the readings, because a candidate is only as independently
        // reviewed as its least independent reading. `independent` is never the default: it
        // is what every reading was, or the answer is something else.
        String weakest = null;
        if (summary.get("review_coverage") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> entry)) continue;
                String label = entry.get("assurance") instanceof String text ? text : "unproven";
                weakest = weaker(weakest, label);
            }
        }
        if (weakest != null) summary.put("review_assurance", weakest);
        liftFailovers(summary);
    }

    /**
     * Switches and refused switches, on the summary itself.
     *
     * The step row names the profile that finished. A run that left grok for a paid API
     * then showed only that API. The from→to list is what an operator can see without
     * opening the role report.
     */
    private static void liftFailovers(Map<String, Object> summary) {
        List<Object> switches = new ArrayList<>();
        List<Object> blocked = new ArrayList<>();
        if (summary.get("steps") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> step)) continue;
                if (step.get("role_failovers") instanceof List<?> list) switches.addAll(list);
                if (step.get("failover_blocked") instanceof List<?> list) blocked.addAll(list);
            }
        }
        if (switches.isEmpty()) summary.remove("role_failovers");
        else summary.put("role_failovers", List.copyOf(switches));
        if (blocked.isEmpty()) summary.remove("failover_blocked");
        else summary.put("failover_blocked", List.copyOf(blocked));
    }

    /** Order of assurance labels, strongest first; the weaker of two wins. */
    private static final List<String> ASSURANCE_ORDER = List.of(
            "independent", "same_vendor_peer", "peer_review", "coauthor", "unproven");

    private static String weaker(String current, String candidate) {
        if (current == null) return candidate;
        int a = ASSURANCE_ORDER.indexOf(current);
        int b = ASSURANCE_ORDER.indexOf(candidate);
        if (a < 0) return current;
        if (b < 0) return candidate;
        return b > a ? candidate : current;
    }

}
