package dev.oseer.cli;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.config.ConfigLoader;
import dev.oseer.config.UserConfig;
import dev.oseer.json.Json;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.run.TaskLoop;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.oseer.cli.Continuation.continuation;

public final class RunPreflight {
    private RunPreflight() {}

    /** Turn failures before TaskLoop starts into the same durable human boundary. */
    static int recordPreflightFailure(String taskSelector, String runId,
                                              String continueFrom, Exception failure)
            throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        Map<String, Object> result = recordPreflightFailure(root, UserConfig.load(), taskSelector,
                runId, continueFrom, failure);
        System.out.println(Json.write(result));
        return 1;
    }

    /** Persists the terminal report and its pending decision, including prior chain accounting. */
    public static Map<String, Object> recordPreflightFailure(Path root, UserConfig user,
                                                      String taskSelector, String runId,
                                                      String continueFrom, Exception failure)
            throws Exception {
        EvidenceLedger ledger = new EvidenceLedger(root, runId, user.home());
        if (continueFrom != null) {
            try {
                ledger.bindParent(EvidenceLedger.runInstanceIdOf(root, continueFrom));
            } catch (Exception unknownParent) {
                // Lineage stays unknown; the chain below still carries the spend.
            }
        }
        ledger.reserveWorkflowRun(taskSelector);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("task_id", taskSelector);
        summary.put("ok", false);
        summary.put("reason", "run_preflight_failed");
        summary.put("next_action", "human_escalation");
        summary.put("error", Map.of(
                "type", failure.getClass().getName(),
                "message", String.valueOf(failure.getMessage())));
        if (continueFrom != null) {
            // The run never started, but it was a continuation, and the next one reads its
            // chain from here. When the recorded decision cannot even be read, the earlier
            // spend is counted rather than dropped.
            summary.put("continued_from", continueFrom);
            boolean inherits = true;
            try {
                inherits = continuation(root, continueFrom).continuation().reuseJudgements();
            } catch (Exception unreadable) {
                inherits = true;
            }
            summary.put("chain", TaskLoop.chainForPreflightFailure(root, continueFrom, inherits, runId));
        }
        Path file = ledger.writeReport("task-run", summary);
        ApprovalStore store = new ApprovalStore(root);
        HumanDecision decision = store.createFailure(runId, taskSelector,
                "run_preflight_failed", file, null);
        summary.put("decision_path", root.relativize(store.decisionPath(runId))
                .toString().replace('\\', '/'));
        summary.put("decision_state", decision.state().jsonValue());
        summary.put("decision_options", decision.options());
        summary.put("decision_updated_at", decision.updatedAt().toString());
        ledger.writeReport("task-run", summary);
        ledger.append("human_decision_pending", Map.of(
                "run_id", runId, "kind", decision.kind().jsonValue(),
                "path", summary.get("decision_path")));
        ledger.append("task_run", summary);
        ledger.recordCorpusVisibility(summary);
        ledger.writeReport("task-run", summary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("reason", "run_preflight_failed");
        result.put("next_action", "human_escalation");
        result.put("run_id", runId);
        result.put("summary", file.toString());
        result.put("decision_path", summary.get("decision_path"));
        result.put("decision_state", summary.get("decision_state"));
        result.put("decision_options", summary.get("decision_options"));
        result.put("decision_updated_at", summary.get("decision_updated_at"));
        result.putAll(ledger.corpusVisibility());
        result.put("message", String.valueOf(failure.getMessage()));
        return result;
    }
}
