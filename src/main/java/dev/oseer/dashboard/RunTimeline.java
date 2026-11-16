package dev.oseer.dashboard;

import dev.oseer.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One run as a timeline: its stages in order, who filled each one, how long it took, what it
 * cost, what the machine gates said, and links to the evidence the run left behind.
 *
 * Read-only. It reads the summary the loop wrote ({@code task-run.json}) and, for a run that
 * has not written one yet, the per-step directories and live role views, so a run that is
 * still going shows its finished and running steps. It never writes into the run directory
 * and never follows a link out of {@code .oseer/runs}.
 *
 * Evidence is offered by name only here. The bytes are served one file at a time by
 * {@link #evidence}, from an allowlist of kinds (prompts, vendor output, artifacts, gate
 * reports, screenshots), with credential-shaped text masked before it leaves the process.
 */
public final class RunTimeline {
    private RunTimeline() {}

    /** Run identities the loop mints: a slug, optionally followed by `--stage-N`. */
    static final Pattern RUN_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}");
    private static final long MAX_JSON = 4L * 1024 * 1024;
    static final long MAX_TEXT = 2L * 1024 * 1024;
    static final long MAX_IMAGE = 12L * 1024 * 1024;
    private static final int MAX_EVIDENCE_FILES = 120;
    private static final Pattern EVIDENCE_FILE = Pattern.compile(
            "(prompts/[A-Za-z0-9._-]{1,120}\\.md"
                    + "|raw/[A-Za-z0-9._-]{1,120}\\.(?:stdout|stderr)\\.txt"
                    + "|artifacts/[A-Za-z0-9._-]{1,120}\\.json"
                    + "|screenshots/[A-Za-z0-9._-]{1,160}\\.png"
                    + "|(?:machine-gate|baseline-gate|reproduce-gate|visual-qa)\\.json"
                    + "|preview\\.log)");

    /** Thrown for a run or file that does not exist or may not be served; the caller answers 404. */
    public static final class NotFound extends Exception {
        NotFound() { super(null, null, false, false); }
    }

    public record Evidence(byte[] bytes, String contentType) {}

    // ---------------------------------------------------------------------------------------
    // Timeline projection

    public static Map<String, Object> read(Path project, String runId) throws IOException, NotFound {
        if (runId == null || !RUN_ID.matcher(runId).matches() || runId.contains("--")) throw new NotFound();
        Path runsRoot = runsRoot(project);
        Path directory = runsRoot.resolve(runId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().startsWith(runsRoot)) throw new NotFound();
        Map<String, Object> summary = readJson(directory.resolve("task-run.json"), runsRoot);
        Map<String, Object> reserved = readJson(directory.resolve("run.json"), runsRoot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", runId);
        Object task = summary != null ? summary.get("task_id") : reserved != null ? reserved.get("task_id") : null;
        result.put("task_id", task instanceof String text ? text : null);
        if (reserved != null && reserved.get("reserved_at") instanceof String at) result.put("started_at", at);

        List<Path> stepDirectories = stepDirectories(runsRoot, runId);
        List<Map<String, Object>> steps;
        if (summary != null && summary.get("steps") instanceof List<?>) {
            result.put("state", "finished");
            for (String key : List.of("ok", "reason", "risk", "mode", "next_action", "total_cost_usd", "unpriced_calls",
                    "role_runs", "attempts_used", "open_blocking_findings", "candidate_review_passed",
                    "continued_from", "decision_state", "decision_kind", "decision")) {
                copyScalar(result, summary, key);
            }
            if (summary.get("next_step") instanceof Map<?, ?> next && next.get("summary") instanceof String text) {
                result.put("next_step", text);
            } else if (summary.get("safe_next_step") instanceof String text) {
                result.put("next_step", text);
            }
            steps = summarySteps((List<?>) summary.get("steps"), runsRoot, runId);
        } else {
            boolean running = false;
            steps = new ArrayList<>();
            for (Path step : stepDirectories) {
                Map<String, Object> row = liveStep(step, runsRoot);
                if (row == null) continue;
                if ("running".equals(row.get("status"))) running = true;
                steps.add(row);
            }
            result.put("state", running ? "running" : summary != null ? "stopped" : "no_summary");
            if (summary != null) {
                copyScalar(result, summary, "ok");
                copyScalar(result, summary, "reason");
                copyScalar(result, summary, "next_action");
            }
        }
        result.put("workflow", workflow(summary));
        result.put("baseline", baseline(summary, runsRoot));
        attribute(steps, (Map<?, ?>) result.get("baseline"));
        result.put("steps", steps);

        long duration = 0L; boolean durationKnown = !steps.isEmpty();
        double cost = 0.0; long unpriced = 0L;
        for (Map<String, Object> step : steps) {
            if (step.get("duration_ms") instanceof Number number) duration += number.longValue();
            else if (!"skipped".equals(step.get("status")) && !Boolean.TRUE.equals(step.get("reused"))) durationKnown = false;
            if (step.get("cost_usd") instanceof Number number) cost += number.doubleValue();
            if (Boolean.TRUE.equals(step.get("unpriced"))) unpriced++;
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("duration_ms", duration);
        totals.put("duration_complete", durationKnown);
        totals.put("cost_usd_known", Math.round(cost * 1_000_000d) / 1_000_000d);
        totals.put("unpriced_steps", unpriced);
        result.put("totals", totals);
        result.put("decision_record", decision(directory, runsRoot));
        result.put("observed_at", Instant.now().toString());
        return result;
    }

    private static List<Map<String, Object>> summarySteps(List<?> source, Path runsRoot, String runId) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : source) {
            if (!(item instanceof Map<?, ?> step)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : List.of("step", "stage", "attempt", "ok", "code", "profile", "vendor", "cost_usd",
                    "reused_from", "fix_for", "blocking_findings", "independence")) {
                copyScalar(row, step, key);
            }
            if (!row.containsKey("stage")) row.put("stage", step.get("step"));
            String kind = kindOf(step);
            row.put("kind", kind);
            if (step.get("reused_from") instanceof String) row.put("reused", true);
            row.put("status", step.get("ok") instanceof Boolean ok ? (ok ? "passed" : "failed") : "unknown");
            if ("skipped".equals(step.get("code"))) row.put("status", "skipped");
            // Model, effort, duration and the number of vendor attempts come from the attempts
            // the role made, not from the profile: a failover or a retry changes them.
            if (step.get("vendor_attempts") instanceof List<?> attempts && !attempts.isEmpty()) {
                long millis = 0L; boolean known = true; int count = 0;
                Map<?, ?> last = null;
                for (Object raw : attempts) {
                    if (!(raw instanceof Map<?, ?> attempt)) continue;
                    count++; last = attempt;
                    if (attempt.get("duration_millis") instanceof Number number) millis += number.longValue();
                    else known = false;
                }
                row.put("vendor_attempts", count);
                if (known && count > 0) row.put("duration_ms", millis);
                if (last != null) {
                    Object model = last.get("model_reported") != null ? last.get("model_reported") : last.get("model_requested");
                    if (model instanceof String text) row.put("model", text);
                    if (last.get("model_requested") instanceof String text) row.put("model_requested", text);
                    if (last.get("effort_requested") instanceof String text) row.put("effort", text);
                    if (last.get("runner") instanceof String text) row.put("runner", text);
                    if (last.get("tokens") instanceof Map<?, ?> tokens) {
                        Map<String, Object> visible = new LinkedHashMap<>();
                        for (String key : List.of("input", "output")) copyScalar(visible, tokens, key);
                        if (!visible.isEmpty()) row.put("tokens", visible);
                    }
                }
            }
            // A vendor call with no price is unpriced, not free. A step that made no call
            // (gates, a reused verdict) has no price to report.
            if ("role".equals(kind) && !row.containsKey("reused") && !(step.get("cost_usd") instanceof Number)
                    && (row.get("vendor_attempts") instanceof Integer n ? n > 0 : step.get("profile") != null)) {
                row.put("unpriced", true);
            }
            String stepDirectory = stepDirectoryOf(step, runsRoot, runId);
            if (stepDirectory != null) {
                row.put("evidence_dir", stepDirectory);
                Path dir = runsRoot.resolve(stepDirectory);
                if ("gate".equals(kind)) gateDetails(row, step, dir, runsRoot);
                row.put("evidence", evidenceList(dir, runsRoot));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String kindOf(Map<?, ?> step) {
        Object name = step.get("step");
        if (step.get("profile") != null || step.get("vendor_attempts") != null || step.get("artifact_path") != null) return "role";
        if (step.get("report") != null || "gates".equals(name) || "baseline".equals(name) || "reproduce".equals(name)) return "gate";
        return "other";
    }

    /** The step's own directory, named by the artifact or report the summary points at. */
    private static String stepDirectoryOf(Map<?, ?> step, Path runsRoot, String runId) {
        for (String key : List.of("artifact_path", "report")) {
            if (!(step.get(key) instanceof String text) || text.isBlank()) continue;
            String normalized = text.replace('\\', '/');
            int marker = normalized.lastIndexOf(".oseer/runs/");
            String rest = marker >= 0 ? normalized.substring(marker + ".oseer/runs/".length()) : null;
            if (rest == null) continue;
            int slash = rest.indexOf('/');
            String name = slash < 0 ? rest : rest.substring(0, slash);
            if (ownedBy(name, runId) && isStepDirectory(runsRoot, name)) return name;
        }
        // Without a pointer the loop's naming convention is the fallback: `<run>--<stage>-<attempt>`.
        Object stage = step.get("stage") != null ? step.get("stage") : step.get("step");
        if (stage instanceof String text && step.get("attempt") instanceof Number attempt) {
            String name = runId + "--" + text.replace('_', '-') + "-" + attempt.longValue();
            if (RUN_ID.matcher(name).matches() && isStepDirectory(runsRoot, name)) return name;
        }
        return null;
    }

    private static boolean ownedBy(String directory, String runId) {
        return directory.equals(runId) || directory.startsWith(runId + "--");
    }

    private static boolean isStepDirectory(Path runsRoot, String name) {
        if (!RUN_ID.matcher(name).matches()) return false;
        Path dir = runsRoot.resolve(name);
        try {
            return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && dir.toRealPath().startsWith(runsRoot);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static void gateDetails(Map<String, Object> row, Map<?, ?> step, Path dir, Path runsRoot) throws IOException {
        Map<String, Object> report = null;
        if (step.get("report") instanceof String text) {
            Path named = dir.resolve(Path.of(text.replace('\\', '/')).getFileName().toString());
            report = readJson(named, runsRoot);
        }
        if (report == null) {
            for (String name : List.of("machine-gate.json", "baseline-gate.json", "reproduce-gate.json")) {
                report = readJson(dir.resolve(name), runsRoot);
                if (report != null) break;
            }
        }
        if (report == null) return;
        copyScalar(row, report, "phase");
        copyScalar(row, report, "finished_at");
        List<Map<String, Object>> commands = gateCommands(report);
        if (!commands.isEmpty()) {
            row.put("commands", commands);
            long millis = 0L; boolean known = true;
            for (Map<String, Object> command : commands) {
                if (command.get("duration_ms") instanceof Number number) millis += number.longValue();
                else known = false;
            }
            if (known) row.put("duration_ms", millis);
        }
        if (report.get("violations") instanceof List<?> violations) row.put("scope_violations", violations.size());
        if (report.get("changed_paths") instanceof List<?> changed) row.put("changed_paths", changed.size());
    }

    private static List<Map<String, Object>> gateCommands(Map<?, ?> report) {
        List<Map<String, Object>> commands = new ArrayList<>();
        if (!(report.get("commands") instanceof List<?> list)) return commands;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> command)) continue;
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String key : List.of("command", "exit_code", "timed_out", "duration_ms")) copyScalar(visible, command, key);
            boolean passed = command.get("exit_code") instanceof Number code && code.longValue() == 0
                    && !Boolean.TRUE.equals(command.get("timed_out"));
            visible.put("ok", passed);
            commands.add(visible);
        }
        return commands;
    }

    /**
     * Where a red acceptance check came from. The loop stops on a red baseline before any
     * vendor, so a baseline command that is red at acceptance was green when the task began:
     * that is a regression of this task. A command that was not in the baseline has no record
     * of its earlier state, and that is said rather than guessed.
     */
    private static void attribute(List<Map<String, Object>> steps, Map<?, ?> baseline) {
        Map<String, Boolean> before = new LinkedHashMap<>();
        if (baseline != null && baseline.get("commands") instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> command && command.get("command") instanceof String text) {
                    before.put(text, Boolean.TRUE.equals(command.get("ok")));
                }
            }
        }
        for (Map<String, Object> step : steps) {
            if (!"gate".equals(step.get("kind")) || "baseline".equals(step.get("step"))) continue;
            if (!(step.get("commands") instanceof List<?> commands)) continue;
            for (Object raw : commands) {
                if (!(raw instanceof Map<?, ?> rawCommand)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> command = (Map<String, Object>) rawCommand;
                if (Boolean.TRUE.equals(command.get("ok"))) continue;
                Boolean earlier = command.get("command") instanceof String text ? before.get(text) : null;
                command.put("attribution", earlier == null ? "no_baseline" : earlier ? "regression" : "pre_existing");
            }
        }
    }

    private static Map<String, Object> baseline(Map<String, Object> summary, Path runsRoot) throws IOException {
        Map<String, Object> baseline = new LinkedHashMap<>();
        if (summary == null) { baseline.put("recorded", false); return baseline; }
        boolean required = Boolean.TRUE.equals(summary.get("baseline_required"))
                || summary.get("baseline_commands") instanceof List<?> list && !list.isEmpty();
        baseline.put("required", required);
        baseline.put("recorded", summary.containsKey("baseline_ok"));
        copyScalar(baseline, summary, "baseline_ok");
        copyScalar(baseline, summary, "baseline_code");
        copyScalar(baseline, summary, "baseline_reused_from");
        if (summary.get("baseline_commands") instanceof List<?> list) {
            List<String> names = new ArrayList<>();
            for (Object item : list) if (item instanceof String text) names.add(text);
            baseline.put("declared", names);
        }
        if (summary.get("baseline_report") instanceof String path) {
            String normalized = path.replace('\\', '/');
            int marker = normalized.lastIndexOf(".oseer/runs/");
            if (marker >= 0) {
                String rest = normalized.substring(marker + ".oseer/runs/".length());
                int slash = rest.indexOf('/');
                if (slash > 0 && isStepDirectory(runsRoot, rest.substring(0, slash))) {
                    Map<String, Object> report = readJson(runsRoot.resolve(rest.substring(0, slash))
                            .resolve(rest.substring(slash + 1)), runsRoot);
                    if (report != null) {
                        baseline.put("commands", gateCommands(report));
                        copyScalar(baseline, report, "finished_at");
                        baseline.put("evidence_dir", rest.substring(0, slash));
                    }
                }
            }
        }
        return baseline;
    }

    private static List<Map<String, Object>> workflow(Map<String, Object> summary) {
        List<Map<String, Object>> stages = new ArrayList<>();
        if (summary == null || !(summary.get("workflow") instanceof List<?> list)) return stages;
        Set<String> completed = new LinkedHashSet<>();
        if (summary.get("completed_stages") instanceof List<?> done) for (Object item : done) if (item instanceof String text) completed.add(text);
        Map<String, String> pending = new LinkedHashMap<>();
        if (summary.get("pending_stages") instanceof List<?> waiting) {
            for (Object item : waiting) {
                if (item instanceof Map<?, ?> row && row.get("stage") instanceof String stage) {
                    pending.put(stage, row.get("reason") instanceof String reason ? reason : "");
                }
            }
        }
        Map<String, Object> inapplicable = new LinkedHashMap<>();
        if (summary.get("inapplicable_stages") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) if (entry.getKey() instanceof String key) inapplicable.put(key, entry.getValue());
        } else if (summary.get("inapplicable_stages") instanceof List<?> names) {
            for (Object item : names) if (item instanceof String text) inapplicable.put(text, true);
            else if (item instanceof Map<?, ?> row && row.get("stage") instanceof String stage) inapplicable.put(stage, row.get("reason"));
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> stage)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : List.of("stage", "run", "role")) copyScalar(row, stage, key);
            if (stage.get("when") instanceof List<?> when) {
                List<String> conditions = new ArrayList<>();
                for (Object condition : when) if (condition instanceof String text) conditions.add(text);
                row.put("when", conditions);
            }
            String name = String.valueOf(stage.get("stage"));
            if (completed.contains(name)) row.put("state", "completed");
            else if (pending.containsKey(name)) { row.put("state", "pending"); row.put("reason", pending.get(name)); }
            else if (inapplicable.containsKey(name)) row.put("state", "skipped");
            stages.add(row);
        }
        return stages;
    }

    private static Map<String, Object> decision(Path directory, Path runsRoot) throws IOException {
        Map<String, Object> source = readJson(directory.resolve("decision.json"), runsRoot);
        if (source == null) return null;
        Map<String, Object> visible = new LinkedHashMap<>();
        // Codes and times only: the question's prose and the person's note stay on the decision
        // page, as they do in the dashboard's run list.
        for (String key : List.of("state", "kind", "decision", "actor", "created_at", "updated_at", "expires_at")) {
            copyScalar(visible, source, key);
        }
        if (source.get("options") instanceof List<?> options) {
            List<String> names = new ArrayList<>();
            for (Object option : options) if (option instanceof String text) names.add(text);
            visible.put("options", names);
        }
        return visible;
    }

    /** A step of a run that has not written its summary yet, from its directory alone. */
    private static Map<String, Object> liveStep(Path dir, Path runsRoot) throws IOException {
        String name = dir.getFileName().toString();
        int separator = name.indexOf("--");
        String label = separator < 0 ? name : name.substring(separator + 2);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", label.replaceFirst("-\\d+$", ""));
        java.util.regex.Matcher attempt = Pattern.compile("-(\\d+)$").matcher(label);
        if (attempt.find()) row.put("attempt", Long.parseLong(attempt.group(1)));
        row.put("evidence_dir", name);
        Map<String, Object> view = null;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("view-[a-z_]+\\.json")).sorted().toList()) {
                view = readJson(file, runsRoot);
                if (view != null) break;
            }
        }
        if (view != null) {
            Map<String, Object> visible = Dashboard.publicAttempt(view);
            row.put("kind", "role");
            for (String key : List.of("stage", "role", "profile", "vendor", "model", "runner", "code", "cost_usd")) {
                if (visible.get(key) != null) row.put(key, visible.get(key));
            }
            if (visible.get("effort_requested") instanceof String effort) row.put("effort", effort);
            String state = String.valueOf(visible.get("view_state"));
            row.put("status", switch (state) {
                case "running" -> "running";
                case "completed" -> "ok".equals(visible.get("code")) || visible.get("code") == null ? "passed" : "failed";
                case "failed" -> "failed";
                default -> "unknown";
            });
            if (view.get("duration_millis") instanceof Number number) row.put("duration_ms", number.longValue());
            if ("running".equals(state) && view.get("controller_started_at") instanceof String started) row.put("started_at", started);
            if (!"running".equals(state) && !(view.get("cost_usd") instanceof Number) && view.get("vendor") != null) row.put("unpriced", true);
        } else {
            Map<String, Object> report = null;
            for (String file : List.of("machine-gate.json", "baseline-gate.json", "reproduce-gate.json", "visual-qa.json")) {
                report = readJson(dir.resolve(file), runsRoot);
                if (report != null) break;
            }
            row.put("kind", "gate");
            if (report == null) {
                row.put("status", "running");
            } else {
                row.put("status", Boolean.TRUE.equals(report.get("ok")) ? "passed" : report.get("ok") == null ? "unknown" : "failed");
                copyScalar(row, report, "code");
                Map<String, Object> pseudo = new LinkedHashMap<>();
                gateDetails(row, pseudo, dir, runsRoot);
            }
            if ("baseline".equals(row.get("stage"))) row.put("step", "baseline");
        }
        row.put("evidence", evidenceList(dir, runsRoot));
        return row;
    }

    private static List<Path> stepDirectories(Path runsRoot, String runId) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var entries = Files.list(runsRoot)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(runId + "--") || !RUN_ID.matcher(name).matches()) continue;
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || !entry.toRealPath().startsWith(runsRoot)) continue;
                dirs.add(entry);
            }
        }
        dirs.sort(Comparator.comparing(RunTimeline::createdAt).thenComparing(Path::toString));
        return dirs;
    }

    private static long createdAt(Path dir) {
        try {
            Path instance = dir.resolve("run-instance.json");
            Path probe = Files.exists(instance, LinkOption.NOFOLLOW_LINKS) ? instance : dir;
            return Files.readAttributes(probe, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).creationTime().toMillis();
        } catch (IOException unreadable) {
            return Long.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Evidence

    static List<Map<String, Object>> evidenceList(Path dir, Path runsRoot) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !dir.toRealPath().startsWith(runsRoot)) return files;
        try (var walk = Files.walk(dir, 2)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                if (!EVIDENCE_FILE.matcher(relative).matches()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", relative);
                row.put("kind", evidenceKind(relative));
                row.put("bytes", Files.size(file));
                files.add(row);
                if (files.size() >= MAX_EVIDENCE_FILES) break;
            }
        }
        return files;
    }

    private static String evidenceKind(String relative) {
        if (relative.startsWith("prompts/")) return "prompt";
        if (relative.startsWith("raw/")) return relative.endsWith(".stderr.txt") ? "stderr" : "stdout";
        if (relative.startsWith("artifacts/")) return "artifact";
        if (relative.startsWith("screenshots/")) return "screenshot";
        if (relative.equals("preview.log")) return "log";
        return "report";
    }

    /**
     * One evidence file of one run. {@code directory} must be the run itself or one of its
     * step directories, {@code relative} must match the allowlist, and the resolved file must
     * stay inside the runs directory without a symbolic link on the way.
     */
    public static Evidence evidence(Path project, String runId, String directory, String relative)
            throws IOException, NotFound {
        if (runId == null || directory == null || relative == null
                || !RUN_ID.matcher(runId).matches() || runId.contains("--")
                || !RUN_ID.matcher(directory).matches() || !ownedBy(directory, runId)
                || !EVIDENCE_FILE.matcher(relative).matches() || relative.contains("..")) throw new NotFound();
        Path runsRoot = runsRoot(project);
        Path dir = runsRoot.resolve(directory);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !dir.toRealPath().startsWith(runsRoot)) throw new NotFound();
        Path file = dir;
        for (String part : relative.split("/")) {
            file = file.resolve(part);
            if (Files.isSymbolicLink(file)) throw new NotFound();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(dir.toRealPath())) throw new NotFound();
        long size = Files.size(file);
        if (relative.endsWith(".png")) {
            if (size > MAX_IMAGE) throw new NotFound();
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 8 || (bytes[0] & 0xff) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') throw new NotFound();
            return new Evidence(bytes, "image/png");
        }
        String text;
        try (var input = Files.newInputStream(file)) {
            byte[] head = input.readNBytes((int) Math.min(size, MAX_TEXT));
            text = new String(head, StandardCharsets.UTF_8);
        }
        if (size > MAX_TEXT) text += "\n\n[truncated by the dashboard: " + size + " bytes on disk, first " + MAX_TEXT + " shown]\n";
        return new Evidence(redact(text).getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    private static final List<Pattern> SECRET_SHAPES = List.of(
            Pattern.compile("\\b(?:sk|pk|rk)-(?:[A-Za-z0-9]+-)?[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bxai-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"),
            Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|auth[_-]?token|secret|password|passwd)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]{8,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));

    /** Masks credential-shaped text. A guard for what a vendor printed, not a guarantee. */
    static String redact(String text) {
        String result = text;
        for (Pattern shape : SECRET_SHAPES) {
            result = shape.matcher(result).replaceAll(match -> {
                String prefix = match.groupCount() >= 1 && match.group(1) != null ? match.group(1) : "";
                return java.util.regex.Matcher.quoteReplacement(prefix + "[redacted]");
            });
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------

    private static Path runsRoot(Path project) throws IOException, NotFound {
        Path runs = project.toAbsolutePath().normalize().resolve(".oseer/runs");
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) throw new NotFound();
        Path real = runs.toRealPath();
        if (!real.startsWith(project.toRealPath())) throw new NotFound();
        return real;
    }

    private static Map<String, Object> readJson(Path file, Path runsRoot) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_JSON
                    || !file.toRealPath().startsWith(runsRoot)) return null;
            return Json.parseObject(Files.readString(file));
        } catch (RuntimeException | IOException unreadable) {
            return null;
        }
    }

    private static void copyScalar(Map<String, Object> dest, Map<?, ?> source, String key) {
        if (!source.containsKey(key)) return;
        Object value = source.get(key);
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) dest.put(key, value);
    }
}
