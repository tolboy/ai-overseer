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
