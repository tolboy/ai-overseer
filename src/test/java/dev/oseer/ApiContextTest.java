package dev.oseer;

import dev.oseer.config.ApiContextSettings;
import dev.oseer.execution.ApiContextBuilder;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Real disposable Git repositories establish what the text-only API actually receives. */
public final class ApiContextTest implements Suite {
    private final ProcessRunner processes = new ProcessRunner();
    @Override public String name() { return "api-context"; }

    @Override public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("oseer-api-context-");
        try {
            Path root = Files.createDirectories(scratch.resolve("repo"));
            git(root, "init", "-q");
            git(root, "config", "core.autocrlf", "false");
            Files.writeString(root.resolve("source.java"), "class Original {}\n");
            Files.writeString(root.resolve("deleted.java"), "class Removed {}\n");
            Files.createDirectories(root.resolve(".oseer"));
            Files.writeString(root.resolve(".oseer/internal.txt"), "old internal metadata\n");
            git(root, "add", "-f", "--", "source.java", "deleted.java", ".oseer/internal.txt");
            commit(root);
            String base = git(root, "rev-parse", "HEAD").strip();
            Files.writeString(root.resolve("source.java"), "class Changed { int value = 42; }\n");
            Files.writeString(root.resolve("new source.java"), "class Added { String label = \"новый\"; }\n");
            Files.delete(root.resolve("deleted.java"));
            Files.writeString(root.resolve(".oseer/internal.txt"), "do-not-send-internal-change\n");
            Files.writeString(root.resolve(".oseer/new-receipt.json"), "do-not-send-internal-receipt\n");

            String context = ApiContextBuilder.build(root, base, processes);
            check.contains("tracked source contents reach the API", context, "class Changed");
            check.contains("untracked UTF-8 source contents reach the API", context, "новый");
            check.contains("base diff reaches the API", context, "-class Original");
            check.contains("deleted source is represented in the diff", context, "-class Removed");
            check.contains("model knows that omitted dependencies require an inconclusive result", context, "inconclusive or blocked");
            check.contains("model cannot claim to have tools", context, "no filesystem, shell, network");
            check.that("tracked metadata is excluded from diff and files", !context.contains("do-not-send-internal-change"));
            check.that("untracked metadata is excluded", !context.contains("do-not-send-internal-receipt"));
            check.that("context remains within the byte bound", context.getBytes(StandardCharsets.UTF_8).length <= ApiContextBuilder.MAX_CONTEXT_BYTES);
            check.eq("snapshot is deterministic", context, ApiContextBuilder.build(root, base, processes));
            Map<?, ?> snapshot = (Map<?, ?>) Json.parse(context.substring(context.indexOf('{')));
            check.eq("snapshot pins the inspected commit", base, snapshot.get("base_commit"));
            List<?> entries = (List<?>) snapshot.get("files");
            check.eq("exactly the changed source files are included", 3, entries.size());
            check.that("deletion has explicit status and no fabricated current content", entries.stream()
                    .anyMatch(item -> item instanceof Map<?, ?> file && "deleted.java".equals(file.get("path"))
                            && "deleted".equals(file.get("state")) && !file.containsKey("content")));
            check.that("untracked source is identified", entries.stream()
                    .anyMatch(item -> item instanceof Map<?, ?> file && "new source.java".equals(file.get("path"))
                            && "untracked".equals(file.get("state"))));

            check.rejects("an option cannot be substituted for a base hash", "explicit base commit hash",
                    () -> ApiContextBuilder.build(root, "--output=elsewhere", processes));
            Path nested = Files.createDirectories(root.resolve("nested"));
            check.rejects("subdirectory roots cannot misresolve repository-relative paths", "repository root",
                    () -> ApiContextBuilder.build(nested, base, processes));

            Path secret = root.resolve(".env");
            Files.writeString(secret, "API_KEY=do-not-leak-secret-value\n");
            git(root, "add", "-f", "--", ".env");
            safeFailure(check, "changed secret files are refused before sending", "secret or credential", root, base);
            git(root, "reset", "-q", "--", ".env");
            Files.delete(secret);

            Path binary = root.resolve("binary.java");
            Files.write(binary, new byte[]{0, 1, 2, 3});
            safeFailure(check, "binary source is refused", "binary", root, base);
            Files.delete(binary);

            Path invalid = root.resolve("invalid.java");
            Files.write(invalid, new byte[]{(byte) 0xc3, (byte) 0x28});
            safeFailure(check, "malformed UTF-8 is refused", "UTF-8", root, base);
            Files.delete(invalid);

            Path oversized = root.resolve("oversized.java");
            Files.writeString(oversized, "a".repeat(ApiContextBuilder.MAX_FILE_BYTES + 1));
            safeFailure(check, "oversized files are refused rather than clipped", "per-file limit", root, base);
            Files.delete(oversized);

            List<Path> big = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Path file = root.resolve("large-" + i + ".java");
                Files.writeString(file, "a".repeat(60 * 1024));
                big.add(file);
            }
            safeFailure(check, "total source budget is enforced before request creation", "total limit", root, base);
            for (Path file : big) Files.delete(file);

            List<Path> many = new ArrayList<>();
            for (int i = 0; i < 65; i++) {
                Path file = root.resolve("many-" + i + ".java");
                Files.writeString(file, "class Small {}\n");
                many.add(file);
            }
            safeFailure(check, "file count cannot silently hide a tail of changes", "64 changed-file limit", root, base);
            for (Path file : many) Files.delete(file);

            symlinks(check, scratch, root, base);
            truncatedDiff(check, scratch);
            settingsModes(check, scratch);
        } finally {
            try (Stream<Path> paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void symlinks(Check check, Path scratch, Path root, String base) throws Exception {
        Path outside = Files.createDirectories(scratch.resolve("outside"));
        Path target = outside.resolve("private.txt");
        Files.writeString(target, "do-not-leak-secret-value\n");
        Path link = root.resolve("outside-link.java");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            return; // Windows can require a privilege that the offline suite deliberately does not obtain.
        }
        try {
            safeFailure(check, "symlink cannot read outside the repository", "symlink", root, base);
            git(root, "add", "-f", "--", "outside-link.java");
            commit(root);
            String linkedBase = git(root, "rev-parse", "HEAD").strip();
            Files.delete(link);
            safeFailure(check, "deleted symlinks cannot enter a source diff", "symlink", root, linkedBase);
        } finally {
            Files.deleteIfExists(link);
        }
    }

    /**
     * The 2026-11-18 case: a pull request that edits one line of a 66 KiB README could not be
     * read by any API reviewer, although its diff was a fraction of the budget.
     */
    private void settingsModes(Check check, Path scratch) throws Exception {
        Path root = Files.createDirectories(scratch.resolve("modes"));
        git(root, "init", "-q");
        git(root, "config", "core.autocrlf", "false");
        StringBuilder readme = new StringBuilder("# Title\n");
        for (int i = 0; i < 1400; i++) readme.append("Line ").append(i).append(" of a long README that nobody touched.\n");
        readme.append("UNCHANGED-TAIL-MARKER\n");
        Files.writeString(root.resolve("README.md"), readme.toString());
        Files.writeString(root.resolve("small.java"), "class Small {}\n");
        git(root, "add", "--", ".");
        commit(root);
        String base = git(root, "rev-parse", "HEAD").strip();
        Files.writeString(root.resolve("README.md"), readme.toString().replace("# Title", "# New title"));
        Files.writeString(root.resolve("small.java"), "class Small { int changed; }\n");
        int readmeBytes = (int) Files.size(root.resolve("README.md"));
        check.that("fixture README is over the default per-file limit", readmeBytes > ApiContextBuilder.MAX_FILE_BYTES);

        var full = new ApiContextSettings("full", 64, 64 * 1024, 256 * 1024);
        try {
            ApiContextBuilder.build(root, base, processes, full);
            check.that("full mode refuses an oversized file", false);
        } catch (IOException refused) {
            check.contains("the refusal names the file", refused.getMessage(), "README.md (" + readmeBytes + " bytes)");
            check.contains("the refusal names the limit and its key", refused.getMessage(), "65536 bytes (api_context.max_file_bytes)");
            check.contains("the refusal says how to get past it", refused.getMessage(), "api_context.mode to auto or diff");
        }

        var auto = ApiContextBuilder.build(root, base, processes, ApiContextSettings.DEFAULT);
        check.eq("auto falls back to the diff", "diff", auto.mode());
        check.eq("auto records what was asked for", "auto", auto.requestedMode());
        check.contains("the fallback names the file that did not fit", String.join(";", auto.fallback()), "README.md");
        check.contains("the model is told it has the diff only", auto.text(), "DIFF-ONLY SNAPSHOT");
        check.contains("the change itself is sent", auto.text(), "+# New title");
        check.contains("the other change is sent too", auto.text(), "int changed");
        check.that("whole files are not sent", !auto.text().contains("UNCHANGED-TAIL-MARKER"));
        check.contains("the evidence carries the fallback", String.valueOf(auto.evidence(ApiContextSettings.DEFAULT)), "fallback_reasons");

        var diffOnly = ApiContextBuilder.build(root, base, processes, new ApiContextSettings("diff", 64, 64 * 1024, 256 * 1024));
        check.eq("diff mode sends the diff", "diff", diffOnly.mode());
        check.eq("diff mode chosen on purpose has no fallback reason", List.of(), diffOnly.fallback());
        check.that("diff mode sends no whole file", !diffOnly.text().contains("UNCHANGED-TAIL-MARKER"));

        var raised = ApiContextBuilder.build(root, base, processes, new ApiContextSettings("auto", 64, 128 * 1024, 512 * 1024));
        check.eq("raised limits keep whole files", "full", raised.mode());
        check.contains("whole files are then sent", raised.text(), "UNCHANGED-TAIL-MARKER");

        List<Path> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Path file = root.resolve("many-" + i + ".java");
            Files.writeString(file, "class Many" + i + " {}\n");
            many.add(file);
        }
        var counted = ApiContextBuilder.build(root, base, processes, new ApiContextSettings("auto", 4, 128 * 1024, 512 * 1024));
        check.eq("too many files falls back too", "diff", counted.mode());
        check.contains("and says why", String.join(";", counted.fallback()), "4 changed-file limit (api_context.max_files)");
        check.contains("untracked files keep their text, having no diff", counted.text(), "class Many3");
        for (Path file : many) Files.delete(file);

        Path untracked = root.resolve("generated.java");
        Files.writeString(untracked, "a".repeat(2048));
        try {
            ApiContextBuilder.build(root, base, processes, new ApiContextSettings("diff", 64, 1024, 512 * 1024));
            check.that("an untracked file over the limit is refused in diff mode", false);
        } catch (IOException refused) {
            check.contains("the refusal names the untracked file", refused.getMessage(), "untracked generated.java is 2048 bytes");
        }
        Files.delete(untracked);

        try {
            ApiContextBuilder.build(root, base, processes, new ApiContextSettings("auto", 64, 64 * 1024, 1024));
            check.that("a diff over the total limit is refused, not cut", false);
        } catch (IOException refused) {
            check.contains("the refusal names the total limit", refused.getMessage(), "1024 bytes (api_context.max_bytes)");
        }
        check.rejects("an unknown mode is refused", "api_context.mode",
                () -> new ApiContextSettings("everything", 64, 1, 1));
    }

    private void truncatedDiff(Check check, Path scratch) throws Exception {
        Path root = Files.createDirectories(scratch.resolve("large-base"));
        git(root, "init", "-q");
        git(root, "config", "core.autocrlf", "false");
        for (int i = 0; i < 5; i++) Files.writeString(root.resolve("old-" + i + ".java"), "a".repeat(60 * 1024));
        git(root, "add", "--", ".");
        commit(root);
        String base = git(root, "rev-parse", "HEAD").strip();
        for (int i = 0; i < 5; i++) Files.delete(root.resolve("old-" + i + ".java"));
        safeFailure(check, "a truncated deletion diff is refused", "capture limit", root, base);
    }

    private void safeFailure(Check check, String description, String expected, Path root, String base)
            throws Exception {
        try {
            ApiContextBuilder.build(root, base, processes);
            check.that(description, false);
        } catch (IOException failure) {
            check.contains(description, failure.getMessage(), expected);
            check.that(description + " does not expose source contents",
                    !failure.getMessage().contains("do-not-leak-secret-value") && failure.getCause() == null);
        }
    }

    private String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        for (String argument : args) command.add(ProcessRunner.argument("git", argument));
        ProcessRunner.Result result = processes.run(command, root, Duration.ofSeconds(30), 64 * 1024);
        if (!result.ok()) throw new IOException("disposable Git fixture command failed");
        return result.stdout();
    }

    private void commit(Path root) throws Exception {
        git(root, "-c", "user.name=Overseer Test", "-c", "user.email=test@example.invalid",
                "-c", "commit.gpgsign=false", "commit", "-qm", "fixture baseline");
    }
}
