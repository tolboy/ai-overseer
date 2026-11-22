package dev.oseer.cli;

import dev.oseer.approval.HumanDecision;
import dev.oseer.json.Json;
import dev.oseer.ledger.EvidenceLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class DecisionProjection {
    private DecisionProjection() {}

    /** decision.json is authoritative; task-run.json is a convenient, explicitly derived view. */
    static boolean updateDecisionProjection(Path root, HumanDecision decision,
                                            Map<String, Object> waiver) {
        try {
            Path summary = Path.of(decision.summaryPath());
            if (!summary.isAbsolute()) summary = root.resolve(summary);
            summary = summary.toAbsolutePath().normalize();
            Path runDirectory = root.resolve(".oseer/runs").resolve(decision.runId())
                    .toAbsolutePath().normalize();
            if (!summary.startsWith(runDirectory) || !Files.isRegularFile(summary)) return false;
            Map<String, Object> report = Json.parseObject(Files.readString(summary));
            report.put("decision_state", decision.state().jsonValue());
            report.put("decision", decision.decision());
            report.put("decision_actor", decision.actor());
            report.put("decision_note", decision.note());
            report.put("decision_updated_at", decision.updatedAt().toString());
            if (waiver != null) report.put("contract_gap_waiver", waiver);
            String file = summary.getFileName().toString();
            if (!file.endsWith(".json")) return false;
            new EvidenceLedger(root, decision.runId()).writeReport(
                    file.substring(0, file.length() - ".json".length()), report);
            return true;
        } catch (Exception projectionFailure) {
            return false;
        }
    }
}
