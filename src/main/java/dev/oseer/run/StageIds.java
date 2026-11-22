package dev.oseer.run;

import dev.oseer.config.Workflow;

import java.util.Set;

/**
 * Where one stage's evidence goes.
 *
 * A single stage keeps its historical directory. When several stages perform the same
 * check or role, each needs a directory for its receipts, output and screenshots.
 */
final class StageIds {
    // Match the run identifier accepted by EvidenceLedger.
    private static final int MAX_RUN_ID_LENGTH = 80;
    private static final Set<String> PREPARATION_LABELS = Set.of("baseline", "reproduction");

    private StageIds() {}

    static String stepRunId(String runId, String role, int attempt) {
        return runId + "--" + role + "-" + attempt;
    }

    /**
     * Keep the historical path wherever its label identifies exactly one dispatch.
     */
    static String stageRunId(String runId, Workflow workflow, Workflow.Stage stage, int attempt) {
        String legacyLabel = legacyLabel(workflow, stage);
        boolean shared = workflow.stages().stream()
                .anyMatch(other -> !other.equals(stage) && legacyLabel.equals(legacyLabel(workflow, other)));
        boolean sharesRepair = attempt > 0 && workflow.stages().stream().anyMatch(other ->
                legacyLabel.equals(other.fixWith())
                        && ("fix".equals(other.onFail()) || "fix".equals(other.onFindings())));
        if (!shared && !sharesRepair && !PREPARATION_LABELS.contains(legacyLabel)) {
            return stepRunId(runId, legacyLabel, attempt);
        }

        // A leading hyphen is reserved for these IDs. Old role slugs cannot start with
        // it. The position distinguishes names with the same slug on case-insensitive
        // filesystems and remains stable between attempts in this workflow.
        String label = "-" + (workflow.stages().indexOf(stage) + 1);
        int available = MAX_RUN_ID_LENGTH - stepRunId(runId, label, attempt).length() - 1;
        if (available > 0) {
            String slug = Workflow.slug(stage.name()).replaceAll("-+", "-");
            label += "-" + slug.substring(0, Math.min(available, slug.length()));
        }
        return stepRunId(runId, label, attempt);
    }

    private static String legacyLabel(Workflow workflow, Workflow.Stage stage) {
        return switch (stage.kind()) {
            case MACHINE_GATES -> "gates";
            case VISUAL_HARNESS -> "visual-qa";
            case ROLE -> {
                if ("visual_qa".equals(stage.role())) yield "visual-role";
                yield workflow.stagesFor(stage.role()).size() > 1 ? Workflow.slug(stage.name()) : stage.label();
            }
        };
    }

    static boolean isDedicated(String runId, String evidenceRunId) {
        return evidenceRunId.startsWith(runId + "---");
    }

    /** Namespaced labels never end in a double hyphen followed by a legacy label. */
    static boolean hasDedicatedEvidenceDirectory(String evidenceRunId, String legacyLabel, int attempt) {
        return !evidenceRunId.endsWith("--" + legacyLabel + "-" + attempt);
    }

}
