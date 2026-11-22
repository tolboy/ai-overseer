package dev.oseer.run;

import dev.oseer.config.TaskSpec;
import dev.oseer.json.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the earlier runs of a continued chain already spent.
 *
 * A continuation used to start from nothing: a fresh call ceiling, a fresh set of fix
 * rounds, a fresh clock. So a task declared as "four calls, one repair" could be run as
 * four calls and one repair per `--continue`, and the limits an operator chose described
 * one run rather than the work. The chain is the unit now. Raising a limit is still one
 * edit to the task file, which is not part of what the work is judged by, and the run
 * that follows records it as `contract_change_budget_only`.
 *
 * Only a continuation that carries verdicts — a `retry` or a `switch` — is the same
 * chain. A rejection is a person asking for different work, and starts a new one; so
 * does a run with no `--continue`, which carries nothing at all.
 *
 * @param elapsedKnown false when an earlier run predates this record, whose execution
 *                     time is then not counted rather than guessed
 */
record RunChain(List<String> runs, long roleRuns, double costUsd, long unpricedCalls,
             long fixAttempts, long elapsedSeconds, boolean elapsedKnown,
             long blockingReviewsSeen, long escalationRung, double unpricedChargeUsd) {
    /**
     * What an amendment spent, written by the supervisor into the directory of the run that
     * stopped for it: the planner's calls belong to that run's chain, and {@link #after}
     * adds them to what the next run inherits.
     */
    static final String AMENDMENT_RECEIPT = "contract-amendment.json";

    static final RunChain NONE = new RunChain(List.of(), 0, 0, 0, 0, 0, true, 0, 0, 0);

    RunChain(List<String> runs, long roleRuns, double costUsd, long unpricedCalls,
          long fixAttempts, long elapsedSeconds, boolean elapsedKnown) {
        this(runs, roleRuns, costUsd, unpricedCalls, fixAttempts, elapsedSeconds, elapsedKnown,
                0, 0, 0);
    }

    /** The chain as the named run left it, or {@link #NONE} when it cannot be read. */
    static RunChain after(Path root, String runId) {
        try {
            Path file = root.resolve(".oseer/runs").resolve(runId).resolve("task-run.json");
            if (!Files.isRegularFile(file)) return NONE;
            Map<String, Object> prior = Json.parseObject(Files.readString(file));
            Path receipt = file.resolveSibling(AMENDMENT_RECEIPT);
            if (prior.get("chain") instanceof Map<?, ?> chain) {
                List<String> runs = new ArrayList<>();
                if (chain.get("runs") instanceof List<?> names) {
                    for (Object name : names) runs.add(String.valueOf(name));
                }
                return new RunChain(List.copyOf(runs), number(chain.get("role_runs")),
                        decimal(chain.get("cost_usd")), number(chain.get("unpriced_calls")),
                        number(chain.get("fix_attempts")), number(chain.get("elapsed_seconds")),
                        !Boolean.FALSE.equals(chain.get("elapsed_known")),
                        number(chain.get("blocking_reviews_seen")),
                        number(chain.get("escalation_rung")),
                        decimal(chain.get("cost_cap_unpriced_charge_usd"))).withAmendment(receipt);
            }
            // Written before chains were recorded: the run's own totals are the chain.
            return new RunChain(List.of(runId), number(prior.get("role_runs")),
                    decimal(prior.get("total_cost_usd")), number(prior.get("unpriced_calls")),
                    number(prior.get("attempts_used")), 0, false).withAmendment(receipt);
        } catch (Exception unreadable) {
            return NONE;
        }
    }

    /**
     * This chain with the contract amendment that followed its last run, when there was
     * one. The planner and the plan reviewer were paid between two runs, so neither run's
     * own totals hold them; without this a chain could spend on amendments outside every
     * ceiling it declared. The receipt is written as a reservation of the most calls an
     * amendment can make before any of them, and replaced by the actual spend after.
     */
    RunChain withAmendment(Path receipt) {
        if (!Files.isRegularFile(receipt)) return this;
        Map<String, Object> spent;
        try {
            spent = Json.parseObject(Files.readString(receipt));
        } catch (Exception unreadable) {
            return this;
        }
        List<String> names = new ArrayList<>(runs);
        if (spent.get("run_id") instanceof String id && !names.contains(id)) names.add(id);
        return new RunChain(List.copyOf(names), roleRuns + number(spent.get("role_runs")),
                costUsd + decimal(spent.get("cost_usd")),
                unpricedCalls + number(spent.get("unpriced_calls")), fixAttempts,
                elapsedSeconds + number(spent.get("elapsed_seconds")), elapsedKnown,
                blockingReviewsSeen, escalationRung, unpricedChargeUsd);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * The chain block for a run that failed before the loop could start.
     *
     * A `oseer run --continue A` that fails validation is still a link in A's chain, and a
     * run that later continues it must inherit A's spend. Without this a configuration typo
     * between two continuations reset the task's call ceiling: the review of this change
     * reproduced a second implementer dispatch under `max_role_runs: 1`. The failed run spent
     * nothing, so the block is A's chain with this run appended. A continuation that would
     * not carry verdicts — a rejection — starts afresh, exactly as the loop decides.
     */
    static Map<String, Object> forPreflightFailure(Path root, String priorRunId,
                                                               boolean inherits, String runId) {
        RunChain earlier = priorRunId != null && inherits ? RunChain.after(root, priorRunId) : RunChain.NONE;
        List<String> runs = new ArrayList<>(earlier.runs());
        runs.add(runId);
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("runs", runs);
        chain.put("role_runs", earlier.roleRuns());
        chain.put("cost_usd", earlier.costUsd());
        chain.put("unpriced_calls", earlier.unpricedCalls());
        if (earlier.unpricedChargeUsd() > 0) {
            chain.put("cost_cap_unpriced_charge_usd", earlier.unpricedChargeUsd());
        }
        chain.put("fix_attempts", earlier.fixAttempts());
        chain.put("elapsed_seconds", earlier.elapsedSeconds());
        chain.put("elapsed_known", earlier.elapsedKnown());
        chain.put("blocking_reviews_seen", earlier.blockingReviewsSeen());
        chain.put("escalation_rung", earlier.escalationRung());
        return chain;
    }

    static void describe(Map<String, Object> summary, RunBudget budget, String runId,
                                      TaskSpec.ResolvedTask task) {
        describe(summary, budget, runId, task, 0);
    }

    /**
     * The spend of the whole continued chain, this run included, beside this run's own.
     *
     * The next `--continue` reads it back as its starting point, so it is written at every
     * stop and at the gate, not only once. The maxima are the ones in force now: a person may
     * have raised them since the chain began, and the run records that separately as
     * `contract_change_budget_only`.
     */
    @SuppressWarnings("unchecked")
    static void describe(Map<String, Object> summary, RunBudget budget, String runId,
                                      TaskSpec.ResolvedTask task, int attempt) {
        RunChain earlier = budget.inherited();
        Map<String, Object> chain = summary.get("chain") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing) : new LinkedHashMap<>();
        List<String> runs = new ArrayList<>(earlier.runs());
        runs.add(runId);
        chain.put("runs", runs);
        chain.put("role_runs", budget.chainRuns());
        chain.put("cost_usd", budget.chainSpent());
        chain.put("unpriced_calls", budget.chainUnpriced());
        if (budget.chainUnpricedCharge() > 0) {
            chain.put("cost_cap_unpriced_charge_usd", budget.chainUnpricedCharge());
        }
        chain.put("fix_attempts", earlier.fixAttempts() + attempt);
        chain.put("elapsed_seconds", budget.elapsedSeconds());
        chain.put("elapsed_known", earlier.elapsedKnown());
        if (task != null) {
            chain.put("max_role_runs", task.budget().maxRoleRuns());
            chain.put("max_cost_usd", task.budget().maxCostUsd());
            chain.put("max_fix_attempts", task.maxFixAttempts());
            chain.put("max_elapsed_minutes", task.budget().maxElapsedMinutes());
        }
        chain.put("calls_remaining", budget.remaining() == Integer.MAX_VALUE
                ? null : (long) budget.remaining());
        if (chain.get("max_fix_attempts") instanceof Number max) {
            chain.put("fix_attempts_remaining",
                    Math.max(0, max.longValue() - (earlier.fixAttempts() + attempt)));
        }
        // The ladder's position is a chain fact: a continuation must not climb the same rung
        // twice, nor forget the readings that already objected.
        long seen = earlier.blockingReviewsSeen();
        long rung = earlier.escalationRung();
        if (summary.get("escalation") instanceof Map<?, ?> ladder) {
            if (ladder.get("blocking_reviews_seen") instanceof Number n) seen = n.longValue();
            if (ladder.get("rung") instanceof Number n) rung = n.longValue();
        }
        chain.put("blocking_reviews_seen", seen);
        chain.put("escalation_rung", rung);
        summary.put("chain", chain);
    }
}
