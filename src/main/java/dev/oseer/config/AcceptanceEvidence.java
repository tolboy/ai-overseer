package dev.oseer.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Acceptance a review task already has, supplied by the operator rather than discovered live.
 *
 * A review's local machine gate is often only {@code git diff --check}. The pull request's
 * own build and tests, and the CI result that already ran them, are the acceptance readers
 * need to see. This record is that evidence: command lines and a recorded check summary
 * (for example the text of {@code gh pr checks}), never a network call.
 */
public record AcceptanceEvidence(List<String> commands, String ciSource, String ciSummary,
                                 List<Check> checks) {

    /** Conclusions a recorded check-run may carry. {@code pass} and {@code fail} are accepted as aliases. */
    public static final Set<String> CONCLUSIONS = Set.of(
            "success", "failure", "neutral", "cancelled", "skipped", "timed_out",
            "action_required", "startup_failure");

    public static final AcceptanceEvidence NONE =
            new AcceptanceEvidence(List.of(), null, null, List.of());

    public record Check(String name, String conclusion) {}

    public AcceptanceEvidence {
        commands = commands == null ? List.of() : List.copyOf(commands);
        checks = checks == null ? List.of() : List.copyOf(checks);
        if (ciSource != null && ciSource.isBlank()) ciSource = null;
        if (ciSummary != null && ciSummary.isBlank()) ciSummary = null;
    }

    /** Whether the task recorded anything a reader should treat as acceptance. */
    public boolean present() {
        return !commands.isEmpty() || ciSummary != null || !checks.isEmpty();
    }

    /**
     * Whether the recorded evidence is real acceptance rather than a failed or empty CI note.
     *
     * Named commands count: the operator is saying these are the pull request's tests.
     * A check counts when its conclusion is success. A pasted table counts when it names
     * a passing check and reports no failed checks.
     */
    public boolean recordsRealAcceptance() {
        if (!commands.isEmpty()) return true;
        for (Check check : checks) {
            if ("success".equals(check.conclusion())) return true;
        }
        if (ciSummary == null) return false;
        boolean passed = false;
        for (String row : ciSummary.lines().toList()) {
            String[] columns = row.strip().split("\\t+| {2,}", 3);
            if (columns.length < 2) columns = row.strip().split(":\\s+", 2);
            if (columns.length < 2) columns = row.strip().split("\\s+", 2);
            if (columns.length < 2) continue;
            switch (columns[1].strip().toLowerCase(Locale.ROOT)) {
                case "pass", "passed", "success" -> passed = true;
                case "fail", "failed", "failure", "timed_out", "startup_failure" -> { return false; }
                default -> { }
            }
        }
        return passed;
    }

    /** The block substituted for {@code {{acceptance_evidence}}} in a reader prompt. */
    public String promptText() {
        if (!present()) return "(none recorded)";
        StringBuilder text = new StringBuilder();
        text.append("Recorded acceptance evidence (supplied with the task; Overseer did not look this up):\n");
        if (!commands.isEmpty()) {
            text.append("\nCommands the pull request runs:\n");
            for (String command : commands) text.append("- `").append(command).append("`\n");
        }
        if (ciSource != null || !checks.isEmpty() || ciSummary != null) {
            text.append("\nCI");
            if (ciSource != null) text.append(" (").append(ciSource).append(')');
            text.append(":\n");
            for (Check check : checks) {
                text.append("- ").append(check.name()).append(": ").append(check.conclusion()).append('\n');
            }
            if (ciSummary != null) {
                text.append("\nRecorded summary:\n");
                text.append(ciSummary.strip()).append('\n');
            }
        }
        if (recordsRealAcceptance()) {
            text.append("""

                    This is real acceptance. The local machine gate may still be only `git diff --check`.
                    Do not file a contract_gap claiming that nothing compiles, that tests do not run, or
                    that the only acceptance is a whitespace check. File a contract_gap only when these
                    commands do not cover the behaviour under review.
                    """);
        }
        return text.toString().stripTrailing();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        if (!commands.isEmpty()) value.put("commands", commands);
        if (ciSource != null) value.put("ci_source", ciSource);
        if (ciSummary != null) value.put("ci_summary", ciSummary);
        if (!checks.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Check check : checks) {
                rows.add(Map.of("name", check.name(), "conclusion", check.conclusion()));
            }
            value.put("checks", rows);
        }
        value.put("records_real_acceptance", recordsRealAcceptance());
        return value;
    }

    /** A stable fragment for the acceptance fingerprint. Empty when nothing was recorded. */
    public String fingerprintText() {
        if (!present()) return "";
        StringBuilder text = new StringBuilder();
        text.append(String.join("\u0000", commands));
        text.append('\u0001');
        text.append(ciSource == null ? "" : ciSource);
        text.append('\u0001');
        text.append(ciSummary == null ? "" : ciSummary);
        for (Check check : checks) {
            text.append('\u0001').append(check.name()).append('=').append(check.conclusion());
        }
        return text.toString();
    }
}
