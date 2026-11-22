package dev.oseer.run;

import dev.oseer.gate.GateRunner;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.json.Json;
import dev.oseer.ledger.CitationCheck;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.ledger.Findings;
import dev.oseer.role.RoleRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a repair or a re-reading is handed, and the findings read back from it.
 *
 * The file a vendor wrote is left as they wrote it. Citations, manual verification
 * and acceptance evidence are applied to the copy the loop routes on.
 */
final class RepairBrief {
    private RepairBrief() {}

    static Path writeContext(EvidenceLedger ledger, int attempt, String kind, String body)
            throws Exception {
        Path directory = ledger.runDirectory().resolve("context");
        Files.createDirectories(directory);
        Path file = directory.resolve("fix-" + attempt + "-" + kind + ".md");
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * What the implementer is handed when the browser disagreed with the contract. The
     * harness already wrote down which assertion failed and why; repeating that here beats
     * a pointer to a file the vendor may not open.
     */
    static String visualContext(VisualQaRunner.Outcome visual) {
        StringBuilder builder = new StringBuilder("# The browser check failed\n\n");
        Object adapter = visual.data().get("adapter");
        if (adapter instanceof Map<?, ?> body && body.get("scenarios") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> scenario) || Boolean.TRUE.equals(scenario.get("ok"))) continue;
                builder.append("## `").append(scenario.get("raw")).append("`\n\n");
                if (scenario.get("why") != null) {
                    builder.append("- why: ").append(scenario.get("why")).append('\n');
                }
                if (scenario.get("viewport_effective") instanceof Map<?, ?> effective) {
                    builder.append("- rendered at: ").append(effective.get("width")).append('x')
                            .append(effective.get("height")).append('\n');
                }
                if (scenario.get("console_errors") instanceof List<?> errors && !errors.isEmpty()) {
                    builder.append("- console errors: ").append(errors).append('\n');
                }
                if (scenario.get("screenshot") != null) {
                    builder.append("- screenshot: `").append(scenario.get("screenshot")).append("`\n");
                }
                builder.append('\n');
            }
        } else {
            builder.append("Report: `").append(visual.report()).append("`\n\n");
        }
        builder.append("""
                Fix the page, not the scenario. The visual contract lives in the task file, which
                is hashed — editing it to make the check agree aborts the run.

                If the scenario is genuinely wrong about the page — it names a control that was
                renamed, or asserts a viewport the design does not support — say so in your
                summary with what the correct assertion would be, and change nothing else.
                """);
        return builder.toString();
    }

    static String gateContext(GateRunner.Outcome gate) {
        StringBuilder body = new StringBuilder("""
                # Machine gate failed

                Failure: `%s`
                Full report: `%s`

                """.formatted(gate.code(), gate.report()));
        Object violations = gate.data() == null ? null : gate.data().get("violations");
        if (violations instanceof List<?> list && !list.isEmpty()) {
            body.append("Paths changed outside the declared scope:\n\n");
            for (Object path : list) body.append("- `").append(path).append("`\n");
            body.append('\n');
        }
        Object commands = gate.data() == null ? null : gate.data().get("commands");
        if (commands instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> command && command.get("exit_code") instanceof Number n
                        && n.longValue() != 0) {
                    body.append("Failing command: `").append(command.get("command")).append("`\n");
                    body.append("Exit code: ").append(command.get("exit_code")).append("\n\n");
                }
            }
        }
        body.append("""
                Fix the cause, not the check. Do not edit the task contract, the declared scope or
                the acceptance commands to make this pass — the configuration is hashed and a
                change to it aborts the run.
                """);
        return body.toString();
    }

    static long blockingFindings(Path root, RoleRunner.Outcome review, List<String> manual,
                                  boolean realAcceptance)
            throws Exception {
        return acknowledgedFindings(root, review, manual, realAcceptance).stream()
                .filter(Findings.Finding::blocking).count();
    }

    /**
     * The reviewer's findings after a task's {@code manual_verification} list and any recorded
     * acceptance evidence have been applied. The file the vendor wrote is left as they wrote it.
     */
    static List<Findings.Finding> acknowledgedFindings(Path root, RoleRunner.Outcome review,
                                                        List<String> manual, boolean realAcceptance)
            throws Exception {
        Map<String, Object> artifact = readArtifact(root, review);
        if (artifact == null) return List.of();
        return citedFindings(root, review, artifact, manual, realAcceptance);
    }

    private static List<Findings.Finding> citedFindings(Path root, RoleRunner.Outcome review,
                                                        Map<String, Object> artifact, List<String> manual,
                                                        boolean realAcceptance) {
        if ("visual_qa".equals(review.role())) return Findings.recorded(review.details().get("findings"));
        return CitationCheck.verify(Findings.adjusted(Findings.of(artifact), manual, realAcceptance), root);
    }

    /**
     * Product findings from each stage's latest round whose citation was not in the tree.
     * Recomputed from the history so a later reading that cites a real line drops the flag.
     */
    static List<Map<String, Object>> unverifiedFindings(List<Map<String, Object>> history) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> round : history) {
            if (round != null && round.get("stage") instanceof String stage) latest.put(stage, round);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> round : latest.values()) {
            if (!(round.get("findings") instanceof List<?> findings)) continue;
            for (Object item : findings) {
                if (!(item instanceof Map<?, ?> finding) || !Boolean.TRUE.equals(finding.get("unverified"))) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", finding.get("id"));
                if (finding.get("reviewer_finding_id") != null) {
                    row.put("reviewer_finding_id", finding.get("reviewer_finding_id"));
                }
                row.put("stage", round.get("stage"));
                row.put("path", finding.get("path"));
                row.put("severity", finding.get("severity"));
                if (finding.get("severity_as_filed") != null) row.put("severity_as_filed", finding.get("severity_as_filed"));
                row.put("verification_reason", finding.get("verification_reason"));
                row.put("verification_detail", finding.get("verification_detail"));
                rows.add(row);
            }
        }
        return rows;
    }

    static String reviewContext(Path root, RoleRunner.Outcome review, java.util.Set<String> severities,
                                 List<String> manual, boolean realAcceptance)
            throws Exception {
        Map<String, Object> artifact = readArtifact(root, review);
        StringBuilder builder = new StringBuilder("# An independent reading asked for changes\n\n");
        if (artifact == null) return builder.append("(the reviewer artifact could not be read)\n").toString();
        builder.append("Reviewer summary: ").append(artifact.get("summary")).append("\n\n");
        for (Findings.Finding finding : citedFindings(root, review, artifact, manual, realAcceptance)) {
            // An unverified citation is not work for the implementer. It is already excluded
            // from the blocking count; leaving it in the repair prompt would send them to
            // edit a line the finding did not actually show.
            if (finding.unverified()) continue;
            // P1 always; P2 and P3 when the policy repairs them before the human gate. A
            // non-blocking finding handed over here is what the operator would otherwise have
            // had to read at the gate and send back by hand.
            if (!finding.blocking() && !severities.contains(finding.severity())) continue;
            Map<String, Object> raw = finding.raw();
            builder.append("## ").append(finding.id()).append(" — ").append(finding.path());
            if (raw.get("line") != null) builder.append(':').append(raw.get("line"));
            builder.append("\n\n")
                    .append("- severity: ").append(finding.severity()).append('\n')
                    .append("- category: ").append(finding.category()).append('\n')
                    .append("- expected: ").append(raw.get("expected")).append('\n')
                    .append("- actual: ").append(raw.get("actual")).append('\n');
            if (raw.get("scenario") != null) {
                builder.append("- reproduce: ").append(raw.get("scenario")).append('\n');
            }
            builder.append('\n');
        }
        builder.append("""
                Each finding states an expected and an actual. Address the difference, or, if the
                reviewer is wrong, say so in your summary with the evidence that shows it — do not
                silently leave a finding unaddressed.

                Its `category` says what kind of problem the reviewer thinks it is. Only
                `product_defect` is reliably yours: a `contract_gap`, an `access_required` or a
                `review_disagreement` is usually resolved by a person changing the task, not by
                editing this diff.

                If a finding is not something a change to this diff can fix — the acceptance
                command is too weak, an access grant is missing, the contract itself is wrong —
                say that in your summary and change nothing on its account. A repair that leaves
                the candidate byte-for-byte unchanged ends the run rather than buying another
                reading of the same bytes, so an honest "this one is not mine to fix" is worth
                more here than a token edit.

                Quote each finding's id in your summary and say what you did about it, so the
                next reading can check closure instead of starting over.
                """);
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readArtifact(Path root, RoleRunner.Outcome review) throws Exception {
        Object path = review.details() == null ? null : review.details().get("artifact_path");
        if (!(path instanceof String relative)) return null;
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file)) return null;
        Object parsed = Json.parse(Files.readString(file));
        return parsed instanceof Map ? (Map<String, Object>) parsed : null;
    }

}
