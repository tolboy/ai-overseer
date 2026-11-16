package dev.oseer.config;

import dev.oseer.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates a conservative starter config; it never overwrites an existing project contract. */
public final class ProjectInitializer {
    public record Result(Path projectFile, Path exampleTask, String detectedBuild, List<String> checks) {}

    public Result initialize(Path root, String baseRef) throws IOException {
        root = root.toAbsolutePath().normalize();
        Path oseer = root.resolve(".oseer");
        Path projectFile = oseer.resolve("project.yaml");
        if (Files.exists(projectFile)) throw new IOException(projectFile + " already exists; init never overwrites it");

        Detection detection = detect(root);
        List<String> scopes = scopes(root, detection);

        Files.createDirectories(oseer.resolve("tasks"));
        Files.createDirectories(oseer.resolve("runs"));
        String project = root.getFileName() == null ? "project" : root.getFileName().toString();
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\nproject: ").append(quote(project)).append("\nbase_ref: ")
                .append(quote(baseRef)).append("\n\n");
        if (detection.greenfield()) yaml.append(GREENFIELD_NOTE);
        yaml.append("checks:\n  fast:").append(detection.checks().isEmpty() ? " []" : "").append('\n');
        for (String command : detection.checks()) yaml.append("    - ").append(quote(command)).append('\n');
        yaml.append("\nscopes:\n  code:\n");
        for (String scope : scopes) yaml.append("    - ").append(quote(scope)).append('\n');
        yaml.append("\ndefaults:\n  checks: fast\n");
        // A detected build gives us a real project-health command. Run it before the first
        // vendor and again in the final gate. A greenfield's `fast` set is deliberately
        // empty, so naming it as a baseline would turn "nothing was checked" into a pass.
        if (!detection.greenfield()) yaml.append("  baseline_checks: fast\n");
        yaml.append("  risk: medium\n")
                .append("  max_fix_attempts: 2\n  timeout_minutes: 30\n")
                .append(LAND_NOTE);
        Files.writeString(projectFile, yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        Path task = oseer.resolve("tasks/example.yaml");
        // With no check command there has to be something else that says "done", or the
        // linter would reject the very file init just wrote. Two assertions that hold for any
        // page, and each one leaves a screenshot the visual_qa role can be shown.
        if (detection.greenfield()) {
            Files.writeString(task, """
                version: 1
                id: example
                goal: Replace this with one mechanically verifiable outcome
                non_goals:
                  - "Name at least one thing this task must not change"
                risk: medium
                scope: code
                checks: fast
                authority:
                  workspace_write: true
                  network: false
                  land: false
                # This project has no check command yet, so these scenarios are its definition
                # of done. Point url at the dev server once there is one, and replace the two
                # generic assertions with what this task actually promises.
                visual_qa:
                  required: true
                  url: "http://127.0.0.1:4173/"
                  scenarios:
                    - "1280x720: no-console-errors"
                    - "700x400: no-console-errors"
                budgets:
                  max_role_runs: 6
                  max_cost_usd: 20.0
                max_fix_attempts: 2
                timeout_minutes: 30
                """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            writeRunsIgnore(oseer);
            new ConfigLoader().load(root, "example");
            return new Result(projectFile, task, detection.name(), detection.checks());
        }
        Files.writeString(task, """
                version: 1
                id: example
                goal: Replace this with one mechanically verifiable outcome
                non_goals:
                  - "Name at least one thing this task must not change"
                risk: medium
                scope: code
                checks: fast
                authority:
                  workspace_write: true
                  network: false
                  land: false
                visual_qa:
                  required: false
                  scenarios: []
                budgets:
                  max_role_runs: 6
                  max_cost_usd: 20.0
                max_fix_attempts: 2
                timeout_minutes: 30
                """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        writeRunsIgnore(oseer);
        // Never claim successful initialization for a contract Overseer itself cannot load.
        new ConfigLoader().load(root, "example");
        return new Result(projectFile, task, detection.name(), detection.checks());
    }

    /** What {@code init} would write, without writing anything: the first-run scan. */
    public record Scan(String detectedBuild, List<String> checks, List<String> scopes, boolean greenfield,
                       List<String> evidence) {}

    public Scan scan(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        Detection detection = detect(root);
        return new Scan(detection.name(), detection.checks(), scopes(root, detection), detection.greenfield(),
                detection.evidence());
    }

    private static List<String> scopes(Path root, Detection detection) throws IOException {
        List<String> scopes = new ArrayList<>();
        for (String candidate : List.of("src", "test", "tests", "app", "lib", "docs", "scripts", "cmd", "internal", "pkg")) {
            if (Files.exists(root.resolve(candidate))) scopes.add(candidate);
        }
        boolean python = detection.name().startsWith("python");
        boolean go = detection.name().equals("go");
        if (python || go) {
            try (var entries = Files.list(root)) {
                for (Path entry : entries.sorted().toList()) {
                    String name = entry.getFileName().toString();
                    if (name.startsWith(".") || scopes.contains(name)) continue;
                    if (python && Files.isRegularFile(entry.resolve("__init__.py"))) scopes.add(name);
                    else if (Files.isRegularFile(entry) && name.endsWith(python ? ".py" : ".go")) scopes.add(name);
                }
            }
        }
        if (scopes.isEmpty()) {
            if (go) scopes.add("go.mod");
            else if (Files.isRegularFile(root.resolve("Makefile"))) scopes.add("Makefile");
            // A project from scratch has no directory to protect: whatever the agent creates
            // is new, and a blast radius of `src` would refuse the package.json it has to
            // write. This is the one case where the whole repository is the honest boundary,
            // and it is written into the contract rather than assumed.
            else if (detection.greenfield()) scopes.add(RepoPath.WHOLE_REPOSITORY);
            else scopes.add("src");
        }
        return scopes;
    }

    private static void writeRunsIgnore(Path oseer) throws IOException {
        Files.writeString(oseer.resolve("runs/.gitignore"), """
                *
                !*/
                !*.json
                !*.jsonl
                !.gitignore
                """,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    @SuppressWarnings("unchecked")
    private Detection detect(Path root) throws IOException {
        Path packageJson = root.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            Map<String, Object> object = Json.parseObject(Files.readString(packageJson));
            Map<String, Object> scripts = object.get("scripts") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            List<String> commands = new ArrayList<>();
            for (String name : List.of("test", "check", "build")) {
                if (scripts.get(name) instanceof String) commands.add("npm run " + name);
            }
            if (commands.isEmpty()) throw new IOException("package.json has no test/check/build script to gate");
            return new Detection("npm", commands, "package.json");
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (Files.isRegularFile(root.resolve(windows ? "gradlew.bat" : "gradlew"))) {
            String wrapper = windows ? ".\\gradlew.bat" : "./gradlew";
            return new Detection("gradle-wrapper", List.of(wrapper + " test", wrapper + " build"),
                    windows ? "gradlew.bat" : "gradlew");
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            String wrapper = Files.isRegularFile(root.resolve(windows ? "mvnw.cmd" : "mvnw"))
                    ? (windows ? ".\\mvnw.cmd" : "./mvnw") : "mvn";
            List<String> evidence = wrapper.equals("mvn") ? List.of("pom.xml")
                    : List.of("pom.xml", windows ? "mvnw.cmd" : "mvnw");
            return new Detection("maven", List.of(wrapper + " test", wrapper + " package"), false, evidence);
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) return new Detection("cargo", List.of("cargo test"), "Cargo.toml");
        if (Files.isRegularFile(root.resolve("go.mod")))
            return new Detection("go", List.of("go vet ./...", "go test ./..."), "go.mod");
        Detection python = python(root, windows ? "python" : "python3");
        if (python != null) return python;
        if (Files.isRegularFile(root.resolve("Makefile"))) return new Detection("make", List.of("make test"), "Makefile");
        // No build marker AND no files: a project that does not exist yet, which is a
        // different thing from one whose build system is unrecognised. Guessing a check for
        // the second produces a gate that fails for reasons unrelated to the task, so it is
        // still refused. The first gets a contract whose definition of done is written by the
        // operator, or by the browser scenarios `oseer do` drafts.
        if (isEmptyProject(root)) return new Detection("none", List.of(), true);
        throw new IOException("no supported build marker found; create .oseer/project.yaml manually");
    }

    /** Nothing but hidden metadata: no source, no manifest, nothing to infer a check from. */
    private static boolean isEmptyProject(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            return entries.noneMatch(path -> !path.getFileName().toString().startsWith("."));
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * How this project takes an accepted change. Commented out, and left for the operator to
     * write: Overseer knows git, not your forge, and a guessed `gh pr create` in a GitLab
     * project is a landing step that fails the first time it is ever needed.
     */
    private static final String LAND_NOTE = """

            # How an accepted candidate becomes a request. `oseer land <run-id>` plans it and
            # changes nothing; --commit, --push and --pull-request escalate a step at a time.
            # Overseer merges nothing either way: a request is a request.
            #
            # The command is argv, not a shell line, so a title containing a quote stays one
            # argument. Placeholders: {{remote}} {{branch}} {{base}} {{title}} {{body_file}}.
            #
            # The argv list must be one line. Overseer's YAML is a strict subset and a flow
            # sequence does not continue across a newline — this example was written wrapped
            # and, uncommented exactly as it stood, produced `unexpected end of flow
            # collection` from the parser that shipped it.
            #
            # land:
            #   remote: origin        # optional; inferred when the repo has exactly one
            #   base: main            # optional; asked of the remote when absent
            #   pull_request: ["gh", "pr", "create", "--base", "{{base}}", "--head", "{{branch}}", "--title", "{{title}}", "--body-file", "{{body_file}}"]
            #
            # GitLab: ["glab", "mr", "create", ...]   Gitea: ["tea", "pr", "create", ...]
            """;

    private static final String GREENFIELD_NOTE = """
            # This directory had no files when oseer init ran, so there is nothing here to
            # infer a check from. Two consequences, both deliberate and both editable:
            #
            #   checks.fast is empty. Until you write the real command, the browser scenarios
            #   in the task contract are this project's only executable definition of "done",
            #   and a task that declares neither is refused rather than passed.
            #
            #   the scope is the whole repository. A project with no baseline has no existing
            #   code for a blast radius to protect. Narrow it to real directories as soon as
            #   this project has a shape.
            """;

    private record Detection(String name, List<String> checks, boolean greenfield, List<String> evidence) {
        Detection(String name, List<String> checks, String evidence) { this(name, checks, false, List.of(evidence)); }
        Detection(String name, List<String> checks, boolean greenfield) { this(name, checks, greenfield, List.of()); }
    }

    /**
     * A Python project is recognised by a marker that names its test runner, or by test files
     * the standard library runner finds. pytest is chosen only when the project says so
     * (pytest.ini, conftest.py, a [tool.pytest] or [pytest] section); otherwise unittest, which
     * needs nothing installed. A Python project with no tests at all has no check to infer and
     * is refused like any other unrecognised build.
     */
    private static Detection python(Path root, String command) throws IOException {
        List<String> markers = new ArrayList<>();
        for (String name : List.of("pyproject.toml", "setup.cfg", "setup.py", "tox.ini", "pytest.ini", "conftest.py")) {
            if (Files.isRegularFile(root.resolve(name))) markers.add(name);
        }
        boolean pytest = markers.contains("pytest.ini") || markers.contains("conftest.py")
                || mentions(root.resolve("pyproject.toml"), "[tool.pytest") || mentions(root.resolve("tox.ini"), "[pytest]")
                || mentions(root.resolve("setup.cfg"), "[tool:pytest]");
        List<String> tests = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        boolean discoversFromRoot = false;
        for (Path directory : List.of(root, root.resolve("tests"), root.resolve("test"))) {
            if (!Files.isDirectory(directory)) continue;
            boolean hasTests = false;
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.sorted().toList()) {
                    String name = entry.getFileName().toString();
                    if (!name.endsWith(".py") || !Files.isRegularFile(entry)) continue;
                    boolean testName = pytest ? name.startsWith("test_") || name.endsWith("_test.py")
                            : name.startsWith("test") && name.substring(0, name.length() - 3).matches("[a-zA-Z_]\\w*");
                    if (testName) {
                        tests.add(root.relativize(entry).toString().replace('\\', '/'));
                        hasTests = true;
                    }
                }
            }
            if (hasTests && !pytest) {
                if (directory.equals(root) || Files.isRegularFile(directory.resolve("__init__.py"))) {
                    discoversFromRoot = true;
                } else {
                    checks.add(command + " -m unittest discover -q -s " + directory.getFileName());
                }
            }
        }
        if (pytest) {
            List<String> evidence = new ArrayList<>(markers); evidence.addAll(tests);
            return new Detection("python-pytest", List.of(command + " -m pytest -q"), false, List.copyOf(evidence));
        }
        if (tests.isEmpty()) return null;
        if (discoversFromRoot) checks.addFirst(command + " -m unittest discover -q");
        List<String> evidence = new ArrayList<>(markers); evidence.addAll(tests);
        return new Detection("python-unittest", List.copyOf(checks), false, List.copyOf(evidence));
    }

    private static boolean mentions(Path file, String text) {
        try {
            return Files.isRegularFile(file) && Files.size(file) < 512 * 1024 && Files.readString(file).contains(text);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
