package dev.oseer.run;

import dev.oseer.config.TaskSpec;
import dev.oseer.role.RoleRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Tracks the current run's dispatch admissions and spend against the chain's limits. */
final class RunBudget {
    /** The shortest time a vendor call is started with. See {@link #requireRoleRun}. */
    private static final long MINIMUM_CALL_SECONDS = 60;

    /**
     * Which ceiling refused the call, carried rather than only spelled into a
     * message.
     *
     * They are hit for different reasons and fixed in different places, and the run used
     * to tell an operator whose money ceiling was reached to raise the call ceiling — a
     * next step that would not have unblocked anything. Parsing the sentence back out to
     * work out which limit it was is the kind of thing that is right until somebody
     * rewords it.
     */
    @SuppressWarnings("serial")
    static final class ExceededException extends RuntimeException {
        private final String limit;

        ExceededException(String limit, String message) {
            super(message);
            this.limit = limit;
        }

        String limit() { return limit; }
    }

    private final long maxRuns;
    private final double maxCost;
    private int runs;
    private double spent;
    /**
     * Vendor calls that came back with no price at all.
     *
     * Every vendor here is driven through its own CLI on the operator's own
     * subscription, and what a call costs is whatever that CLI chose to report: Grok and
     * Claude print a figure, Codex prints none. So `max_cost_usd` is a ceiling on the
     * part of the run that priced itself, and a run where nothing did could spend its
     * whole allowance of calls under a $40 limit and charge $0.00 against it. Counting
     * the silent calls is the difference between a budget and the appearance of one.
     */
    private int unpriced;

    /**
     * What earlier runs of the same chain spent. Counted against every ceiling and never
     * reported as this run's own: `role_runs` and `total_cost_usd` stay per run, because
     * the corpus sums runs and would otherwise count a continued chain twice.
     */
    private RunChain inherited = RunChain.NONE;
    /** Execution time the chain may spend in all, in seconds; null when undeclared. */
    private final Long maxElapsedSeconds;
    /**
     * Worst-case price per paying stage under a strict cap, or null under a threshold.
     * A call is admitted only while the reported spend plus every bound still owed
     * fits under the ceiling; the bound of the stage being dispatched is retired when
     * its actual price is accounted for.
     */
    private Map<String, Double> strictBounds;
    private final Set<String> stagesPaid = new LinkedHashSet<>();
    private String dispatchingStage;
    /** True while this role's vendor attempts have already been charged in-flight. */
    private boolean settledInFlight;
    /**
     * Declared bounds a strict cap charged for attempts that reported no price. They count
     * against the cap and are never reported as spend: `total_cost_usd` is what vendors
     * printed, and a call that printed nothing stays in `unpriced`.
     */
    private double unpricedCharge;
    private final LongSupplier clock;
    private final long startedNanos;

    RunBudget(long maxRuns, double maxCost, Long maxElapsedMinutes,
           LongSupplier clock) {
        this.maxRuns = maxRuns;
        this.maxCost = maxCost;
        this.maxElapsedSeconds = maxElapsedMinutes == null ? null : maxElapsedMinutes * 60;
        this.clock = clock;
        this.startedNanos = clock.getAsLong();
    }

    int runs() { return runs; }
    double spent() { return spent; }

    void strictBounds(Map<String, Double> bounds) { this.strictBounds = new LinkedHashMap<>(bounds); }

    boolean strict() { return strictBounds != null; }

    /** The stage about to dispatch, so its own bound counts once and is retired after. */
    void dispatching(String stage) { this.dispatchingStage = stage; }

    /** Bounds still owed: every paying stage not yet paid, the dispatching one included. */
    double outstandingBounds() {
        if (strictBounds == null) return 0;
        double sum = 0;
        for (Map.Entry<String, Double> bound : strictBounds.entrySet()) {
            if (stagesPaid.contains(bound.getKey())) continue;
            sum += bound.getValue();
        }
        return sum;
    }

    void inherit(RunChain chain) { this.inherited = chain; }

    RunChain inherited() { return inherited; }

    long chainRuns() { return inherited.roleRuns() + runs; }

    double chainSpent() { return inherited.costUsd() + spent; }

    long chainUnpriced() { return inherited.unpricedCalls() + unpriced; }

    double unpricedCharge() { return unpricedCharge; }

    double chainUnpricedCharge() { return inherited.unpricedChargeUsd() + unpricedCharge; }

    /** Execution seconds the chain has used, this run's so far included. */
    long elapsedSeconds() {
        return inherited.elapsedSeconds() + (clock.getAsLong() - startedNanos) / 1_000_000_000L;
    }

    Long maxElapsedSeconds() { return maxElapsedSeconds; }

    /** What is left of the chain's execution time, or null when it declared none. */
    Duration wallClockCap() {
        if (maxElapsedSeconds == null) return null;
        return Duration.ofSeconds(Math.max(0, maxElapsedSeconds - elapsedSeconds()));
    }

    /** Whether {@link #requireRoleRun} would admit another call right now. */
    boolean hasRoom() {
        if (maxRuns > 0 && chainRuns() >= maxRuns) return false;
        if (maxCost > 0 && chainSpent() >= maxCost) return false;
        if (strictBounds != null && maxCost > 0
                && chainSpent() + chainUnpricedCharge() + outstandingBounds() > maxCost) {
            return false;
        }
        return !deadlinePassed();
    }

    boolean deadlinePassed() {
        Duration left = wallClockCap();
        return left != null && left.getSeconds() < MINIMUM_CALL_SECONDS;
    }

    /** Check the wait without consuming the next call's reservation. */
    void requireRetryWait(long pauseMillis) {
        if (maxElapsedSeconds == null) return;
        Duration left = Duration.ofSeconds(maxElapsedSeconds - inherited.elapsedSeconds())
                .minusNanos(clock.getAsLong() - startedNanos);
        if (left.minusMillis(pauseMillis).getSeconds() >= MINIMUM_CALL_SECONDS) return;
        throw new ExceededException("max_elapsed_minutes", "max_elapsed_minutes of "
                + (maxElapsedSeconds / 60) + " cannot fit a " + pauseMillis
                + " ms rate-limit retry wait and the next call's minimum "
                + MINIMUM_CALL_SECONDS + " s: " + left.getSeconds() + " s remain");
    }

    /**
     * Vendor calls that already happened before this loop, counted so preparation is
     * not free. A reservation is never given back: see {@link #requireRoleRun}.
     */
    void alreadySpent(int priorRuns, double priorCost, int priorUnpriced) {
        this.runs = Math.max(0, priorRuns);
        this.spent = Math.max(0, priorCost);
        this.unpriced = Math.max(0, priorUnpriced);
    }

    /**
     * Vendor calls the ceiling still allows, or {@link Integer#MAX_VALUE} when the task
     * declared none.
     *
     * A reservation is taken at {@link #requireRoleRun} and never given back, including
     * for a dispatch that failed or whose outcome is unknown. That is the conservative
     * direction: a call whose receipt never arrived may still have been served and billed,
     * and treating it as free would let a restart pay for it twice. The one exception is
     * {@link #releaseRoleRun}, for a call proven never to have reached a vendor.
     */
    int remaining() {
        if (maxRuns <= 0) return Integer.MAX_VALUE;
        return (int) Math.max(0, maxRuns - chainRuns());
    }

    /**
     * Give back the reservation of a call that provably never reached a vendor, and
     * charge it nothing — not even an unpriced count. It still counts as settled, so the
     * role is not charged again when its outcome is recorded.
     *
     * Measured 2026-09-24: Orca workers fenced before their first turn were each counted
     * against {@code max_role_runs} (default 6), beside preparation's calls.
     */
    void releaseRoleRun() {
        runs = Math.max(0, runs - 1);
        settledInFlight = true;
    }

    long maxRuns() { return maxRuns; }

    void requireRoleRun() {
        String earlier = inherited.runs().isEmpty() ? ""
                : " across this run and " + String.join(", ", inherited.runs());
        if (maxRuns > 0 && chainRuns() >= maxRuns) {
            throw new ExceededException("max_role_runs",
                    "max_role_runs of " + maxRuns + " reached" + earlier);
        }
        if (maxCost > 0 && chainSpent() >= maxCost) {
            throw new ExceededException("max_cost_usd",
                    "max_cost_usd of " + maxCost + " reached, spent " + chainSpent() + earlier);
        }
        // Under a strict cap the reported spend plus the worst case of every call still
        // owed has to fit, so the last call cannot be the one that overruns. A repair is
        // a call the plan did not reserve; its stage is owed again when it re-dispatches.
        if (strictBounds != null && maxCost > 0) {
            if (dispatchingStage != null) stagesPaid.remove(dispatchingStage);
            double owed = outstandingBounds();
            double charged = chainUnpricedCharge();
            if (chainSpent() + charged + owed > maxCost) {
                throw new ExceededException("max_cost_usd", "max_cost_usd of " + maxCost
                        + " is a strict cap: reported spend " + chainSpent()
                        + (charged > 0 ? ", " + charged + " charged at declared bounds for "
                                + "calls that reported no price," : "")
                        + " plus the declared worst case of the calls still owed (" + owed
                        + ") would exceed it" + earlier);
            }
        }
        // A call is not started with less than a minute left. The wall clock a profile
        // declares is in whole minutes, and a call admitted with seconds to spare would be
        // given a minute it does not have.
        if (deadlinePassed()) {
            throw new ExceededException("max_elapsed_minutes",
                    "max_elapsed_minutes of " + (maxElapsedSeconds / 60) + " reached: "
                            + elapsedSeconds() + " s of execution already spent" + earlier);
        }
        runs++;
    }

    int unpriced() { return unpriced; }

    /**
     * The selected profile's declared bound for the stage about to dispatch. A pin or
     * host twin can be dearer than anyone on the policy list; reserving the list's max
     * then spent the pin under a number that had never been checked.
     */
    void reserveSelected(Double bound) {
        if (strictBounds == null || dispatchingStage == null) return;
        if (bound == null) {
            throw new ExceededException("max_cost_usd",
                    "budgets.cost_cap: strict needs a proven upper bound for the profile "
                            + "about to dispatch at stage '" + dispatchingStage
                            + "', and it declares none");
        }
        strictBounds.put(dispatchingStage, bound);
    }

    /**
     * Charge one completed vendor attempt before a retry or failover is admitted.
     * Unpriced attempts under a strict cap are charged at the profile's declared bound
     * so the next dispatch cannot treat them as free. That charge is the cap's, not the
     * vendor's: folding it into {@code spent} reported a Codex call or a spent quota as
     * dollars nobody printed, and hid it from {@code unpriced_calls}.
     */
    void settleAttempt(Object cost, Double bound) {
        if (cost instanceof Number number) {
            spent += number.doubleValue();
        } else {
            unpriced++;
            if (strictBounds != null && bound != null && bound > 0) unpricedCharge += bound;
        }
        settledInFlight = true;
    }

    /**
     * The role already charged its vendor attempts through {@link #settleAttempt}.
     * Recording the role only retires the stage bound.
     */
    boolean finishSettledRole() {
        if (!settledInFlight) return false;
        settledInFlight = false;
        if (strictBounds != null && dispatchingStage != null) {
            stagesPaid.add(dispatchingStage);
            dispatchingStage = null;
        }
        return true;
    }

    void account(Object cost) {
        if (cost instanceof Number number) spent += number.doubleValue();
        else unpriced++;
        if (strictBounds != null && dispatchingStage != null) {
            stagesPaid.add(dispatchingStage);
            dispatchingStage = null;
        }
    }

    /**
     * The gate a contract amendment's planner and plan reviewer dispatch through: the ceilings
     * in {@code ceilings}, spent as far as the chain {@code runId} belongs to has spent them.
     *
     * The loop routes contract gaps to the planner only when the chain can pay for every call
     * an amendment may make. Those calls run afterwards, in the supervisor, and preparation's
     * own gate counts calls and nothing else: a planner dispatched with two minutes of the
     * chain's deadline left was given its profile's whole wall clock, and a plan reviewer or
     * a redraft could start after the money ceiling had been reached. This gate asks the
     * chain's budget before every call, as the loop does, and lowers each call's wall clock
     * to what is left.
     *
     * Build it before the amendment's reservation receipt is written: the chain read here
     * counts a receipt it finds, so a fresh reservation would be counted as already spent.
     */
    static RoleRunner.DispatchGate amendmentGate(Path root, String runId, TaskSpec.Budget ceilings,
                                                 LongSupplier nanos) {
        RunBudget budget = new RunBudget(ceilings.maxRoleRuns(), ceilings.enforcedMaxCostUsd(),
                ceilings.maxElapsedMinutes(), nanos);
        budget.inherit(RunChain.after(root, runId));
        return preparationGate(budget);
    }

    static RoleRunner.DispatchGate preparationGate(TaskSpec.Budget ceilings) {
        return preparationGate(new RunBudget(ceilings.maxRoleRuns(), ceilings.enforcedMaxCostUsd(),
                null, System::nanoTime));
    }

    private static RoleRunner.DispatchGate preparationGate(RunBudget budget) {
        return new RoleRunner.DispatchGate() {
            @Override public void requireDispatch() {
                try {
                    budget.requireRoleRun();
                } catch (ExceededException exceeded) {
                    throw new Preparation.Exhausted(exceeded.getMessage());
                }
            }

            @Override public boolean hasRoom() { return budget.hasRoom(); }

            @Override public Duration wallClockCap() { return budget.wallClockCap(); }

            @Override public void requireRetryWait(long pauseMillis) {
                try {
                    budget.requireRetryWait(pauseMillis);
                } catch (ExceededException exceeded) {
                    throw new Preparation.Exhausted(exceeded.getMessage());
                }
            }

            @Override public void settleAttempt(Object costUsd, Double declaredBound) {
                budget.settleAttempt(costUsd, declaredBound);
            }

            @Override public void release() { budget.releaseRoleRun(); }
        };
    }
}
