package dev.oseer.run;

import dev.oseer.config.Workflow;
import dev.oseer.ledger.EvidenceLedger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The current run's evidence and the previous run's receipts it can still consume. */
final class WorkflowEvidence {
    private final EvidenceLedger ledger;
    private final List<Map<String, Object>> steps;
    private final Map<String, Object> summary;
    private final TaskLoop.Continuation continuation;
    private final Map<String, Map<String, Object>> reusable;

    WorkflowEvidence(EvidenceLedger ledger, List<Map<String, Object>> steps,
                     Map<String, Object> summary, TaskLoop.Continuation continuation,
                     Map<String, Map<String, Object>> reusable) {
        this.ledger = ledger;
        this.steps = steps;
        this.summary = summary;
        this.continuation = continuation;
        this.reusable = new LinkedHashMap<>(reusable);
    }

    EvidenceLedger ledger() { return ledger; }
    String runId() { return ledger.operatorRunId(); }
    List<Map<String, Object>> steps() { return steps; }
    Map<String, Object> summary() { return summary; }
    TaskLoop.Continuation continuation() { return continuation; }

    Map<String, Object> takeReusable(String key) {
        return reusable.remove(key);
    }

    void stampStage(Workflow.Stage stage, int previousStepCount) {
        if (steps.size() > previousStepCount) {
            steps.getLast().putIfAbsent("stage", stage.name());
        }
    }
}
