package dev.oseer.run;

import dev.oseer.role.RoleRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One role outcome, as the step row a continuation later reads back. */
final class StepRecord {
    private StepRecord() {}

    static void record(List<Map<String, Object>> steps, RunBudget budget, String role, int attempt,
                        RoleRunner.Outcome step) {
        record(steps, budget, role, attempt, step, null);
    }

    /**
     * @param stage the workflow stage that dispatched this role, when there was one. It is
     *              recorded so a reader of the summary can tell two stages of the same role
     *              apart — and so `oseer report` looks for the evidence where the stage
     *              actually wrote it rather than where the role alone would suggest.
     */
    static void record(List<Map<String, Object>> steps, RunBudget budget, String role, int attempt,
                        RoleRunner.Outcome step, String stage) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", role);
        if (stage != null) entry.put("stage", stage);
        entry.put("attempt", (long) attempt);
        entry.put("ok", step.ok());
        entry.put("code", step.code());
        entry.put("profile", step.profile());
        entry.put("vendor", step.vendor());
        entry.put("rejected_profiles", step.rejected());
        if (step.details() != null) {
            // A role may have dispatched more than one vendor. Charging only the last one
            // would under-report a run that failed over, which is the run most worth costing.
            Object attempts = step.details().get("vendor_attempts");
            if (!budget.finishSettledRole()) {
                if (attempts instanceof List<?> list && !list.isEmpty()) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) budget.account(map.get("cost_usd"));
                    }
                } else {
                    budget.account(step.details().get("cost_usd"));
                }
            }
            if (attempts instanceof List<?> list && !list.isEmpty()) {
                entry.put("vendor_attempts", attempts);
            }
            if (step.details().get("failover_pending") != null) {
                entry.put("failover_pending", step.details().get("failover_pending"));
            }
            // Carried onto the row rather than left in the role report, because the row is
            // what a continuation reads when deciding whether this verdict may stand.
            if (step.details().get("role_contract") != null) {
                entry.put("role_contract", step.details().get("role_contract"));
            }
            entry.put("cost_usd", step.details().get("cost_usd"));
            entry.put("attempts_cost_usd", step.details().get("attempts_cost_usd"));
            // The pictures a self-acquiring visual role was verified to have taken, as digests.
            if (step.details().get("image_evidence") != null) {
                entry.put("evidence", step.details().get("evidence"));
                entry.put("image_evidence", step.details().get("image_evidence"));
            }
            if (step.details().get("screenshots_refused") != null) {
                entry.put("screenshots_refused", step.details().get("screenshots_refused"));
            }
            if (step.details().get("independence") != null) {
                entry.put("independence", step.details().get("independence"));
            }
            entry.put("failed_over_from", step.details().get("failed_over_from"));
            if (step.details().get("role_failovers") != null) {
                entry.put("role_failovers", step.details().get("role_failovers"));
            }
            // A refused switch is not a switch. The summary has to name both profiles;
            // the successor alone is what a silent failover used to leave behind.
            if (step.details().get("failover_blocked") != null) {
                entry.put("failover_blocked", step.details().get("failover_blocked"));
            }
            if (step.details().get("failover_notice") != null) {
                entry.put("failover_notice", step.details().get("failover_notice"));
            }
            entry.put("artifact_path", step.details().get("artifact_path"));
            if ("role_quota_exhausted".equals(step.code())) {
                entry.put("quota", step.details().get("quota"));
                entry.put("exhausted_profiles", step.details().get("exhausted_profiles"));
            }
        }
        steps.add(entry);
    }

}
