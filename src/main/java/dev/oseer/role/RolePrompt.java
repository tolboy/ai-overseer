package dev.oseer.role;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.OverseerTree;
import dev.oseer.config.Profile;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.ApiContextBuilder;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders the role's prompt from the task, profile, and evidence for this dispatch. */
final class RolePrompt {
    static final String VISUAL_SCENARIOS = "visual_scenarios";

    record Request(String role, String runId, String mergeBase, Path contextFile, boolean inheritsUnfinishedWork,
                   List<Map<String, Object>> attempts, List<Path> attachments, Path evidenceDirectory) {}

    record Rendered(String text, String context, boolean backfilledVisualScenarios,
                    Map<String, Object> apiContextEvidence) {}

    static final class ApiContextUnavailable extends IOException {
        private ApiContextUnavailable(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private final ProcessRunner processes;

    RolePrompt(ProcessRunner processes) {
        this.processes = processes;
    }

    Rendered render(ConfigLoader.Loaded loaded, UserConfig user, Profile profile, Request request)
            throws IOException, InterruptedException {
        Map<String, String> values = promptValues(loaded, user, profile, request);
        if (profile.promptTemplate() == null) {
            throw new IllegalStateException("profile '" + profile.name()
                    + "' has no prompt_template; a role with no prompt cannot be run");
        }
        Path templateFile = user.resolve(profile.promptTemplate());
        if (!Files.isRegularFile(templateFile)) {
            throw new IllegalStateException("prompt template not found: " + templateFile);
        }
        String template = Files.readString(templateFile);
        String text = PromptRenderer.render(template, values, templateFile.toString());
        boolean backfilled = backfillNeeded(request.role(), loaded.resolved(), template);
        if (backfilled) {
            text += browserScenarioSection(values.get(VISUAL_SCENARIOS));
        }
        // setup leaves installed prompts in place, so bring older reviewer prompts up to date.
        if (acceptanceBackfillNeeded(request.role(), template)) {
            text += acceptanceEvidenceSection(values.get("acceptance_evidence"));
        }
        if (citationBackfillNeeded(request.role(), template)) {
            text += citationSection();
        }

        Map<String, Object> apiContextEvidence = Map.of();
        if ("api".equals(profile.runner())) {
            try {
                ApiContextBuilder.Snapshot snapshot = ApiContextBuilder.build(
                        loaded.root(), request.mergeBase(), processes, profile.apiContext());
                text += "\n\n" + snapshot.text();
                apiContextEvidence = snapshot.evidence(profile.apiContext());
            } catch (IOException unavailable) {
                throw new ApiContextUnavailable(unavailable);
            }
        }
        return new Rendered(text, values.get("context"), backfilled, apiContextEvidence);
    }

    /**
     * Whether this prompt has to be told about the browser scenarios after the fact.
     *
     * The scenarios are half the definition of done — for a project with no check command
     * they are all of it — and both the reviewer and the visual_qa prompts have always been
     * given them. The implementer, the one role that can actually satisfy them, was not:
     * measured on a live run, where the contract asked for a `data-testid` the harness needs
     * and the implementer's prompt never mentioned it. It would have been found by the
     * browser stage and sent back as a fix round, paying a vendor to learn something the
     * contract already said.
     *
     * The shipped template now names {{visual_scenarios}}. This covers the templates already
     * sitting in an operator's ~/.oseer — `oseer setup` never overwrites them, and no
     * operator ever meant to hide the definition of done from the role expected to meet it.
     */
    private static boolean backfillNeeded(String role, TaskSpec.ResolvedTask task, String template) {
        return "implementer".equals(role)
                && task.visualQa().required()
                && !task.visualQa().scenarios().isEmpty()
                && !PromptRenderer.uses(template, VISUAL_SCENARIOS);
    }

    /** A reviewer prompt that predates {@code {{acceptance_evidence}}}. See the call site. */
    private static boolean acceptanceBackfillNeeded(String role, String template) {
        return "reviewer".equals(role) && !PromptRenderer.uses(template, "acceptance_evidence");
    }

    /** A reviewer prompt that does not yet mention the mechanical citation check. */
    private static boolean citationBackfillNeeded(String role, String template) {
        return "reviewer".equals(role) && !template.contains("unverified");
    }

    private static String citationSection() {
        return """

                ## Citations that can block

                A P1 or P2 product_defect has to name a file that is in the tree. When you name
                a line, that line has to exist. Put current source in `quote`, or use backticks
                in `actual` for a literal source excerpt. Overseer checks within 20 lines of the cited
                line before the finding can block. A citation it cannot check is recorded as
                unverified and does not block. The check reads the files; it does not call a model.

                """;
    }

    private static String acceptanceEvidenceSection(String evidence) {
        return """

                ## Recorded acceptance evidence

                The machine gate's command list is not the whole of what this change already
                ran. The block below was supplied with the task (commands and a recorded CI
                summary). Overseer did not look it up. When it records real acceptance, do
                not file a contract_gap claiming that nothing compiles, that tests do not run,
                or that the only acceptance is a whitespace check. File a contract_gap only
                when those commands do not cover the behaviour under review.

                """ + (evidence == null ? "(none recorded)" : evidence) + "\n";
    }

    /** Items the task already accepts as checked by hand, or an explicit empty list. */
    private static String manualLines(List<String> items) {
        if (items == null || items.isEmpty()) return "(none declared)";
        return bullets(items);
    }

    private static String browserScenarioSection(String scenarios) {
        return """

                ## Browser scenarios that must also pass

                A headless browser runs these against the app after the acceptance commands.
                They are part of the definition of done, not a suggestion: a matcher that finds
                nothing fails the run and comes back to you as a fix round. The grammar is
                `testid=` / `css=` / `role=` / `text=` with `visible`, `hidden`, `click`,
                `click@x,y` or `focused`, plus `press=KEY` (Escape, Enter, Tab, Space, an arrow,
                or one character; a matcher before it is focused first) and `wait` / `wait-for`.

                """ + scenarios + "\n";
    }

    private Map<String, String> promptValues(ConfigLoader.Loaded loaded, UserConfig user,
                                              Profile profile, Request request) throws IOException {
        TaskSpec.ResolvedTask task = loaded.resolved();
        Path contextFile = request.contextFile();
        Path evidenceDirectory = request.evidenceDirectory();
        List<Path> attachments = request.attachments();
        String schemaPretty = "";
        String schemaJson = "";
        if (profile.jsonSchema() != null) {
            Path schemaFile = user.resolve(profile.jsonSchema());
            if (Files.isRegularFile(schemaFile)) {
                Object schema = Json.parse(Files.readString(schemaFile));
                schemaPretty = Json.writePretty(schema);
                schemaJson = Json.write(schema);
            }
        }
        String context = contextFile != null && Files.isRegularFile(contextFile)
                ? Files.readString(contextFile) : "";
        if (request.inheritsUnfinishedWork()) {
            context = handover(request.attempts()) + context;
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_id", task.id());
        values.put("run_id", request.runId());
        values.put("goal", task.goal());
        values.put("non_goals", bullets(task.nonGoals()));
        values.put("risk", task.risk());
        values.put("project", loaded.project().project());
        values.put("base_ref", task.baseRef());
        values.put("diff_base_commit", request.mergeBase());
        values.put("scope_paths", String.join(", ", task.scopePaths()));
        values.put("changed_files", changedFileList(loaded.root(), request.mergeBase()));
        values.put("acceptance_commands", bullets(task.acceptanceCommands()));
        values.put("acceptance_evidence", task.acceptanceEvidence().promptText());
        values.put(VISUAL_SCENARIOS, bullets(task.visualQa().scenarios()));
        values.put("manual_verification", manualLines(task.manualVerification()));
        values.put("authority", "workspace_write=" + task.authority().workspaceWrite()
                + ", network=" + task.authority().network() + ", land=" + task.authority().land());
        values.put("operator_goal", task.goal());
        values.put("named_checks", namedEntries(loaded.project().checks()));
        values.put("named_scopes", namedEntries(loaded.project().scopes()));
        values.put("max_fix_attempts", String.valueOf(task.maxFixAttempts()));
        values.put("timeout_minutes", String.valueOf(task.timeoutMinutes()));
        values.put("budget_max_role_runs", String.valueOf(task.budget().maxRoleRuns()));
        values.put("budget_max_cost_usd", String.valueOf(task.budget().maxCostUsd()));
        values.put("contract_path", loaded.root().relativize(loaded.taskFile()).toString().replace('\\', '/'));
        values.put("schema_json", schemaJson);
        values.put("schema_pretty", schemaPretty);
        values.put("context", context);
        values.put("context_path", contextFile == null ? "" : contextFile.toString());
        values.put("screenshots", attachmentList(attachments));
        values.put("evidence_dir", evidenceDirectory == null ? ""
                : evidenceDirectory.toAbsolutePath().normalize().toString());
        values.put("vision_note", visionNote(profile, attachments, evidenceDirectory));
        return values;
    }

    /**
     * The paths a reviewer has to look at, listed rather than described.
     *
     * The prompt used to name `git diff` and `git ls-files` as the way to find them, and for
     * a profile whose tool grant is Read/Grep/Glob that instruction is an invitation to spend
     * turns on refusals. Measured: an Opus review spent 16 of its 74 tool calls on shell
     * calls it was never allowed to make — every one of them doing exactly what this prompt
     * told it to — and then died at its turn ceiling. A list of paths costs nothing to render
     * and is usable by any profile, with or without a shell.
     */
    private String changedFileList(Path root, String mergeBase) {
        try {
            List<String> paths = new ArrayList<>(
                    new GitRepository(root, processes).changedPaths(mergeBase));
            paths.removeIf(path -> path.equals(OverseerTree.DIRECTORY)
                    || path.startsWith(OverseerTree.DIRECTORY + "/"));
            if (paths.isEmpty()) return "- (nothing changed since the diff base)";
            return bullets(paths.stream().map(path -> "`" + path + "`").toList());
        } catch (Exception unreadable) {
            // A prompt is not the place to fail a run. The reviewer still has the diff base
            // commit and its own tools; it simply does not get the shortcut.
            return "- (Overseer could not list them: " + unreadable.getMessage() + ")";
        }
    }

    /**
     * What a vendor is told when it picks up a role another vendor was cut off from. It is
     * given the fact and no reassurance: the tree it is looking at is partly someone else's
     * work, and pretending otherwise produces a second implementation layered on a first.
     */
    private static String handover(List<Map<String, Object>> attempts) {
        StringBuilder builder = new StringBuilder("# You are continuing an interrupted run\n\n");
        for (Map<String, Object> attempt : attempts) {
            builder.append("- `").append(attempt.get("profile")).append("` (vendor `")
                    .append(attempt.get("vendor")).append("`) stopped with `")
                    .append(attempt.get("code")).append("`.\n");
        }
        builder.append("""

                The previous attempt stopped before producing a completed result. The worktree
                already contains edits it made, and those edits are unreviewed and may be half
                of a larger change.

                Read the current state of the files in scope before writing anything. Finish the
                task as specified — do not restart it from scratch, and do not assume the
                existing edits are correct.

                """);
        return builder.toString();
    }

    /**
     * How the pixels actually reach this vendor, in the words the prompt needs.
     *
     * The two deliveries ask for different behaviour from the model, and getting it wrong is
     * silent: a `workspace_file` profile told the images are "attached" will answer about
     * filenames, and sound just as confident doing it.
     */
    private static String visionNote(Profile profile, List<Path> attachments, Path evidenceDirectory) {
        boolean any = attachments != null && !attachments.isEmpty();
        if (!any && profile.vision() != null && profile.vision().acquires()) {
            return "No screenshot was handed to you: this role takes its own. Drive the "
                    + "application with your own tools (the browser, engine or desktop MCP "
                    + "server this profile is configured with), exercise the scenarios above, "
                    + "and save every screenshot you judge as a PNG file under `"
                    + (evidenceDirectory == null ? "the run's screenshots directory"
                            : evidenceDirectory.toAbsolutePath().normalize())
                    + "`. List those files, as absolute paths, in `screenshots_taken`. Overseer "
                    + "verifies that each listed file exists there and hashes it; a verdict that "
                    + "lists none is refused as having no evidence. Do not edit the source tree.";
        }
        if (!any) return "No screenshot reached this run. Say so and return status: \"aborted\".";
        String delivery = profile.vision() == null ? null : profile.vision().delivery();
        if ("cli_attachment".equals(delivery)) {
            return "The images below are attached to this message as image data. Look at them.";
        }
        if ("workspace_file".equals(delivery)) {
            return "The images below are absolute paths in this workspace, not attachments. "
                    + "**Open every one with your own image-reading tool before you answer.** "
                    + "If your tools cannot open an image, say so in `summary` and return "
                    + "status: \"aborted\" — a verdict on files you did not open is worth "
                    + "less than an honest refusal, and Overseer checks for exactly that.";
        }
        return "The images below are paths. This profile declares no vision capability, so "
                + "whether you can see them at all is unproven; if you cannot, say so and "
                + "return status: \"aborted\".";
    }

    private static String attachmentList(List<Path> attachments) {
        if (attachments == null || attachments.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Path attachment : attachments) {
            builder.append("- ").append(attachment.toAbsolutePath()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private static String bullets(List<String> items) {
        if (items == null || items.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (String item : items) builder.append("- ").append(item).append('\n');
        return builder.toString().stripTrailing();
    }

    /**
     * Named project entries as the planner (and any later reader) must see them: the name
     * Overseer will accept, and the value that name already resolves to. A model that invents
     * a fourth name is refused at compile time; this list is what "already exists" means.
     */
    private static String namedEntries(Map<String, ? extends List<String>> named) {
        if (named == null || named.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, ? extends List<String>> entry : named.entrySet()) {
            builder.append("- `").append(entry.getKey()).append("`: ")
                    .append(entry.getValue()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

}
