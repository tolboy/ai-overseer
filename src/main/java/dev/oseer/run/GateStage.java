package dev.oseer.run;

import dev.oseer.config.ConfigLoader;
import dev.oseer.gate.GateRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A machine-gate dispatch and the environment failure it may have to name. */
final class GateStage {
    private GateStage() {}

    /** Name the command and the signal on the run, for the person who has to install it. */
    static void recordEnvironmentError(Map<String, Object> summary, GateRunner.Outcome gate) {
        Map<String, Object> data = gate.data() == null ? Map.of() : gate.data();
        if (data.get("environment_signal") != null) {
            summary.put("environment_signal", String.valueOf(data.get("environment_signal")));
        }
        if (data.get("environment_command") != null) {
            summary.put("environment_command", String.valueOf(data.get("environment_command")));
        }
        summary.putIfAbsent("resolution", "A gate command could not run on this machine"
                + (data.get("environment_command") != null
                    ? " (`" + data.get("environment_command") + "`: " + data.get("environment_signal") + ")"
                    : "")
                + ". Install the missing tool or module, or remove the command from the gates, "
                + "then continue; no implementer was asked to work around the environment.");
    }

    static GateRunner.Outcome runGates(GateRunner gates, ConfigLoader.Loaded loaded, String evidenceRunId,
                                        int attempt, boolean dryRun, List<Map<String, Object>> steps,
                                        Map<String, String> pinnedConfig, String diffBaseCommit)
            throws Exception {
        if (dryRun) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", "gates");
            entry.put("attempt", (long) attempt);
            entry.put("dry_run", true);
            steps.add(entry);
            return null;
        }
        GateRunner.Outcome outcome = gates.run(loaded, evidenceRunId, pinnedConfig, diffBaseCommit);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", "gates");
        entry.put("attempt", (long) attempt);
        entry.put("ok", outcome.ok());
        entry.put("code", outcome.code());
        entry.put("report", String.valueOf(outcome.report()));
        if (StageIds.hasDedicatedEvidenceDirectory(evidenceRunId, "gates", attempt)) {
            entry.put("evidence_dir", evidenceRunId);
        }
        steps.add(entry);
        return outcome;
    }

}
