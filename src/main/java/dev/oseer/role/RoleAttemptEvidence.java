package dev.oseer.role;

import dev.oseer.config.Profile;
import dev.oseer.execution.RoleExecutor;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.run.Progress;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projects role attempt receipts and profile switches into their ledger and report fields. */
final class RoleAttemptEvidence {
    private static final List<String> EFFORT_FIELDS = List.of("effort_transmitted", "effort_reported",
            "effort_delivery", "effort_transmission", "effort_verification", "launch");
    private static final List<String> API_FIELDS = List.of("connection", "connection_fingerprint", "provider",
            "model_requested", "model_reported", "model_served", "correlation_id",
            "api_attempts", "api_attempt_count", "api_retries", "api_retry_policy",
            "api_retry_stopped", "failure_phase", "delivery", "http_status", "request_id", "cost_status",
            "unpriced_attempts", "reported_cost_usd_subtotal", "relay", "may_be_billed");
    private static final List<String> PROCESS_FIELDS = List.of(
            "stderr_tail", "stdout_tail", "exit_code", "timed_out", "stalled", "start_error");

    private RoleAttemptEvidence() {}

    static void journal(EvidenceLedger ledger, String role, String roleInvocationId,
                        Map<String, Object> report, Map<String, Object> attempt) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>(attempt);
        event.put("role", role);
        event.put("stage", report.get("stage"));
        event.put("task_id", report.get("task_id"));
        event.put("workflow_run_id", report.get("workflow_run_id"));
        event.put("role_invocation_id", roleInvocationId);
        event.put("vendor_attempt", attempt.get("attempt"));
        event.put("measurement_context", report.get("measurement_context"));
        event.put("role_contract", report.get("role_contract"));
        if (report.get("findings") != null) event.put("findings", report.get("findings"));
        ledger.append("vendor_attempt", event);
    }

    static Map<String, Object> receipt(int attempt, Profile profile, RoleExecutor.Result result,
                                       boolean inheritsUnfinishedWork) {
        Map<String, Object> entry = new LinkedHashMap<>();
        Map<String, Object> evidence = result.evidence();
        entry.put("attempt", (long) attempt);
        entry.put("vendor_attempt_id", java.util.UUID.randomUUID().toString());
        entry.put("profile", profile.name());
        entry.put("vendor", profile.vendor());
        entry.put("runner", profile.runner());
        entry.put("effort_requested", profile.effort());
        entry.put("model_requested", profile.model());
        copyPresent(evidence, entry, EFFORT_FIELDS);
        if (evidence.get("model_reported") instanceof String value && !value.isBlank()) {
            entry.put("model_reported", value);
            entry.put("model", value);
        }
        entry.put("ok", result.ok());
        entry.put("code", result.code());
        entry.put("duration_millis", result.duration().toMillis());
        copyNonNull(evidence, entry, "cost_usd", "tokens");
        // Each attempt retains the endpoint receipt it received, including delivery and billing.
        copyPresent(evidence, entry, API_FIELDS);
        copyNonNull(evidence, entry, "raw_stdout", "quota", "vendor_unavailable");
        // Process tails stay local to the attempt. The home corpus allowlist removes them.
        copyPresent(evidence, entry, PROCESS_FIELDS);
        if (inheritsUnfinishedWork) entry.put("inherited_unfinished_work", true);
        return entry;
    }

    private static void copyPresent(Map<String, Object> source, Map<String, Object> target, List<String> fields) {
        for (String field : fields) {
            if (source.containsKey(field)) target.put(field, source.get(field));
        }
    }

    private static void copyNonNull(Map<String, Object> source, Map<String, Object> target, String... fields) {
        for (String field : fields) {
            Object value = source.get(field);
            if (value != null) target.put(field, value);
        }
    }

    static Map<String, Object> confirmedSwitch(String role, String invocationId, Map<String, Object> pending) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("role", role);
        event.put("from_profile", pending.get("from_profile"));
        event.put("from_vendor", pending.get("from_vendor"));
        event.put("to_profile", pending.get("to_profile"));
        event.put("to_vendor", pending.get("to_vendor"));
        event.put("from", pending.get("from_profile"));
        event.put("to", pending.get("to_profile"));
        event.put("cause", pending.get("cause"));
        event.put("authorized_by", "human");
        event.put("policy_setting", "confirm");
        if (pending.get("role_code") != null) event.put("role_code", pending.get("role_code"));
        if (pending.get("api_fallback_profiles") != null) {
            event.put("api_fallback_profiles", pending.get("api_fallback_profiles"));
        }
        event.put("role_invocation_id", invocationId);
        return event;
    }

    static void includeFailovers(Map<String, Object> report, List<Map<String, Object>> roleFailovers) {
        if (roleFailovers.isEmpty()) return;
        report.put("role_failovers", List.copyOf(roleFailovers));
        // Each attempt builds a fresh report, so the line printed at the switch would
        // otherwise die with the attempt that failed. The report that is kept is the
        // last one, and it has to name every from→to itself.
        if (!report.containsKey("failover_notice")) {
            StringBuilder notice = new StringBuilder();
            for (Map<String, Object> event : roleFailovers) {
                if (!notice.isEmpty()) notice.append("; ");
                notice.append(event.get("from")).append(" -> ").append(event.get("to"));
            }
            report.put("failover_notice", notice.toString());
        }
    }

    static Map<String, Object> failover(String role, Profile from, Profile to,
                                         RoleExecutor.Result result, String authorizedBy) {
        return failover(role, from, to, result, authorizedBy, "role_quota_exhausted", null);
    }

    static Map<String, Object> failover(String role, Profile from, Profile to,
                                         RoleExecutor.Result result, String authorizedBy,
                                         String cause, String policySetting) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("role", role);
        event.put("from_profile", from.name());
        event.put("from_vendor", from.vendor());
        event.put("to_profile", to.name());
        event.put("to_vendor", to.vendor());
        event.put("from", from.name());
        event.put("to", to.name());
        event.put("cause", cause);
        event.put("authorized_by", authorizedBy);
        if ("role_quota_exhausted".equals(cause)) {
            event.put("quota", result.evidence().getOrDefault("quota", Map.of()));
        } else {
            event.put("role_code", result.code());
            if (policySetting != null) event.put("policy_setting", policySetting);
        }
        return event;
    }

    static Map<String, Object> blockedSwitch(RoleFailoverPolicy.BlockedSwitch blocked) {
        Profile from = blocked.from();
        Profile to = blocked.to();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("rule", blocked.rule());
        event.put("from_profile", from.name());
        event.put("from_vendor", from.vendor());
        event.put("to_profile", to.name());
        event.put("to_vendor", to.vendor());
        event.put("from", from.name());
        event.put("to", to.name());
        event.put("detail", blocked.detail());
        return event;
    }

    static void journalBlocked(EvidenceLedger ledger, Map<String, Object> report,
                               List<RoleFailoverPolicy.BlockedSwitch> blocked, Progress progress) throws IOException {
        if (blocked.isEmpty()) return;
        List<Map<String, Object>> events = blocked.stream().map(RoleAttemptEvidence::blockedSwitch).toList();
        report.put("failover_blocked", events);
        for (Map<String, Object> event : events) {
            ledger.append("role_failover_blocked", event);
            progress.line("      failover refused: " + event.get("from") + " -> " + event.get("to")
                    + " (" + event.get("rule") + ")");
        }
    }
}
