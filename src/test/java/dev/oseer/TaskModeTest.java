package dev.oseer;

import dev.oseer.config.ConfigException;
import dev.oseer.config.ProjectConfig;
import dev.oseer.config.TaskDraft;
import dev.oseer.config.TaskSpec;
import dev.oseer.dashboard.NextRun;
import dev.oseer.dashboard.SettingsEditor;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Task modes are presets that fill what a task leaves unset and add what the kind of work needs. */
public final class TaskModeTest implements Suite {
    public String name() { return "task-modes"; }

    private static final String PROJECT = """
            version: 1
            project: demo
            base_ref: HEAD
            checks:
              fast: ["make test"]
              repro: ["make repro"]
            scopes:
              code: [src]
            defaults:
              checks: fast
              risk: %s
            %s
            """;

    public void run(Check check) throws Exception {
        ProjectConfig lowNoBaseline = ProjectConfig.parse(PROJECT.formatted("low", ""), "project.yaml");
        ProjectConfig lowWithBaseline = ProjectConfig.parse(PROJECT.formatted("low", "  baseline_checks: fast"), "project.yaml");

        TaskSpec quick = TaskSpec.parse(task("mode: quick"), "quick.yaml");
        check.eq("quick sets low risk", "low", quick.risk());
        check.eq("quick allows one fix round", 1L, quick.maxFixAttempts());
        check.eq("quick allows three role calls", 3L, quick.budget().maxRoleRuns());
        TaskSpec explicit = TaskSpec.parse(task("mode: quick\nrisk: high\nmax_fix_attempts: 3\nbudgets: { max_role_runs: 9 }"), "q.yaml");
        check.eq("an explicit risk wins over the preset", "high", explicit.risk());
        check.eq("an explicit fix limit wins", 3L, explicit.maxFixAttempts());
        check.eq("an explicit budget wins", 9L, explicit.budget().maxRoleRuns());
        check.eq("no mode is no preset", null, TaskSpec.parse(task(""), "t.yaml").mode());
        check.eq("task mode is recorded but changes nothing", 6L, TaskSpec.parse(task("mode: task"), "t.yaml").budget().maxRoleRuns());

        check.rejects("an unknown mode is refused", "mode", () -> TaskSpec.parse(task("mode: yolo"), "y.yaml"));
        check.rejects("bug needs a reproduction", "reproduce", () -> TaskSpec.parse(task("mode: bug"), "b.yaml"));
        TaskSpec bug = TaskSpec.parse(task("mode: bug\nreproduce: [\"make repro\"]\nacceptance: [\"make repro\"]"), "b.yaml");
        check.eq("bug with a reproduction parses", "bug", bug.mode());

        check.rejects("refactor is reviewed work", "risk must be medium", () -> TaskSpec.parse(task("mode: refactor\nrisk: low"), "r.yaml"));
        TaskSpec refactor = TaskSpec.parse(task("mode: refactor"), "r.yaml");
        check.rejects("refactor needs a baseline", "baseline_checks", () -> refactor.resolve(lowNoBaseline, "r.yaml"));
        var resolved = refactor.resolve(lowWithBaseline, "r.yaml");
        check.eq("refactor raises a low project default to medium", "medium", resolved.risk());
        check.eq("quick resolves low against any project default", "low",
                quick.resolve(ProjectConfig.parse(PROJECT.formatted("high", ""), "project.yaml"), "q.yaml").risk());

        // review: an existing branch is read, never written.
        check.rejects("review needs to know who wrote the branch", "prior_writers",
                () -> TaskSpec.parse(task("mode: review"), "rv.yaml"));
        check.rejects("review dispatches no fix round", "max_fix_attempts",
                () -> TaskSpec.parse(task("mode: review\nprior_writers: [codex]\nmax_fix_attempts: 2"), "rv.yaml"));
        TaskSpec review = TaskSpec.parse(task("mode: review\nprior_writers: [codex]"), "rv.yaml");
        check.eq("review records its mode", "review", review.mode());
        check.eq("review forces zero fix rounds", 0L, review.maxFixAttempts());
        var reviewResolved = review.resolve(lowNoBaseline, "rv.yaml");
        check.that("review resolves to a review-only task", reviewResolved.reviewOnly());
        check.eq("review keeps its authors", java.util.List.of("codex"), reviewResolved.priorWriters());
        check.that("an ordinary task is not review-only", !quick.resolve(lowNoBaseline, "q.yaml").reviewOnly());
        check.that("review-only changes what a verdict was reached under",
                !reviewResolved.acceptanceFingerprint().equals(TaskSpec.parse(task("prior_writers: [codex]"), "t.yaml")
                        .resolve(lowNoBaseline, "t.yaml").acceptanceFingerprint()));
        check.that("oseer do cannot draft a review", !TaskSpec.DRAFT_MODES.contains("review"));
        check.rejects("oseer do --mode review is refused with the list it accepts", "--mode must be one of",
                () -> dev.oseer.run.DoCommand.parse(new String[] {"do", "Review it", "--mode", "review"}));

        // `oseer do --mode` writes the mode into a contract it drafted, and never into one a person wrote.
        Path root = Files.createTempDirectory("oseer-task-mode-");
        Files.createDirectories(root.resolve(".oseer/tasks"));
        var drafted = new TaskDraft().write(root, "fresh", "Make the button blue", "code", "medium");
        check.eq("a fresh draft takes the mode", null, TaskDraft.applyMode(drafted, "quick"));
        TaskSpec written = TaskSpec.parse(Files.readString(drafted.file()), "fresh.yaml");
        check.eq("the drafted contract now says quick", "quick", written.mode());
        check.eq("the drafted risk is kept", "medium", written.risk());
        check.eq("the same mode again is accepted", null, TaskDraft.applyMode(
                new TaskDraft.Written(drafted.file(), "fresh", true), "quick"));
        check.contains("another mode is a conflict", TaskDraft.applyMode(
                new TaskDraft.Written(drafted.file(), "fresh", true), "refactor"), "has mode: quick");
        var person = new TaskDraft().write(root, "theirs", "Fix the crash", "code", "medium");
        String before = Files.readString(person.file());
        check.contains("a person's contract without a mode is not edited", TaskDraft.applyMode(
                new TaskDraft.Written(person.file(), "theirs", true), "bug"), "has no mode");
        check.eq("and its bytes are unchanged", before, Files.readString(person.file()));
        check.contains("a mode the fresh draft cannot satisfy is refused", TaskDraft.applyMode(person, "bug"), "reproduce");
        check.eq("and the draft is left as it was", before, Files.readString(person.file()));

        // `oseer do --max-role-runs/--max-cost-usd` caps a contract it drafted, the launcher's budget fields.
        var capped = new TaskDraft().write(root, "capped", "Make the link green", "code", "medium");
        check.eq("a fresh draft takes the budgets", null, TaskDraft.applyBudgets(capped, 3L, 0.25));
        TaskSpec cappedSpec = TaskSpec.parse(Files.readString(capped.file()), "capped.yaml");
        check.eq("the call cap is written", 3L, (long) cappedSpec.budget().maxRoleRuns());
        check.eq("the spend threshold is written", 0.25, cappedSpec.budget().maxCostUsd());
        check.eq("the same budgets again are accepted", null, TaskDraft.applyBudgets(
                new TaskDraft.Written(capped.file(), "capped", true), 3L, 0.25));
        check.contains("other budgets on a written contract are refused", TaskDraft.applyBudgets(
                new TaskDraft.Written(capped.file(), "capped", true), 9L, null), "has its own budgets");
        check.contains("a call cap out of range is refused", TaskDraft.applyBudgets(capped, 0L, null), "1..50");
        check.eq("no budgets is a no-op", null, TaskDraft.applyBudgets(person, null, null));

        var defaultAmount = new TaskDraft().write(root, "default-cost", "Fix the label", "code", "low");
        check.eq("an explicit amount equal to the default still enables the threshold", null,
                TaskDraft.applyBudgets(defaultAmount, null, 20.0));
        check.eq("the resulting threshold is active", "threshold",
                TaskSpec.parse(Files.readString(defaultAmount.file()), "default-cost.yaml").budget().costCap());
        check.rejects("a non-finite CLI threshold is refused before drafting", "--max-cost-usd",
                () -> dev.oseer.run.DoCommand.parse(new String[] {"do", "Fix the label", "--max-cost-usd", "NaN"}));

        // The browser selects a mode through the same reviewed, versioned task edit as budgets.
        Path sandbox = Files.createTempDirectory("oseer-task-mode-ui-");
        Path project = sandbox.resolve("project"), home = sandbox.resolve("home");
        NextRunTest.fixture(home, project);
        NextRun next = new NextRun(project, home, p -> true);
        SettingsEditor editor = new SettingsEditor(next);
        check.eq("the next-run description carries the mode", null, next.describe("hello").get("mode"));
        var request = new LinkedHashMap<String, Object>(Map.of("task", "hello", "target", "task",
                "revision", next.describe("hello").get("revision"), "changes", Map.of("mode", "quick")));
        var preview = editor.preview(request);
        check.contains("the diff writes the mode", String.valueOf(preview.get("diff")), "+mode: \"quick\"");
        var apply = new LinkedHashMap<String, Object>(request);
        apply.put("preview_sha256", preview.get("preview_sha256"));
        editor.apply(apply);
        check.eq("Apply saves the mode into the task", "quick", next.describe("hello").get("mode"));
        var bad = new LinkedHashMap<String, Object>(Map.of("task", "hello", "target", "task",
                "revision", next.describe("hello").get("revision"), "changes", Map.of("mode", "bug")));
        check.rejects("a mode the task cannot satisfy is explained in the preview", "reproduce", () -> editor.preview(bad));
        var unknown = new LinkedHashMap<String, Object>(Map.of("task", "hello", "target", "task",
                "revision", next.describe("hello").get("revision"), "changes", Map.of("mode", "yolo")));
        var clear = new LinkedHashMap<String, Object>();
        clear.put("task", "hello"); clear.put("target", "task");
        clear.put("revision", next.describe("hello").get("revision"));
        java.util.Map<String, Object> none = new java.util.HashMap<>(); none.put("mode", null);
        clear.put("changes", none);
        var clearPreview = editor.preview(clear);
        clear.put("preview_sha256", clearPreview.get("preview_sha256"));
        editor.apply(clear);
        check.eq("choosing no mode removes it from the task", null, next.describe("hello").get("mode"));
        unknown.put("revision", next.describe("hello").get("revision"));
        check.rejects("an unknown mode is refused by the editor", "mode must be one of", () -> editor.preview(unknown));
    }

    private static String task(String extra) {
        return "version: 1\nid: t\ngoal: g\nscope: code\n" + extra + "\n";
    }
}
