package dev.oseer.cli;

import dev.oseer.config.ConfigLoader;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.ledger.RunReport;
import dev.oseer.run.Progress;
import dev.oseer.run.TaskLoop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.oseer.cli.CliOptions.hasFlag;

public final class RunPresentation {
    private RunPresentation() {}

    static Map<String, Object> runReport(TaskLoop.Outcome outcome, String runId, String reportPath) {
        Map<String, Object> summary = outcome.summaryReport();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", outcome.ok());
        report.put("reason", outcome.reason());
        report.put("next_action", outcome.nextAction());
        report.put("run_id", runId);
        report.put("summary", String.valueOf(outcome.summary()));
        for (String field : List.of("steps", "decision_path", "decision_state", "decision_kind",
                "decision_reason", "decision_options", "decision_updated_at", "failover_pending")) {
            report.put(field, summary.get(field));
        }
        for (String field : List.of("corpus_status", "corpus_undelivered", "corpus_reason", "tree_safe_to_delete")) {
            Object value = summary.get(field);
            if (value != null) report.put(field, value);
        }
        report.put("report_path", reportPath);
        return report;
    }

    /**
     * The printed object is the chain's last run, and {@code ok} is what the exit code uses.
     *
     * A waiter that started on plan-2 and finished when plan-5 was accepted used to print
     * plan-2's {@code vendor_protocol_failed} with {@code ok: false} and exit 0, because
     * only the exit looked at {@code continued_ok}. Measured 2026-09-26.
     */
    public static void surfaceChain(Map<String, Object> result) {
        if (result == null || !(result.get("continued_run_id") instanceof String finalId)) return;
        result.put("run_id", finalId);
        if (result.get("continued_ok") instanceof Boolean ok) result.put("ok", ok);
        if (result.get("continued_reason") != null) {
            result.put("reason", result.get("continued_reason"));
            result.put("code", result.get("continued_reason"));
        }
        if (result.get("continued_next_action") != null) {
            result.put("next_action", result.get("continued_next_action"));
        }
        if (result.get("continued_summary") != null) result.put("summary", result.get("continued_summary"));
        // Prefer the full decision record so run/do stdout matches approve's `decision`
        // shape. `final_decision` stays the choice string for callers that already read it.
        if (result.get("final_decision_record") instanceof Map<?, ?>) {
            result.put("decision", result.get("final_decision_record"));
            result.put("decision_state", "resolved");
        } else if (result.get("final_decision") != null) {
            result.put("decision", result.get("final_decision"));
            result.put("decision_state", "resolved");
        }
    }

    /**
     * The joined view, written next to the evidence it joins. It is derived, so it is
     * regenerated rather than appended to: `oseer report` recomputes it from the same files
     * at any time, and a stale copy can never outlive what it summarises.
     */
    static String writeRunReport(Path from, String runId) {
        try {
            Path root = new ConfigLoader().findProjectRoot(from);
            Map<String, Object> report = new RunReport().of(root, runId);
            Path file = new EvidenceLedger(root, runId).writeReport("report", report);
            return file.toString();
        } catch (Exception noSummary) {
            // A run that never wrote a summary has nothing to join. The stage evidence is
            // still on disk, and saying so beats failing the command that produced it.
            return null;
        }
    }

    /**
     * The live account of a long run, on stderr.
     *
     * stdout is one JSON object and stays that way — Conductor and every script read it —
     * so the narration goes to the other stream, where redirecting one does not disturb the
     * other. `--quiet` turns it off for anyone who wants neither.
     */
    static Progress narration(String[] args) {
        return hasFlag(args, "--quiet") ? Progress.SILENT
                : Progress.toStderr();
    }

    /**
     * Where a run's own account of itself is kept.
     *
     * Beside the evidence, not in it. `oseer report` never reads this: the ledger holds every
     * fact, and this holds the order a person would have watched them arrive in. It exists so
     * a run started from a script, a scheduler or a closed terminal still has one, and so
     * `--watch` has something to follow.
     */
    static Path narrationFile(Path root, String runId) {
        return root.resolve(".oseer/runs").resolve(runId).resolve("narration.log");
    }

    /**
     * The last line of the run's narration, for the tab that follows it. The tab is where the
     * operator was told to decide; without this it ended on the question.
     */
    static void narrateDecision(Path root, String runId, String choice, String actor,
                                        boolean closes) {
        try {
            Path file = narrationFile(root, runId);
            if (!Files.isRegularFile(file)) return;
            Files.writeString(file, System.lineSeparator() + "decision  " + choice
                    + " by " + actor + (closes ? "; this run is closed" : "; the next run says when it starts")
                    + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (Exception notOurProblem) {
            // The decision is recorded; only the narration missed its last line.
        }
    }
}
