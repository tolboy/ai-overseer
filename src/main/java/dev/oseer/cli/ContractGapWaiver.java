package dev.oseer.cli;

import dev.oseer.approval.ApprovalException;
import dev.oseer.approval.HumanDecision;
import dev.oseer.json.Json;
import dev.oseer.ledger.Findings;
import dev.oseer.run.NextStep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.oseer.cli.ContinuationPolicy.CONTRACT_GAP_STOPS;

/** The named blocker and all other open contract gaps closed by the same decision. */
record ContractGapWaiver(String findingId, List<String> waivedFindings) {
    Map<String, Object> view(String actor, String note) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("finding_id", findingId);
        view.put("waived_findings", waivedFindings);
        view.put("note", note == null ? "" : note);
        view.put("actor", actor);
        view.put("cause", "contract_gap");
        return view;
    }

    /**
     * Refuse {@code waive} unless this stop itself is caused by contract-gap findings and
     * every open P1 is a contract_gap, the named finding among them. A continuation
     * restores earlier findings, so an inherited gap can still be open when the new run
     * stops on a machine gate; that history is not this stop. The decision stays pending
     * when this throws.
     */
    static ContractGapWaiver validate(Path root, HumanDecision pending, String findingId)
            throws IOException {
        if (pending.kind() != HumanDecision.Kind.FAILURE) {
            throw new ApprovalException("waive_refused",
                    "waive is a failure option; run " + pending.runId() + " is "
                            + pending.kind().jsonValue());
        }
        if (findingId == null || findingId.isBlank()) {
            throw new ApprovalException("waive_refused",
                    "waive requires --finding <ID>, the contract_gap to accept.");
        }
        Map<String, Object> summary = decisionSummary(root, pending);
        String stop = pending.reason();
        Object reported = summary.get("reason");
        if (!CONTRACT_GAP_STOPS.contains(stop)
                || !(reported instanceof String reportedReason && reportedReason.equals(stop))) {
            throw new ApprovalException("waive_refused",
                    "waive refused: run " + pending.runId() + " stopped for " + stop
                            + ". Waive closes a stop caused solely by contract-gap findings, "
                            + "not a machine-gate failure or any other reason.");
        }
        List<Map<String, Object>> open = openBlockingFindings(summary);
        if (open.isEmpty()) {
            throw new ApprovalException("waive_refused",
                    "this run has no open contract_gap to waive.");
        }
        Map<String, Object> named = null;
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> finding : open) {
            String id = finding.get("id") instanceof String text ? text : "";
            if (!id.isBlank()) ids.add(id);
            if (findingId.equals(id)) named = finding;
        }
        if (named == null) {
            throw new ApprovalException("waive_refused",
                    "waive refused: no open P1 with id " + findingId + ". Open blockers: "
                            + String.join(", ", ids));
        }
        String namedCategory = findingCategory(named);
        if (!"contract_gap".equals(namedCategory)) {
            if ("product_defect".equals(namedCategory)) {
                throw new ApprovalException("waive_refused",
                        "waive refused: finding " + findingId
                                + " is a product_defect. A product defect stays blocking; "
                                + "waive is only for a contract_gap.");
            }
            throw new ApprovalException("waive_refused",
                    "waive refused: finding " + findingId + " is a " + namedCategory
                            + ". Waive is only for a contract_gap.");
        }
        for (Map<String, Object> finding : open) {
            String category = findingCategory(finding);
            if ("contract_gap".equals(category)) continue;
            throw new ApprovalException("waive_refused",
                    "waive refused: run " + pending.runId()
                            + " still has a blocking finding that is not a contract_gap ("
                            + finding.get("id") + ", " + category
                            + "). Waive closes a stop whose blockers are only contract gaps.");
        }
        return new ContractGapWaiver(findingId, List.copyOf(ids));
    }

    /** The task summary the decision points at. Missing is a refusal, not an empty stop. */
    private static Map<String, Object> decisionSummary(Path root, HumanDecision pending)
            throws IOException {
        Path summaryPath = Path.of(pending.summaryPath());
        if (!summaryPath.isAbsolute()) summaryPath = root.resolve(summaryPath);
        if (!Files.isRegularFile(summaryPath)) {
            throw new ApprovalException("waive_refused",
                    "task summary could not be read at " + pending.summaryPath());
        }
        return Json.parseObject(Files.readString(summaryPath));
    }

    /**
     * Open P1s across the run's finding registry, the same set
     * {@link NextStep} routes by. A downgraded advisory is already P2.
     * A closed record is not a blocker; an open or reopened one is, including a product
     * defect another reviewer filed and a later recheck did not mention.
     */
    private static List<Map<String, Object>> openBlockingFindings(Map<String, Object> summary) {
        if (summary == null || !(summary.get("finding_history") instanceof List<?> rounds)) {
            return List.of();
        }
        return Findings.openBlockers(rounds);
    }

    /** Absent or unrecognised category is a product defect, matching {@link Findings}. */
    private static String findingCategory(Map<String, Object> finding) {
        Object raw = finding.get("category");
        String category = raw instanceof String text ? text : "";
        return Findings.CATEGORIES.contains(category)
                ? category : Findings.DEFAULT_CATEGORY;
    }
}
