package dev.oseer.run;

import dev.oseer.config.Workflow;
import dev.oseer.gate.GateRunner;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.role.RoleRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Classifies failures for repair, operator intervention, and continuation. */
final class RunFailurePolicy {
    private RunFailurePolicy() {}

    /** Stops that cut a writer off mid-change and leave its edits in the worktree. */
    private static final Set<String> INTERRUPTED_WRITER = Set.of(
            "turn_ceiling_reached", "role_timed_out", "role_stalled");

    static boolean interruptedWriter(String reason) {
        return INTERRUPTED_WRITER.contains(reason);
    }

    /** A passing run whose readers found the acceptance too weak, sent to the planner. */
    static final String CONTRACT_AMENDMENT = "contract_amendment_requested";

    /** The stop reason of a controller interrupted by a signal or a cancelled API call. */
    static final String CONTROLLER_INTERRUPTED = "controller_interrupted";

    /** Stops that leave prior verdicts eligible for revalidation on the same candidate. */
    private static final Set<String> NOT_ABOUT_THE_WORK = Set.of(
            "visual_qa_unavailable", "visual_qa_port_occupied", "preflight_outside_scope", "model_check_failed",
            "visual_qa_no_evidence", "mcp_config_missing",
            "visual_qa_contract_invalid",
            "turn_ceiling_reached",
            "budget_exhausted",
            "budget_insufficient_to_finish",
            "ledger_unavailable",
            "quota_exhausted",
            "rate_limited",
            "failover_requires_confirmation",
            "escalation_unavailable",
            "independent_review_unavailable",
            "role_timed_out",
            "role_stalled",
            "vendor_call_failed",
            "vendor_unavailable",
            "vendor_protocol_failed",
            "worktree_recovery_required",
            "output_capture_failed",
            "output_limit_exceeded",
            "prompt_undeliverable",
            "orca_worker_not_started",
            CONTROLLER_INTERRUPTED,
            CONTRACT_AMENDMENT);

    /**
     * Translate failed calls to workflow stops. A readable negative verdict still
     * routes through the review's findings. ContinuationEvidence checks whether
     * each previously passing stage still applies.
     */
    private static final Map<String, String> INFRASTRUCTURE_REASONS = Map.ofEntries(
            Map.entry("role_timeout", "role_timed_out"),
            Map.entry("role_process_unsettled", "worktree_recovery_required"),
            Map.entry("role_output_capture_failed", "output_capture_failed"),
            Map.entry("role_output_limit_exceeded", "output_limit_exceeded"),
            Map.entry("role_command_failed", "vendor_call_failed"),
            Map.entry("role_vendor_unavailable", "vendor_unavailable"),
            Map.entry("role_artifact_unparseable", "vendor_protocol_failed"),
            Map.entry("role_artifact_incomplete", "vendor_protocol_failed"),
            Map.entry("role_artifact_schema_violation", "vendor_protocol_failed"),
            Map.entry("role_prompt_undeliverable", "prompt_undeliverable"),
            Map.entry("role_rate_limited", "rate_limited"),
            Map.entry("role_quota_exhausted", "quota_exhausted"),
            Map.entry("role_local_endpoint_unreachable", "vendor_call_failed"),
            Map.entry("role_local_http_error", "vendor_call_failed"),
            Map.entry("role_turns_exhausted", "turn_ceiling_reached"),
            // A failed launch produced no verdict. A worker's reported failure can.
            Map.entry("role_orca_start_failed", "orca_worker_not_started"),
            Map.entry("role_orca_no_coordinator", "orca_worker_not_started"),
            Map.entry("role_orca_unavailable", "orca_worker_not_started"),
            Map.entry("role_orca_no_worktree", "orca_worker_not_started"),
            Map.entry("role_orca_worker_active", "orca_worker_not_started"),
            Map.entry("role_orca_timeout", "role_timed_out"),
            // An API reader that the network, the provider or its own output limit stopped
            // has said nothing about the candidate. They used to become `reviewer_failed`, so a
            // `--continue` after a GonkaRouter 502 on the review declined the implementer's
            // passed stage and paid it to write the same change again. Configuration codes
            // (a profile or connection edited since dispatch, a missing key variable) stay off
            // this list: the role contract check already decides reuse for those.
            Map.entry("role_api_interrupted", CONTROLLER_INTERRUPTED),
            Map.entry("role_api_timeout", "role_timed_out"),
            // Silence while the wall clock remains is not a request to raise that clock.
            Map.entry("role_stalled", "role_stalled"),
            Map.entry("role_api_network_error", "vendor_call_failed"),
            Map.entry("role_api_provider_error", "vendor_call_failed"),
            Map.entry("role_api_protocol_error", "vendor_call_failed"),
            Map.entry("role_api_model_or_parameter_unsupported", "vendor_call_failed"),
            Map.entry("role_api_rate_limited", "rate_limited"),
            Map.entry("role_api_credits_exhausted", "quota_exhausted"),
            Map.entry("role_api_budget_exhausted", "quota_exhausted"),
            Map.entry("role_api_auth_invalid", "vendor_unavailable"),
            Map.entry("role_api_access_denied", "vendor_unavailable"),
            Map.entry("role_api_response_too_large", "vendor_protocol_failed"),
            Map.entry("role_api_response_unreadable", "vendor_protocol_failed"),
            Map.entry("role_api_output_truncated", "vendor_protocol_failed"),
            Map.entry("role_api_model_mismatch", "vendor_protocol_failed"),
            Map.entry("role_api_effort_mismatch", "vendor_protocol_failed"),
            Map.entry("role_api_model_identity_unknown", "vendor_protocol_failed"),
            Map.entry("role_api_tools_unsupported", "vendor_protocol_failed"),
            Map.entry("role_api_artifact_failure", "vendor_protocol_failed"));

    /**
     * The stop reason a role failure code maps to when it says nothing about the work, or
     * null when the failure describes the work.
     */
    static String infrastructureReason(String code) {
        return code == null ? null : INFRASTRUCTURE_REASONS.get(code);
    }

    /** What kind of trouble each infrastructure stop was, for a reader who wants one word. */
    private static final Map<String, String> INFRASTRUCTURE_CAUSES = Map.ofEntries(
            Map.entry(CONTROLLER_INTERRUPTED, "cancelled"),
            Map.entry("role_timed_out", "timeout"),
            Map.entry("role_stalled", "stall"),
            Map.entry("vendor_call_failed", "call_failed"),
            Map.entry("vendor_protocol_failed", "protocol"),
            Map.entry("prompt_undeliverable", "prompt_delivery"),
            Map.entry("rate_limited", "rate_limit"),
            Map.entry("quota_exhausted", "quota"),
            Map.entry("turn_ceiling_reached", "turn_ceiling"),
            Map.entry("orca_worker_not_started", "orca_start"),
            Map.entry("vendor_unavailable", "unavailable"));

    /**
     * Whether a call timed out on a wall clock the chain deadline had lowered.
     *
     * Both runners say it their own way: the direct runner as `role_timeout` after it killed
     * the process tree, Orca as `role_orca_timeout`, whose worker may still need settling. The
     * deadline is the cause either way, and naming the profile's limit instead would send the
     * operator to a number that was never binding.
     */
    static boolean timedOutUnderDeadline(String roleCode, Map<String, Object> details) {
        if (!"role_timeout".equals(roleCode) && !"role_orca_timeout".equals(roleCode)) return false;
        return details != null && "max_elapsed_minutes".equals(details.get("wall_clock_capped_by"));
    }

    /** True when a role outcome code is about the call, not about the candidate. */
    static boolean isInfrastructureFailure(String roleCode) {
        return infrastructureReason(roleCode) != null;
    }

    /**
     * Whether a stop reason is a judgement on the candidate.
     *
     * A continuation throws away earlier verdicts when it is, and keeps them when it is not.
     * The list above carries the argument for each entry; this is the seam where the answer
     * can be checked without paying for a run to reach every one of them.
     */
    static boolean isAboutTheWork(String reason) {
        return !NOT_ABOUT_THE_WORK.contains(reason);
    }

    /**
     * The stop reason a continuation judges an earlier run by.
     *
     * Usually the recorded one. A summary written before a role code was classified still says
     * the generic `<role>_failed`, and its failed step still carries the code that says what
     * happened; reading that code lets a run stopped by, say, an Orca worker that never started
     * keep the verdicts it had already paid for. Only the generic reason is re-read, and only
     * when the failed step's code is one {@link #isInfrastructureFailure} names.
     */
    static String reasonForContinuation(Map<String, Object> prior) {
        String recorded = String.valueOf(prior.get("reason"));
        if (!recorded.endsWith("_failed") || !(prior.get("steps") instanceof List<?> steps)) {
            return recorded;
        }
        for (int index = steps.size() - 1; index >= 0; index--) {
            if (!(steps.get(index) instanceof Map<?, ?> row)) continue;
            if (!Boolean.FALSE.equals(row.get("ok"))) continue;
            String mapped = INFRASTRUCTURE_REASONS.get(String.valueOf(row.get("code")));
            return mapped != null ? mapped : recorded;
        }
        return recorded;
    }

    /**
     * Gate failures an implementer is structurally unable to fix. Each names something
     * outside the work: a tree that was dirty before the run, a base ref that does not
     * resolve, configuration that moved while the run was in flight, or the gate runner
     * itself falling over.
     */
    private static final Set<String> OPERATOR_MUST_RESOLVE = Set.of(
            "preflight_outside_scope",
            "base_ref_unresolvable",
            "contract_mutated",
            "gate_interrupted",
            "gate_internal_error",
            // A missing tool or module on this machine. No candidate change fixes it that
            // is not a workaround for the box the gate happens to run on.
            "gate_environment_error");

    /**
     * Role failures about the endpoint rather than about the work. A model that is not
     * serving, a bearer variable nobody exported, a 500 from the server, an envelope that is
     * not a chat completion: no implementer can fix any of them from inside the diff, and
     * handing one back spends a role run and then fails in exactly the same place. Same
     * reason {@code visual_qa_unavailable} and the {@code role_orca_*} codes are terminal.
     */
    private static final Set<String> ROLE_OPERATOR_MUST_RESOLVE = Set.of(
            "role_visual_no_evidence",
            "role_mcp_config_missing",
            "role_local_endpoint_unreachable",
            "role_local_api_key_missing",
            "role_local_http_error",
            "role_local_response_unreadable",
            "role_human_input_required");

    /** True when another implementer call cannot safely repair the role runner itself. */
    static boolean roleFailureRequiresOperator(String code) {
        return code != null && (ROLE_OPERATOR_MUST_RESOLVE.contains(code)
                || code.startsWith("role_orca_") || code.startsWith("role_api_")
                || code.startsWith("role_harness_") || "harness_coding_unproven".equals(code));
    }

    /**
     * Which failures may be handed back to an implementer at all.
     *
     * The excluded ones are not about the work. No implementer can install a browser,
     * evict another project's dev server, resolve a base ref, start a local model that
     * is not serving, or revert a file outside the blast radius it was given — and
     * asking one to try spends a role run on the operator's configuration and then
     * fails the same way.
     */
    static boolean canRepair(Workflow.Stage stage, Object outcome) {
        if (outcome instanceof VisualQaRunner.Outcome visual) {
            return "visual_qa_failed".equals(visual.code());
        }
        if (outcome instanceof GateRunner.Outcome gate) {
            return !OPERATOR_MUST_RESOLVE.contains(gate.code());
        }
        if (outcome instanceof RoleRunner.Outcome role) {
            if (roleFailureRequiresOperator(role.code())) return false;
            if (!isInfrastructureFailure(role.code())) return true;
            // A call that timed out, was rate limited or ran out of plan has nothing in it
            // for anyone to repair, even on a stage an operator declared `on_fail: fix`:
            // the repair would be a paid call to a writer about a reviewer's endpoint. An
            // unreadable answer is the one exception, and only when the role that gave it
            // is the role that would repair it — a writer can be shown its own malformed
            // artifact and asked again.
            return "vendor_protocol_failed".equals(INFRASTRUCTURE_REASONS.get(role.code()))
                    && stage.fixWith().equals(stage.role());
        }
        return true;
    }

    /**
     * A step that stopped because its vendor ran out is not the same event as a step that
     * failed, and the summary says which. The distinction survives to `next_action` unchanged:
     * both still stop for a human, because Overseer does not decide to wait out a quota window.
     */
    static String reasonFor(RoleRunner.Outcome step, String genericReason) {
        if ("role_failover_requires_confirmation".equals(step.code())) {
            return "failover_requires_confirmation";
        }
        // Orca never saw this worker's first turn, and its wall clock ran out waiting for one.
        // `role_timed_out` would say to raise the wall clock; the agent's screen is what to
        // read. A clock the chain deadline cut is still `budget_exhausted`, decided earlier.
        if ("role_orca_timeout".equals(step.code()) && step.details() != null
                && "unobserved".equals(step.details().get("worker_turn"))) {
            return "orca_worker_not_started";
        }
        if ("role_visual_no_evidence".equals(step.code())) return "visual_qa_no_evidence";
        if ("role_mcp_config_missing".equals(step.code())) return "mcp_config_missing";
        String infrastructure = INFRASTRUCTURE_REASONS.get(step.code());
        return infrastructure != null ? infrastructure : genericReason;
    }

    /**
     * The failed call behind an infrastructure stop, named once at the top of the summary.
     *
     * The row is already in `steps`; this saves the operator from finding it, and says in so
     * many words that the stop is not a judgement and what a continuation will keep.
     */
    static void describeInfrastructureStop(String reason, Map<String, Object> summary,
                                                   List<Map<String, Object>> steps) {
        String cause = INFRASTRUCTURE_CAUSES.get(reason);
        if (cause == null) return;
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("cause", cause);
        for (int index = steps.size() - 1; index >= 0; index--) {
            Map<String, Object> row = steps.get(index);
            if (Boolean.FALSE.equals(row.get("ok")) && row.get("profile") != null) {
                failure.put("step", row.get("step"));
                if (row.get("stage") != null) failure.put("stage", row.get("stage"));
                failure.put("role_code", row.get("code"));
                failure.put("profile", row.get("profile"));
                failure.put("vendor", row.get("vendor"));
                break;
            }
        }
        failure.put("verdict_on_the_work", false);
        failure.put("on_continue", "stages that already passed this exact tree under the same "
                + "contract and roster are reused; the failed stage is dispatched again");
        summary.put("infrastructure_failure", failure);
    }
}
