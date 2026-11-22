package dev.oseer.run;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.execution.orca.OrcaDecisionGate;
import dev.oseer.execution.orca.OrcaLifecycle;
import dev.oseer.process.ProcessRunner;

import java.nio.file.Path;
import java.util.Map;

/** The pending human decision, on the summary and, when asked, on an Orca gate. */
final class DecisionRecord {
    private DecisionRecord() {}

    /**
     * Mirror the pending decision onto Orca so it can be answered from somewhere else.
     *
     * Recorded either way, and never fatal. The decision is already durable on disk by the
     * time this runs; a second surface that could not be reached is worth saying out loud and
     * is not worth failing a finished run over.
     */
    static void publishGate(boolean orcaGate, ProcessRunner processes, Map<String, Object> summary, Path root, HumanDecision decision,
                             String objective) {
        if (!orcaGate) {
            summary.put("orca_gate", Map.of("published", false, "reason", "disabled"));
            return;
        }
        OrcaDecisionGate.Publication published = new OrcaDecisionGate(processes)
                .publish(root, new OrcaLifecycle(root, decision.runId()), decision, objective);
        // Not narrated here: the footer says it once, with the commands that answer it.
        summary.put("orca_gate", published.toMap());
    }

    static void addDecision(Map<String, Object> summary, Path root, HumanDecision decision)
            throws Exception {
        ApprovalStore store = new ApprovalStore(root);
        summary.put("decision_path", root.relativize(store.decisionPath(decision.runId()))
                .toString().replace('\\', '/'));
        summary.put("decision_state", decision.state().jsonValue());
        summary.put("decision_kind", decision.kind().jsonValue());
        summary.put("decision_reason", decision.reason());
        summary.put("decision_options", decision.options());
        summary.put("decision_updated_at", decision.updatedAt().toString());
        summary.put("approve_with", "oseer approve " + decision.runId()
                + " --decision " + decision.options().get(0)
                + " --expected-updated-at " + decision.updatedAt());
    }

}
