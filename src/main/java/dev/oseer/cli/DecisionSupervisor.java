package dev.oseer.cli;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.dashboard.DecisionPage;
import dev.oseer.execution.orca.OrcaClient;
import dev.oseer.process.ProcessRunner;
import dev.oseer.run.TaskLoop;
import dev.oseer.run.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.oseer.cli.ApprovalCommand.waitForPublishedGate;
import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.ContinuationPolicy.continues;
import static dev.oseer.cli.ContractAmendment.amendContract;
import static dev.oseer.cli.DecisionSession.awaitDecision;
import static dev.oseer.cli.GateWait.skippedGateWait;

public final class DecisionSupervisor {
    // Bound a single supervisor's lifetime. A pending gate beyond this limit stays on disk.
    private static final int MAX_SUPERVISED_RUNS = 100;

    private DecisionSupervisor() {}

    @FunctionalInterface
    public interface ContinuationStarter {
        Map<String, Object> start(Path root, HumanDecision decision, String[] args, ApproveEnv env);
    }

    /** How the supervisor waits for one pending decision to be answered. */
    @FunctionalInterface
    public interface DecisionWaiter {
        Map<String, Object> await(Path root, String runId, Map<String, Object> summary) throws Exception;
    }

    public static Map<String, Object> superviseGates(Path root, String runId, Map<String, Object> summary,
                                                     String[] args, int minutes, ApproveEnv env,
                                                     ContinuationStarter starter) throws Exception {
        return superviseGates(root, runId, summary, args, env, starter, gateOnly(minutes, env));
    }

    /**
     * The wait this supervisor had before the decision page: an Orca gate answer for a stop
     * that is not an acceptance. Kept for callers outside an Orca worktree, where there is no
     * gate and no page to open.
     */
    static DecisionWaiter gateOnly(int minutes, ApproveEnv env) {
        return (root, runId, summary) -> {
            String kind = String.valueOf(summary.get("decision_kind"));
            if (!"failover".equals(kind) && !"failure".equals(kind) && !"contract_change".equals(kind)
                    && !"preparation".equals(kind)) {
                return Map.of("gate_wait", skippedGateWait("success_is_a_separate_acceptance"));
            }
            return waitForPublishedGate(root, runId, summary, minutes, env);
        };
    }

    /**
     * The decision as a page in Orca's browser, with a button per option.
     *
     * Only where Overseer already published an Orca gate — inside an Orca worktree with Orca
     * running — so a `oseer run` in a plain checkout keeps its old behaviour and never blocks
     * on a page nobody can see. Everywhere else, and with `--no-decision-page`, the wait is
     * {@link #gateOnly}. The answer is taken from whichever comes first: the page, `oseer
     * approve` typed anywhere, or the Orca gate; each goes through {@link ApprovalCommand#approveDecision}.
     */
    static DecisionWaiter decisionPage(String[] args, int minutes, ApproveEnv env) {
        DecisionWaiter fallback = gateOnly(minutes, env);
        OrcaClient orca = new OrcaClient(new ProcessRunner());
        return (root, runId, summary) -> {
            boolean gatePublished = summary.get("orca_gate") instanceof Map<?, ?> gate
                    && Boolean.TRUE.equals(gate.get("published"));
            if (hasFlag(args, "--no-decision-page") || !gatePublished) {
                return fallback.await(root, runId, summary);
            }
            return awaitDecision(root, runId, args, minutes, env,
                    url -> orca.openTab(root, url).ok());
        };
    }

    /** Supervises each pending decision outside its run's paid execution budget. */
    public static Map<String, Object> superviseGates(Path root, String runId, Map<String, Object> summary,
                                                     String[] args, ApproveEnv env,
                                                     ContinuationStarter starter, DecisionWaiter waiter)
            throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> chain = new ArrayList<>();
        for (int supervised = 0; summary.get("decision_kind") != null; supervised++) {
            if (supervised == MAX_SUPERVISED_RUNS) {
                chain.add(Map.of("skipped", true, "reason", "supervision_limit_reached",
                        "run_id", runId, "limit", MAX_SUPERVISED_RUNS,
                        "detail", "reopen this run in oseer dashboard or with oseer decide " + runId));
                break;
            }
            HumanDecision resolved;
            Map<String, Object> next;
            // One supervisor per run, across processes, from before the amendment is paid until
            // the continuation has started. See DecisionPage.Supervision.
            DecisionPage.Supervision owned;
            try {
                owned = DecisionPage.supervise(root, runId);
            } catch (Exception unlockable) {
                // Not knowing whether someone else supervises is not permission to.
                Map<String, Object> skipped = skippedGateWait("supervision_unavailable");
                skipped.put("message", String.valueOf(unlockable.getMessage()));
                chain.add(skipped);
                break;
            }
            if (owned == null) {
                Map<String, Object> elsewhere = skippedGateWait("supervised_elsewhere");
                var leased = DecisionPage.liveLease(root, runId);
                if (leased != null) elsewhere.put("url", leased.url());
                chain.add(elsewhere);
                result.put("supervised_elsewhere", runId);
                break;
            }
            try (owned) {
                // A passing run whose readers found the acceptance too weak is the planner's to
                // answer first. When the amendment goes through it records a retry, and the wait
                // below finds the decision already made; when it does not, a person decides.
                if (TaskLoop.CONTRACT_AMENDMENT.equals(summary.get("reason"))) {
                    chain.add(amendContract(root, runId, summary, args, env));
                }
                Map<String, Object> waited = waiter.await(root, runId, summary);
                chain.add(waited);
                try {
                    resolved = new ApprovalStore(root).read(runId);
                } catch (Exception unreadable) {
                    break;
                }
                if (resolved.state() != HumanDecision.State.RESOLVED) break;
                result.put("final_decision", resolved.decision());
                result.put("final_decision_run_id", runId);
                result.put("final_decision_record", resolved.toMap());
                // A retry answered on the page is the operator saying "go again": leaving it to a
                // command typed at the machine made the answer from Orca half an answer.
                if (!continues(resolved)) break;
                next = starter.start(root, resolved, args, env);
            }
            chain.add(next);
            if (!Boolean.TRUE.equals(next.get("started"))) break;
            // The answered run's tab names the run that took over, now that one did.
            Workspace previous = Workspace.guarded(env.observation.forStatus(args).at(root));
            previous.adopt(runId);
            previous.answered(resolved.decision() + " -> " + next.get("run_id"));
            result.put("continued_run_id", next.get("run_id"));
            result.put("continued_ok", next.get("ok"));
            result.put("continued_reason", next.get("reason"));
            result.put("continued_next_action", next.get("next_action"));
            result.put("continued_summary", next.get("summary"));
            if (!(next.get("summary_report") instanceof Map<?, ?> raw)) break;
            summary = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) summary.put(String.valueOf(entry.getKey()), entry.getValue());
            runId = String.valueOf(next.get("run_id"));
        }
        if (!chain.isEmpty()) result.put("gate_chain", chain);
        return result;
    }
}
