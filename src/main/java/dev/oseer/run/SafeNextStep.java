package dev.oseer.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one sentence that moves a stopped run forward without losing what it has.
 *
 * A stop reason is a diagnosis, not an instruction, and the gap between them is where
 * runs get re-run from scratch. Each instruction names the change, then the continuation
 * that keeps verdicts already paid for. The wording is what an operator already has in
 * {@code safe_next_step}, so a branch changes only when the instruction itself should.
 */
final class SafeNextStep {

    private SafeNextStep() {}

    static String of(String reason, Map<String, Object> summary) {
        StopFacts facts = new StopFacts(summary);
        return switch (reason) {
            case "budget_insufficient_to_finish", "budget_exhausted" -> budget(facts);
            case "ledger_unavailable" -> ledgerUnavailable(facts);
            case "finding_protocol_failure" -> findingProtocol(facts);
            case "quota_exhausted" -> quotaExhausted(facts);
            case "rate_limited" -> rateLimited(facts);
            case "role_timed_out" -> roleTimedOut(facts);
            case "role_stalled" -> roleStalled(facts);
            case RunFailurePolicy.CONTRACT_AMENDMENT -> contractAmendment(facts);
            case "orca_worker_not_started" -> orcaWorkerNotStarted(facts);
            case "vendor_call_failed" -> vendorCallFailed(facts) + facts.carryOn + facts.kept();
            case "vendor_unavailable" -> vendorUnavailable(facts);
            case "vendor_protocol_failed" -> vendorProtocolFailed(facts);
            case "prompt_undeliverable" -> promptUndeliverable(facts);
            case "failover_requires_confirmation" -> confirmFailover(facts);
            case "severity_downgraded_without_change" -> severityDowngraded(facts);
            case "repair_made_no_progress" -> repairMadeNoProgress(facts);
            case "quality_exhausted" -> qualityExhausted(facts);
            case "escalation_unavailable" -> escalationUnavailable(facts);
            case "blocking_findings_remain" -> blockingFindings(facts);
            case "reproduction_passed_before_change" -> reproductionPassedEarly();
            case "reproduction_inconclusive" -> reproductionInconclusive();
            case "gate_environment_error" -> gateEnvironment(facts);
            case "baseline_failed" -> baselineFailed();
            case "preflight_outside_scope" -> preflightOutsideScope();
            case "contract_mutated" -> contractMutated();
            default -> readTheReport(facts);
        };
    }

    /**
     * What to do about a vendor call that failed, worded for how the call was made. An API
     * profile has no process, stderr or login; telling the operator to read them sent them
     * looking for a CLI that never ran when the cause was an HTTP 502 from the gateway.
     */
    static String vendorCallFailed(Map<String, Object> summary) {
        return vendorCallFailed(new StopFacts(summary));
    }

    /**
     * Two ceilings, two different edits. Telling somebody whose money ran out to raise the
     * call limit names a number that was never binding, and the run would stop in exactly
     * the same place.
     */
    private static String budget(StopFacts facts) {
        if ("max_elapsed_minutes".equals(facts.limitHit())) return elapsedCeiling(facts);
        if ("max_cost_usd".equals(facts.limitHit())) return costCeiling(facts);
        return callCeiling(facts);
    }

    private static String elapsedCeiling(StopFacts facts) {
        long used = facts.elapsedSeconds();
        return "raise budgets.max_elapsed_minutes in .oseer/tasks/" + facts.taskId
                + ".yaml — " + facts.budgetStop() + " — to more than "
                + ((used + 59) / 60 + 1) + " (the chain has used " + used + " s; a "
                + "call needs at least one whole minute), then: " + facts.carryOn
                + ". Time a stopped run spends waiting for you is not counted" + VERDICTS_KEPT;
    }

    private static String costCeiling(StopFacts facts) {
        String strictTreatment = strictCostCeilingTreatment(facts.summary);
        String costTreatment = strictTreatment != null ? strictTreatment
                : "Note that the ceiling only measures calls whose vendor reported "
                + "a price: " + facts.unpricedCalls() + " of this run's "
                + "calls reported none and were not counted against it";
        return "raise budgets.max_cost_usd in .oseer/tasks/" + facts.taskId + ".yaml — "
                + facts.budgetStop() + " — then: " + facts.carryOn
                + ". Raising max_role_runs would not change where this run stopped. "
                + costTreatment + VERDICTS_KEPT;
    }

    static String strictCostCeilingTreatment(Map<String, Object> summary) {
        return "strict".equals(summary.get("cost_cap"))
                ? "The strict ceiling reserves the declared bounds for calls still owed "
                        + "and counts calls without a reported price at their declared bounds"
                : null;
    }

    private static String callCeiling(StopFacts facts) {
        long floor = facts.callFloor();
        return "raise budgets.max_role_runs in .oseer/tasks/" + facts.taskId + ".yaml"
                + (floor > 0 ? " to at least " + floor : "") + ", then: " + facts.carryOn
                + VERDICTS_KEPT;
    }

    private static String ledgerUnavailable(StopFacts facts) {
        return "restore write access to the home corpus at "
                + "<OSEER_CONFIG_HOME>/ledger (the reason is in corpus_error), then: "
                + facts.carryOn + ". The verdicts this run reached are kept: a corpus that "
                + "could not be written is not a judgement on the work, and no vendor is "
                + "re-dispatched to recover a measurement.";
    }

    private static String findingProtocol(StopFacts facts) {
        return "inspect finding_protocol_failure and finding_history in "
                + "oseer report " + facts.runId + " --text; resolve identity/evidence before a new run";
    }

    private static String quotaExhausted(StopFacts facts) {
        return "wait for the quota window named in the vendor message, "
                + "or add a profile from another vendor, then: " + facts.carryOn + facts.kept();
    }

    private static String rateLimited(StopFacts facts) {
        return "the vendor asked to slow down; its subscription is not "
                + "known to be spent, so no other vendor was offered. Wait a few minutes, "
                + "then: " + facts.carryOn + facts.kept();
    }

    private static String roleTimedOut(StopFacts facts) {
        return "the " + facts.failedProfile() + " call ran out of wall "
                + "clock. Raise limits.wall_clock_minutes on that profile if the task "
                + "needs longer, or narrow the task, then: " + facts.carryOn + facts.kept()
                + facts.blockedFailoverNote();
    }

    private static String roleStalled(StopFacts facts) {
        return "the " + facts.failedProfile() + " call went quiet while "
                + "the wall clock still had time. Raise limits.stall_seconds on that "
                + "profile (0 turns the bound off), then: " + facts.carryOn + facts.kept()
                + facts.blockedFailoverNote();
    }

    private static String contractAmendment(StopFacts facts) {
        return "the candidate passed, and its readers found the acceptance "
                + "too weak to have proved it (contract_gaps_to_plan). The supervisor of "
                + "`oseer do`/`oseer run` hands that to the planner and continues on its own; "
                + "if it did not, answer retry to continue on the current terms: " + facts.carryOn;
    }

    private static String orcaWorkerNotStarted(StopFacts facts) {
        return "the " + facts.failedProfile() + " Orca worker "
                + "never took its first turn, so it read nothing. The usual cause is the agent "
                + "CLI waiting on a first-run screen in that tab: Claude Code asks once per "
                + "repository whether to trust it, and Codex asks to update. The role report's "
                + "agent_screen_tail and agent_blocked_on say which. Answer it once in an Orca "
                + "tab in this worktree, then: " + facts.carryOn + facts.kept();
    }

    private static String vendorCallFailed(StopFacts facts) {
        Object code = facts.roleCode();
        if (code instanceof String role && role.startsWith("role_api_")) {
            return "the " + facts.failedProfile() + " API call failed at the provider or on the "
                    + "way to it (" + role + "). Read api_attempts (http_status, failure_phase, "
                    + "delivery) in its role report; wait for the service to recover or switch "
                    + "the stage to another profile, then: ";
        }
        return "the " + facts.failedProfile() + " process failed "
                + "without a recognised cause (a login, the network, the CLI itself). Read "
                + "stderr_tail and stdout_tail in its role report, fix what they name, "
                + "then: ";
    }

    private static String vendorUnavailable(StopFacts facts) {
        return "the " + facts.failedProfile() + " account cannot use "
                + "this vendor here (a location or eligibility refusal, often HTTP 403). "
                + "It is not a verdict on the work. That profile is skipped for the rest of "
                + "this process; a different profile of the same vendor is not. Add another "
                + "profile for the role, or run where the vendor is available, then: "
                + facts.carryOn + facts.kept();
    }

    private static String vendorProtocolFailed(StopFacts facts) {
        return "the " + facts.failedProfile() + " call "
                + "answered, but not with a readable, complete, schema-valid artifact, so "
                + "whatever it concluded is unknown. Check the profile's prompt, schema "
                + "and output flags against its raw stdout, then: " + facts.carryOn + facts.kept();
    }

    private static String promptUndeliverable(StopFacts facts) {
        return "the prompt could not be delivered to "
                + facts.failedProfile() + " (argument_delivery_check in its role report "
                + "says why); switch the profile to prompt_delivery: stdin or a prompt "
                + "file, then: " + facts.carryOn + facts.kept();
    }

    private static String confirmFailover(StopFacts facts) {
        return "oseer approve " + facts.runId
                + " --decision switch  then  oseer run " + facts.taskId + " --continue " + facts.runId;
    }

    private static String severityDowngraded(StopFacts facts) {
        return "a finding that had been P1 stopped "
                + "blocking on a candidate that did not change, so the run would have "
                + "passed on a relabelling or a dropped finding. Read the two readings in "
                + "`oseer report " + facts.runId + " --text`. If the P1 was wrong, settle that "
                + "on the evidence and adjust the task or the finding deliberately; then "
                + "start a new run.";
    }

    private static String repairMadeNoProgress(StopFacts facts) {
        return "the last repair left the candidate byte-for-byte "
                + "unchanged and the same finding came back, so another round would re-read "
                + "the same bytes. Read the open findings in `oseer report " + facts.runId
                + " --text`: one an implementer cannot act on is a task for a person, not "
                + "for another fix round. Change the contract or the finding's premise, "
                + "then start a new run.";
    }

    private static String qualityExhausted(StopFacts facts) {
        return "every rung of the escalation ladder wrote and was read, "
                + "and the last reading still objects. Read the open findings in `oseer report "
                + facts.runId + " --text`; nothing further is allowed to write. Change the task or "
                + "the finding's premise, or fix by hand, then start a new run.";
    }

    private static String escalationUnavailable(StopFacts facts) {
        return "the escalation ladder's next rung cannot be filled "
                + "(unavailable_role says which profile and why). Verify or replace that "
                + "profile in policy.yaml, then: " + facts.carryOn + facts.kept();
    }

    private static String blockingFindings(StopFacts facts) {
        if (facts.nonactionableBlockers()) {
            return "the remaining P1(s) are not the implementer's to close. Read them in "
                    + "`oseer report " + facts.runId + " --text`, change the task or grant "
                    + "what they ask for, then start a new run.";
        }
        return readTheReport(facts);
    }

    private static String reproductionPassedEarly() {
        return "the acceptance does not detect the defect. "
                + "Strengthen the named check or pick an acceptance command that fails today, "
                + "then start a new run; --continue carries no proof after the contract changes.";
    }

    private static String reproductionInconclusive() {
        return "the reproduction could not establish a red base. "
                + "Resolve the timeout or gate error and start a new run on the unchanged tree "
                + "before paying a vendor.";
    }

    private static String gateEnvironment(StopFacts facts) {
        return "a gate command could not run on this machine ("
                + facts.environmentSignal()
                + "), so it says nothing about the candidate and no implementer was asked to "
                + "work around it. Install what it names here, or remove the command from the "
                + "gates if it cannot run in this environment, then --continue " + facts.runId + ".";
    }

    private static String baselineFailed() {
        return "the project's own checks were already failing before any "
                + "vendor ran. Fix that breakage, or change the baseline contract "
                + "deliberately, then start a new run.";
    }

    private static String preflightOutsideScope() {
        return "revert, commit, or bring into scope the paths "
                + "named in preexisting_violations, then start a new run.";
    }

    private static String contractMutated() {
        return "a file under .oseer changed while the run was in "
                + "flight, so nothing this run produced can be trusted against the terms it "
                + "started with. Restore the contract and start a new run.";
    }

    private static String readTheReport(StopFacts facts) {
        return "read `oseer report " + facts.runId + " --text`, then either address what it "
                + "names and start a new run, or " + facts.carryOn;
    }

    /** Shared by every budget ceiling: the limit is not a judgement of the work. */
    private static final String VERDICTS_KEPT = ". Neither ceiling is part of what the work is judged by, so the "
            + "verdicts this run already reached on this tree are kept rather than "
            + "paid for a second time.";

    /**
     * The summary fields an instruction is allowed to read, named once.
     *
     * The report on disk is still a map. Digging through it in every branch is how a
     * sentence and a null check drift apart.
     */
    private static final class StopFacts {
        private final Map<String, Object> summary;
        private final String runId;
        private final String taskId;
        private final String carryOn;

        StopFacts(Map<String, Object> summary) {
            this.summary = summary;
            this.runId = String.valueOf(summary.get("run_id"));
            this.taskId = String.valueOf(summary.get("task_id"));
            this.carryOn = "oseer approve " + runId + " --decision retry --note \"<why>\""
                    + "  then  oseer run " + taskId + " --continue " + runId;
        }

        Object limitHit() {
            return summary.get("budget_limit_hit");
        }

        Object budgetStop() {
            return summary.get("budget_stop");
        }

        Object unpricedCalls() {
            return summary.get("unpriced_calls");
        }

        Object environmentSignal() {
            return summary.getOrDefault("environment_signal", "missing tool or module");
        }

        long elapsedSeconds() {
            return summary.get("chain") instanceof Map<?, ?> chain
                    && chain.get("elapsed_seconds") instanceof Number seconds
                    ? seconds.longValue() : 0L;
        }

        long callFloor() {
            Object reserve = summary.get("budget_reserve");
            return reserve instanceof Map<?, ?> map
                    && map.get("calls_needed_to_repair_and_finish") instanceof Number needed
                    && summary.get("role_runs") instanceof Number spent
                    ? spent.longValue() + needed.longValue()
                    : 0;
        }

        Object roleCode() {
            return summary.get("infrastructure_failure") instanceof Map<?, ?> failure
                    ? failure.get("role_code") : null;
        }

        /** The profile behind an infrastructure stop, for a sentence that names it. */
        String failedProfile() {
            if (summary.get("infrastructure_failure") instanceof Map<?, ?> failure
                    && failure.get("profile") != null) {
                return "'" + failure.get("profile") + "'";
            }
            return "vendor";
        }

        /** What an infrastructure stop's continuation keeps, named rather than implied. */
        String kept() {
            List<String> passed = new ArrayList<>();
            if (summary.get("review_coverage") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> entry && Boolean.TRUE.equals(entry.get("ok"))
                            && !(entry.get("blocking_findings") instanceof Number n && n.longValue() > 0)) {
                        passed.add(String.valueOf(entry.get("stage")));
                    }
                }
            }
            if (passed.isEmpty()) {
                return ". This stop is not a judgement on the work, and no stage has passed this "
                        + "tree, so there is no verdict to reuse; the failed stage runs again.";
            }
            return ". This stop is not a judgement on the work: "
                    + String.join(", ", passed)
                    + " is reused rather than paid for again, provided the tree, the acceptance and "
                    + "the roster are unchanged; the failed stage runs again.";
        }

        /** Appended when a switch was refused, so the stop does not read as an ordinary timeout. */
        String blockedFailoverNote() {
            if (!(summary.get("failover_blocked") instanceof List<?> rows) || rows.isEmpty()) return "";
            if (!(rows.get(0) instanceof Map<?, ?> event)) return "";
            return " Automatic failover was refused (" + event.get("rule") + "): "
                    + event.get("from") + " -> " + event.get("to") + ".";
        }

        boolean nonactionableBlockers() {
            return summary.get("nonactionable_blocking_ids") instanceof List<?> leftover
                    && !leftover.isEmpty();
        }
    }
}
