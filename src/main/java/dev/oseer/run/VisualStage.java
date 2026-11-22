package dev.oseer.run;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.UserConfig;
import dev.oseer.config.Workflow;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.ledger.CitationCheck;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.ledger.Findings;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The browser harness, and the role that looks at the pictures.
 *
 * A self-acquiring visual role is believed only for screenshots that exist inside
 * the run's own evidence. A verdict on pictures nobody can find is not evidence.
 */
final class VisualStage {
    private final ProcessRunner processes;
    private final TaskLoop.VisualCheck visualCheck;

    VisualStage(ProcessRunner processes, TaskLoop.VisualCheck visualCheck) {
        this.processes = processes;
        this.visualCheck = visualCheck;
    }

    VisualQaRunner.Outcome runVisual(ConfigLoader.Loaded loaded, String evidenceRunId, int attempt,
                                             boolean dryRun, List<Map<String, Object>> steps,
                                             Path home)
            throws Exception {
        if (dryRun) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", "visual_qa");
            entry.put("attempt", (long) attempt);
            entry.put("dry_run", true);
            steps.add(entry);
            return null;
        }
        VisualQaRunner.Outcome outcome = visualCheck != null
                ? visualCheck.run(loaded, evidenceRunId)
                : new VisualQaRunner(processes).withHome(home).run(loaded, evidenceRunId);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", "visual_qa");
        entry.put("attempt", (long) attempt);
        entry.put("ok", outcome.ok());
        entry.put("code", outcome.code());
        entry.put("report", String.valueOf(outcome.report()));
        if (StageIds.hasDedicatedEvidenceDirectory(evidenceRunId, "visual-qa", attempt)) {
            entry.put("evidence_dir", evidenceRunId);
        }
        steps.add(entry);
        return outcome;
    }

    /**
     * The role that looks at the screenshots. It is dispatched only when the policy names a
     * `visual_qa` role: the machine harness is the floor, and paying a model to look on top of
     * it is a decision an operator makes once, in policy, not one this loop makes for them.
     *
     * It receives every screenshot the harness produced, as attachments where the vendor
     * supports them, plus the harness report as context so it does not re-argue measurements.
     */
    RoleRunner.Outcome runVisualRole(ConfigLoader.Loaded loaded, UserConfig user, RoleRunner roles,
                                     String runId, Workflow workflow, Workflow.Stage stage, int attempt,
                                     RoleResolver.Writers writers, VisualQaRunner.Outcome visual,
                                     boolean dryRun, List<Map<String, Object>> steps,
                                     RunBudget budget) throws Exception {
        String evidenceRunId = StageIds.stageRunId(runId, workflow, stage, attempt);
        boolean dedicated = StageIds.hasDedicatedEvidenceDirectory(evidenceRunId, "visual-role", attempt);
        List<Path> screenshots = visual == null ? List.of() : screenshotsOf(loaded.root(), visual);
        Path context = null;
        if (visual != null) {
            EvidenceLedger ledger = new EvidenceLedger(loaded.root(), runId, user.home());
            Path directory = ledger.runDirectory().resolve("context");
            Files.createDirectories(directory);
            String contextId = dedicated ? evidenceRunId.substring(runId.length() + 2) : String.valueOf(attempt);
            context = directory.resolve("visual-" + contextId + "-harness.json");
            Files.writeString(context, Json.writePretty(visual.data()) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        RoleRunner.Outcome outcome = roles.run(loaded, user, "visual_qa",
                evidenceRunId, writers, context, screenshots, dryRun, RoleRunner.UNSTAGED);
        if (!dryRun && outcome.ok()) {
            if (stage.acquiresEvidence()) outcome = verifyAcquiredEvidence(loaded.root(), outcome);
            else if (screenshots.isEmpty()) outcome = withVisualCitations(loaded.root(), outcome, List.of());
        }
        StepRecord.record(steps, budget, "visual_qa", attempt, outcome);
        if (dryRun) return null;
        if (dedicated && outcome.report() != null) {
            Map<String, Object> entry = steps.getLast();
            entry.put("evidence_dir", evidenceRunId);
            entry.put("report", outcome.report().toString());
        }
        return outcome;
    }

    /**
     * The pixels a self-acquiring visual role says it looked at, checked rather than believed.
     *
     * Each path in {@code screenshots_taken} has to be an existing, non-empty file inside the
     * project's own run evidence; anything else is a claim about a file Overseer cannot vouch
     * for. What survives is hashed onto the outcome as {@code image_evidence}, the same shape
     * the harness records, so a report reads both kinds alike. A verdict that lists nothing
     * that survives is not a verdict: the role answered from something other than pictures.
     */
    private RoleRunner.Outcome verifyAcquiredEvidence(Path root, RoleRunner.Outcome outcome)
            throws Exception {
        Map<String, Object> artifact = RepairBrief.readArtifact(root, outcome);
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        Path directory = screenshotDirectory(root, outcome.report());
        List<Path> screenshots = new ArrayList<>();
        if (artifact != null && artifact.get("screenshots_taken") instanceof List<?> listed) {
            for (Object item : listed) {
                if (!(item instanceof String text) || text.isBlank()) continue;
                Path file = screenshotPath(root, directory, text);
                if (file == null) {
                    refused.add(text + " (not a file inside the run's evidence)");
                    continue;
                }
                if (!AgentImageEvidence.isImage(file)) {
                    refused.add(text + " (not a decodable bounded image)");
                    continue;
                }
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("path", file.toString());
                image.put("sha256", GitRepository.contentSha256(file));
                image.put("bytes", Files.size(file));
                evidence.add(image);
                screenshots.add(file);
            }
        }
        Map<String, Object> details = new LinkedHashMap<>(
                outcome.details() == null ? Map.of() : outcome.details());
        details.put("evidence", "agent");
        details.put("image_evidence", evidence);
        if (!refused.isEmpty()) details.put("screenshots_refused", refused);
        if (!evidence.isEmpty()) {
            RoleRunner.Outcome verified = new RoleRunner.Outcome(true, outcome.code(), outcome.role(),
                    outcome.profile(), outcome.vendor(), outcome.rejected(), outcome.report(), details);
            return withVisualCitations(root, verified, screenshots);
        }
        details.put("resolution", "the visual role answered without listing a screenshot it took "
                + "inside the run's evidence directory. A verdict on pictures nobody can find is "
                + "not evidence; check that the profile's MCP servers can drive the application "
                + "and that the role writes its screenshots where the prompt says.");
        return new RoleRunner.Outcome(false, "role_visual_no_evidence", outcome.role(),
                outcome.profile(), outcome.vendor(), outcome.rejected(), outcome.report(), details);
    }

    private static RoleRunner.Outcome withVisualCitations(Path root, RoleRunner.Outcome outcome,
                                                           List<Path> screenshots) {
        Map<String, Object> details = new LinkedHashMap<>(outcome.details());
        List<Findings.Finding> found = Findings.recorded(details.get("findings"));
        if (!found.isEmpty()) {
            details.put("findings", CitationCheck.verifyVisual(found, root, screenshots).stream()
                    .map(Findings.Finding::toMap).toList());
        }
        return new RoleRunner.Outcome(outcome.ok(), outcome.code(), outcome.role(), outcome.profile(),
                outcome.vendor(), outcome.rejected(), outcome.report(), details);
    }

    /** The image evidence recorded by this harness, under this harness's screenshots directory. */
    private static List<Path> screenshotsOf(Path root, VisualQaRunner.Outcome visual) {
        Path directory = screenshotDirectory(root, visual.report());
        if (visual.data() == null || !(visual.data().get("image_evidence") instanceof List<?> rows)) {
            return List.of();
        }
        Set<Path> files = new LinkedHashSet<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> image) || !(image.get("path") instanceof String text)) continue;
            Path file = screenshotPath(root, directory, text);
            if (file != null) files.add(file);
        }
        return List.copyOf(files);
    }

    private static Path screenshotDirectory(Path root, Path report) {
        if (report == null) return null;
        try {
            Path runs = root.toRealPath().resolve(".oseer/runs");
            Path run = report.toAbsolutePath().normalize().getParent();
            if (run == null || run.getParent() == null || !runs.equals(run.getParent().toRealPath())) return null;
            Path directory = runs.resolve(run.getFileName()).resolve("screenshots");
            // Resolve aliases of the project directory, but keep the expected run and its
            // screenshots directory anchored by name. A link to another run cannot count.
            if (directory.equals(run.resolve("screenshots").toRealPath()) && Files.isDirectory(directory)) {
                return directory;
            }
        } catch (java.io.IOException unreadable) {
            // Without the run's own directory there is no screenshot evidence to attach.
        }
        return null;
    }

    private static Path screenshotPath(Path root, Path directory, String text) {
        if (directory == null) return null;
        try {
            Path project = root.toRealPath();
            Path file = root.resolve(Path.of(text)).toRealPath();
            if (directory.startsWith(project) && file.startsWith(directory)
                    && Files.isRegularFile(file) && Files.size(file) > 0) {
                // Keep the path in the caller's spelling of the project for attachments and reports.
                return root.toAbsolutePath().normalize().resolve(project.relativize(file));
            }
        } catch (InvalidPathException | java.io.IOException unreadable) {
            // Invalid or unreadable entries are not evidence.
        }
        return null;
    }

}
