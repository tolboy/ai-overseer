package dev.oseer.dashboard;

import dev.oseer.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final List<String> GATE_REPORTS = List.of(
            "machine-gate.json", "baseline-gate.json", "reproduction-gate.json", "visual-qa.json");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final Pattern EVIDENCE_FILE = Pattern.compile(
            "(prompts/[A-Za-z0-9._-]{1,120}\\.md"
                    + "|raw/[A-Za-z0-9._-]{1,120}\\.(?:stdout|stderr)\\.txt"
                    + "|artifacts/[A-Za-z0-9._-]{1,120}\\.json"
                    + "|screenshots/[A-Za-z0-9._-]{1,160}\\.png"
                    + "|(?:machine-gate|baseline-gate|reproduction-gate|visual-qa)\\.json"
                    + "|preview\\.log)");

    /** Thrown for a run or file that does not exist or may not be served; the caller answers 404. */
    public static final class NotFound extends Exception {
        NotFound() { super(null, null, false, false); }
    }

    public record Evidence(byte[] bytes, String contentType) {}

    // ---------------------------------------------------------------------------------------
    // Timeline projection

    public static Map<String, Object> read(Path project, String runId) throws IOException, NotFound {
        if (runId == null || !RUN_ID.matcher(runId).matches()) throw new NotFound();
        Path runsRoot = runsRoot(project);
        Path directory = runsRoot.resolve(runId);
        if (!isStepDirectory(runsRoot, runId)) throw new NotFound();
        Map<String, Object> summary = readJson(directory.resolve("task-run.json"), runsRoot);
        Map<String, Object> reserved = readJson(directory.resolve("run.json"), runsRoot);
        if (summary == null && reserved == null) throw new NotFound();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", runId);
        Object task = summary != null ? summary.get("task_id") : reserved != null ? reserved.get("task_id") : null;
        result.put("task_id", task instanceof String text ? text : null);
        if (reserved != null && reserved.get("reserved_at") instanceof String at) result.put("started_at", at);

        List<Map<String, Object>> steps;
        if (summary != null && summary.get("steps") instanceof List<?>) {
            result.put("state", "finished");
            for (String key : List.of("ok", "reason", "risk", "mode", "next_action", "total_cost_usd", "unpriced_calls",
                    "role_runs", "attempts_used", "open_blocking_findings", "candidate_review_passed",
                    "continued_from", "decision_state", "decision_kind", "decision")) {
                copyScalar(result, summary, key);
            }
            if (summary.get("next_step") instanceof Map<?, ?> next && next.get("summary") instanceof String text) {
                result.put("next_step", redact(text));
            } else if (summary.get("safe_next_step") instanceof String text) {
                result.put("next_step", redact(text));
            }
            steps = summarySteps((List<?>) summary.get("steps"), runsRoot, runId);
        } else {
            boolean running = false;
            boolean controllerStopped = false;
            steps = new ArrayList<>();
            for (Path step : stepDirectories(runsRoot, runId)) {
                Map<String, Object> row = liveStep(step, runsRoot, runId);
                if (row == null) continue;
                if ("running".equals(row.get("status"))) running = true;
                if ("controller_stopped".equals(row.get("status"))) controllerStopped = true;
                steps.add(row);
            }
            result.put("state", running ? "running" : controllerStopped ? "controller_stopped"
                    : summary != null ? "stopped" : "no_summary");
            if (summary != null) {
                copyScalar(result, summary, "ok");
                copyScalar(result, summary, "reason");
                copyScalar(result, summary, "next_action");
            }
        }
        if (summary != null && summary.get("finding_history") instanceof List<?> rounds) {
            List<String> ids = dev.oseer.ledger.Findings.openBlockers(rounds).stream()
                    .filter(finding -> "contract_gap".equals(finding.get("category")))
                    .map(finding -> finding.get("id"))
                    .filter(id -> id instanceof String text && !text.isBlank() && text.length() <= 80 && redact(text).equals(text))
                    .map(String.class::cast).toList();
            if (!ids.isEmpty()) result.put("contract_gap_ids", ids);
        }
        if (summary != null && summary.get("unverified_findings") instanceof List<?> rows && !rows.isEmpty()) {
            List<Map<String, Object>> visible = new ArrayList<>();
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (String key : List.of("id", "stage", "path", "severity", "severity_as_filed",
                        "verification_reason", "verification_detail")) {
                    Object value = raw.get(key);
                    if (value instanceof String text && !text.isBlank()) row.put(key, redact(text));
                }
                if (!row.isEmpty()) visible.add(row);
            }
            if (!visible.isEmpty()) result.put("unverified_findings", visible);
        }
        List<Map<String, Object>> workflow = workflow(summary);
        result.put("workflow", workflow);
        if (summary != null) markFailedStageCards(summary.get("pending_stages"), steps);
        Map<String, Object> baseline = baseline(summary, runsRoot);
        attribute(steps, baseline);
        maskCommands(baseline);
        steps.forEach(RunTimeline::maskCommands);
        result.put("baseline", baseline);
        result.put("steps", steps);
        // The chip row and the stage cards are one fact. A stage that passed used to read
        // "completed" on the chip and "passed" on the card, and a stage the run never opened
        // read "still open" under a header that already said the run had stopped.
        agreeWithCards(workflow, steps);
        if (Boolean.FALSE.equals(result.get("ok"))) {
            List<Map<String, Object>> causes = stopCauses(steps);
            if (!causes.isEmpty()) result.put("stop_causes", causes);
        }

        long duration = 0L; boolean durationKnown = !steps.isEmpty();
        double cost = 0.0; long unpriced = 0L;
        for (Map<String, Object> step : steps) {
            if (step.get("duration_ms") instanceof Number number) duration += number.longValue();
            else if (!"skipped".equals(step.get("status")) && !Boolean.TRUE.equals(step.get("reused"))) durationKnown = false;
            if (step.get("cost_usd") instanceof Number number) cost += number.doubleValue();
            if (step.get("unpriced_calls") instanceof Number n) unpriced += n.longValue();
            else if (Boolean.TRUE.equals(step.get("unpriced"))) unpriced++;
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("duration_ms", duration);
        totals.put("duration_complete", durationKnown);
        if (result.get("total_cost_usd") instanceof Number recorded) cost = recorded.doubleValue();
        totals.put("cost_usd_known", Math.round(cost * 1_000_000d) / 1_000_000d);
        // The engine's own count wins when the run recorded one; the run list reads the same.
        if (result.get("unpriced_calls") instanceof Number recorded) unpriced = recorded.longValue();
        totals.put("unpriced_calls", unpriced);
        totals.put("unpriced_steps", steps.stream().filter(step -> Boolean.TRUE.equals(step.get("unpriced"))).count());
        if (steps.stream().anyMatch(RunTimeline::mayBeBilled)) totals.put("may_be_billed", true);
        result.put("totals", totals);
        Map<String, Object> decisionRecord = decision(directory, runsRoot);
        // Shown, not rewritten. A later continuation or a later run of the same tree leaves
        // decision.json pending; the ledger is the record of what was asked.
        if (decisionRecord != null && "pending".equals(decisionRecord.get("state"))) {
            try {
                if (Dashboard.decisionSuperseded(runsRoot, runId)) {
                    decisionRecord.put("state", "superseded");
                }
            } catch (IOException unread) { /* a sibling that cannot be read does not change this decision */ }
        }
        result.put("decision_record", decisionRecord);
        result.put("observed_at", Instant.now().toString());
        return result;
    }

    /**
     * Chip state follows the card when a stage ran, and names the reason when it did not.
     * {@code not_reached} and {@code judged_an_earlier_candidate} are not an open stage.
     */
    private static void agreeWithCards(List<Map<String, Object>> stages, List<Map<String, Object>> steps) {
        Map<String, String> cards = new LinkedHashMap<>();
        for (Map<String, Object> step : steps) {
            if (step.get("stage") instanceof String name && step.get("status") instanceof String status && !status.isBlank()
                    && !"unknown".equals(status)) {
                cards.put(name, status);
            }
        }
        for (Map<String, Object> row : stages) {
            if ("did_not_pass".equals(row.get("reason"))) { row.put("state", "failed"); continue; }
            String card = cards.get(String.valueOf(row.get("stage")));
            if (card != null) { row.put("state", card); continue; }
            if ("completed".equals(row.get("state"))) { row.put("state", "passed"); continue; }
            if (!"pending".equals(row.get("state"))) continue;
            String reason = String.valueOf(row.get("reason"));
            if ("not_reached".equals(reason)) row.put("state", "not_reached");
            else if ("judged_an_earlier_candidate".equals(reason)) row.put("state", "earlier_candidate");
        }
    }

    /** The vendor can finish its call while the workflow refuses its verdict. */
    static void markFailedStageCards(Object pendingStages, List<Map<String, Object>> steps) {
        if (!(pendingStages instanceof List<?> pending)) return;
        Set<String> failed = new LinkedHashSet<>();
        for (Object item : pending) {
            if (item instanceof Map<?, ?> row && "did_not_pass".equals(row.get("reason"))
                    && row.get("stage") instanceof String stage) failed.add(stage);
        }
        for (int i = steps.size() - 1; i >= 0 && !failed.isEmpty(); i--) {
            Map<String, Object> step = steps.get(i);
            if (failed.remove(step.getOrDefault("stage", step.get("step")))) step.put("status", "failed");
        }
    }

    private static boolean mayBeBilled(Map<String, Object> step) {
        if (Boolean.TRUE.equals(step.get("may_be_billed"))) return true;
        if (!(step.get("failed_attempts") instanceof List<?> attempts)) return false;
        for (Object item : attempts) {
            if (item instanceof Map<?, ?> attempt && Boolean.TRUE.equals(attempt.get("may_be_billed"))) return true;
        }
        return false;
    }

    /**
     * What actually went wrong at the last failed step, attempt by attempt: a 502 then a
     * truncated fallback is two causes, and the run's own reason (for example
     * {@code vendor_protocol_failed}) names neither.
     */
    public static List<Map<String, Object>> stopCauses(List<Map<String, Object>> steps) {
        Map<String, Object> failed = null;
        for (Map<String, Object> step : steps) if ("failed".equals(step.get("status"))) failed = step;
        List<Map<String, Object>> causes = new ArrayList<>();
        if (failed == null || Boolean.TRUE.equals(failed.get("ok"))) return causes;
        if (failed.get("failed_attempts") instanceof List<?> attempts) {
            for (Object item : attempts) {
                if (!(item instanceof Map<?, ?> attempt)) continue;
                Map<String, Object> cause = new LinkedHashMap<>();
                cause.put("stage", failed.get("stage"));
                for (String key : List.of("profile", "vendor", "http_status", "code")) if (attempt.get(key) != null) cause.put(key, attempt.get(key));
                causes.add(cause);
            }
        }
        Object code = failed.get("code"), profile = failed.get("profile");
        boolean covered = causes.stream().anyMatch(c -> java.util.Objects.equals(c.get("code"), code)
                && java.util.Objects.equals(c.get("profile"), profile));
        if (code != null && !covered) {
            Map<String, Object> cause = new LinkedHashMap<>();
            cause.put("stage", failed.get("stage"));
            for (String key : List.of("profile", "vendor", "http_status", "code")) if (failed.get(key) != null) cause.put(key, failed.get(key));
            causes.add(cause);
        }
        return causes;
    }

    private static List<Map<String, Object>> summarySteps(List<?> source, Path runsRoot, String runId) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : source) {
            if (!(item instanceof Map<?, ?> step)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : List.of("step", "stage", "attempt", "ok", "code", "profile", "vendor", "cost_usd", "role",
                    "reused_from", "fix_for", "blocking_findings", "independence")) {
                copyScalar(row, step, key);
            }
            if (!row.containsKey("stage")) row.put("stage", step.get("step"));
            String kind = kindOf(step);
            row.put("kind", kind);
            if ("role".equals(kind) && step.get("step") instanceof String role) row.putIfAbsent("role", role);
            if (step.get("reused_from") instanceof String) row.put("reused", true);
            row.put("status", step.get("ok") instanceof Boolean ok ? (ok ? "passed" : "failed") : "unknown");
            if ("skipped".equals(step.get("code"))) row.put("status", "skipped");
            // Model, effort, duration and the number of vendor attempts come from the attempts
            // the role made, not from the profile: a failover or a retry changes them.
            if (step.get("vendor_attempts") instanceof List<?> attempts && !attempts.isEmpty()) {
                long millis = 0L; boolean known = true; int count = 0; long unpricedCalls = 0L;
                double attemptsCost = 0.0;
                Map<?, ?> last = null;
                for (Object raw : attempts) {
                    if (!(raw instanceof Map<?, ?> attempt)) continue;
                    count++; last = attempt;
                    // Counted per vendor call, as the engine counts unpriced_calls: a failed
                    // attempt with no price is unpriced even when a later attempt reported one.
                    if (attempt.get("cost_usd") instanceof Number price) attemptsCost += price.doubleValue();
                    else unpricedCalls++;
                    if (attempt.get("duration_millis") instanceof Number number) millis += number.longValue();
                    else known = false;
                }
                row.put("vendor_attempts", count);
                if (count > unpricedCalls) row.put("cost_usd", attemptsCost);
                if (unpricedCalls > 0) row.put("unpriced_calls", unpricedCalls);
                if (known && count > 0) row.put("duration_ms", millis);
                List<Map<String, Object>> failures = failedAttempts(attempts);
                if (!failures.isEmpty()) row.put("failed_attempts", failures);
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
            if (row.get("unpriced_calls") instanceof Long n && n > 0) row.put("unpriced", true);
            else if (Boolean.TRUE.equals(row.get("unpriced"))) row.put("unpriced_calls", 1L);
            if (step.get("role_failovers") instanceof List<?> switches && !switches.isEmpty()) {
                List<Map<String, Object>> visible = new ArrayList<>();
                for (Object raw : switches) {
                    if (!(raw instanceof Map<?, ?> event)) continue;
                    Map<String, Object> one = new LinkedHashMap<>();
                    for (String key : List.of("from_profile", "to_profile", "from_vendor", "to_vendor", "cause", "authorized_by"))
                        copyScalar(one, event, key);
                    visible.add(one);
                }
                if (!visible.isEmpty()) row.put("failovers", visible);
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
            if (ownedBy(runsRoot, name, runId) && isStepDirectory(runsRoot, name)) return name;
        }
        // Stage names distinguish repeated roles. Single-stage roles keep their historical
        // role-named directory, including failed calls whose report records no artifact.
        if (step.get("attempt") instanceof Number attempt) {
            for (String key : List.of("stage", "step")) {
                if (!(step.get(key) instanceof String text)) continue;
                for (String label : List.of(dev.oseer.config.Workflow.slug(text), text.replace('_', '-'))) {
                    String name = runId + "--" + label + "-" + attempt.longValue();
                    if (ownedBy(runsRoot, name, runId) && isStepDirectory(runsRoot, name)) return name;
                }
            }
        }
        return null;
    }

    private static boolean ownedBy(Path runsRoot, String directory, String runId) {
        if (!RUN_ID.matcher(directory).matches()) return false;
        if (directory.equals(runId)) return true;
        String prefix = runId + "--";
        if (!directory.startsWith(prefix)) return false;
        // A user-chosen workflow ID can contain the same separator as a step ID. Its
        // own receipt marks another workflow, including the step directories under it.
        for (int boundary = directory.indexOf("--", prefix.length()); boundary >= 0;
                boundary = directory.indexOf("--", boundary + 2)) {
            if (hasWorkflowReceipt(runsRoot, directory.substring(0, boundary))) return false;
        }
        return !hasWorkflowReceipt(runsRoot, directory);
    }

    private static boolean hasWorkflowReceipt(Path runsRoot, String name) {
        if (!isStepDirectory(runsRoot, name)) return false;
        Path dir = runsRoot.resolve(name);
        return Files.isRegularFile(dir.resolve("run.json"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(dir.resolve("task-run.json"), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isStepDirectory(Path runsRoot, String name) {
        if (!RUN_ID.matcher(name).matches()) return false;
        Path dir = runsRoot.resolve(name);
        try {
            return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && dir.toRealPath().equals(dir);
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static void gateDetails(Map<String, Object> row, Map<?, ?> step, Path dir, Path runsRoot) {
        Map<String, Object> report = null;
        if (step.get("report") instanceof String text) {
            String normalized = text.replace('\\', '/');
            String name = normalized.substring(normalized.lastIndexOf('/') + 1);
            if (GATE_REPORTS.contains(name)) report = readJson(dir.resolve(name), runsRoot);
        }
        if (report == null) report = findGateReport(dir, runsRoot);
        if (report != null) gateDetails(row, report);
    }

    private static Map<String, Object> findGateReport(Path dir, Path runsRoot) {
        for (String name : GATE_REPORTS) {
            Map<String, Object> report = readJson(dir.resolve(name), runsRoot);
            if (report != null) return report;
        }
        return null;
    }

    private static void gateDetails(Map<String, Object> row, Map<String, Object> report) {
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
            if (!"gate".equals(step.get("kind")) || "baseline".equals(step.get("step"))
                    || "reproduction".equals(step.get("phase"))) continue;
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

    @SuppressWarnings("unchecked")
    private static void maskCommands(Map<String, Object> row) {
        if (row.get("commands") instanceof List<?> commands) {
            for (Object item : commands) {
                if (item instanceof Map<?, ?> command && command.get("command") instanceof String text) {
                    ((Map<String, Object>) command).put("command", redact(text));
                }
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
            for (Object item : list) if (item instanceof String text) names.add(redact(text));
            baseline.put("declared", names);
        }
        if (summary.get("baseline_report") instanceof String path) {
            String normalized = path.replace('\\', '/');
            int marker = normalized.lastIndexOf(".oseer/runs/");
            if (marker >= 0) {
                String rest = normalized.substring(marker + ".oseer/runs/".length());
                int slash = rest.indexOf('/');
                if (slash > 0 && rest.substring(slash + 1).equals("baseline-gate.json")
                        && isStepDirectory(runsRoot, rest.substring(0, slash))) {
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
    private static Map<String, Object> liveStep(Path dir, Path runsRoot, String runId) throws IOException {
        String name = dir.getFileName().toString();
        String label = name.substring(runId.length() + 2);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", label.replaceFirst("-\\d+$", ""));
        java.util.regex.Matcher attempt = Pattern.compile("-(\\d{1,10})$").matcher(label);
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
                case "unknown_controller_stopped" -> "controller_stopped";
                case "completed" -> "ok".equals(visible.get("code")) || visible.get("code") == null ? "passed" : "failed";
                case "failed" -> "failed";
                default -> "unknown";
            });
            if (view.get("duration_millis") instanceof Number number) row.put("duration_ms", number.longValue());
            if (view.get("http_status") instanceof Number status) row.put("http_status", status.longValue());
            else if (view.get("http_status") instanceof String status && status.matches("\\d{3}")) row.put("http_status", Long.parseLong(status));
            if ("running".equals(state) && view.get("controller_started_at") instanceof String started) row.put("started_at", started);
            if (!"running".equals(state) && !(view.get("cost_usd") instanceof Number) && view.get("vendor") != null) row.put("unpriced", true);
        } else {
            Map<String, Object> report = findGateReport(dir, runsRoot);
            row.put("kind", "gate");
            if (report == null) {
                row.put("status", "running");
            } else {
                row.put("status", Boolean.TRUE.equals(report.get("ok")) ? "passed" : report.get("ok") == null ? "unknown" : "failed");
                copyScalar(row, report, "code");
                gateDetails(row, report);
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
                if (name.equals(runId) || !ownedBy(runsRoot, name, runId)) continue;
                if (!isStepDirectory(runsRoot, name)) continue;
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
        if (!isStepDirectory(runsRoot, dir.getFileName().toString())) return files;
        try (var walk = Files.walk(dir, 2)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                String relative = dir.relativize(file).toString().replace('\\', '/');
                if (!EVIDENCE_FILE.matcher(relative).matches() || relative.contains("..") || !file.toRealPath().equals(file)) continue;
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
                || !RUN_ID.matcher(runId).matches()
                || !EVIDENCE_FILE.matcher(relative).matches() || relative.contains("..")) throw new NotFound();
        Path runsRoot = runsRoot(project);
        if (!hasWorkflowReceipt(runsRoot, runId) || !ownedBy(runsRoot, directory, runId)) throw new NotFound();
        Path dir = runsRoot.resolve(directory);
        if (!isStepDirectory(runsRoot, directory)) throw new NotFound();
        Path file = dir;
        for (String part : relative.split("/")) {
            file = file.resolve(part);
            if (Files.isSymbolicLink(file)) throw new NotFound();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)) throw new NotFound();
        boolean image = relative.endsWith(".png");
        if (image && Files.size(file) > MAX_IMAGE) throw new NotFound();
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes((int) (image ? MAX_IMAGE : MAX_TEXT) + 1);
        }
        if (image) {
            if (bytes.length > MAX_IMAGE || bytes.length < PNG_SIGNATURE.length
                    || !Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) throw new NotFound();
            return new Evidence(bytes, "image/png");
        }
        boolean truncated = bytes.length > MAX_TEXT;
        int length = Math.min(bytes.length, (int) MAX_TEXT);
        if (truncated) {
            // An incomplete line can contain a partial token that would evade a pattern.
            while (length > 0 && bytes[length - 1] != '\n') length--;
        }
        String text = redact(new String(bytes, 0, length, StandardCharsets.UTF_8));
        if (truncated) text += "\n[truncated by the dashboard at " + MAX_TEXT + " bytes]\n";
        return new Evidence(text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
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
            Pattern.compile("(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|\\z)"));

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
        Path runs = project.toRealPath().resolve(".oseer/runs");
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) throw new NotFound();
        Path real = runs.toRealPath();
        if (!real.equals(runs)) throw new NotFound();
        return real;
    }

    private static Map<String, Object> readJson(Path file, Path runsRoot) {
        try {
            if (!file.normalize().startsWith(runsRoot) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_JSON || !file.toRealPath().equals(file)) return null;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes((int) MAX_JSON + 1);
                return bytes.length > MAX_JSON ? null : Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (RuntimeException | IOException unreadable) {
            return null;
        }
    }

    private static void copyScalar(Map<String, Object> dest, Map<?, ?> source, String key) {
        if (!source.containsKey(key)) return;
        Object value = source.get(key);
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) dest.put(key, value);
    }

    /**
     * Attempts that failed, with what the provider said in machine terms: the code, the HTTP
     * status and the phase it failed in. Never the body or a message, which could quote a key.
     */
    static List<Map<String, Object>> failedAttempts(List<?> attempts) {
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Object raw : attempts) {
            if (!(raw instanceof Map<?, ?> attempt) || !Boolean.FALSE.equals(attempt.get("ok"))) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            for (String key : List.of("profile", "vendor", "provider", "code", "failure_phase", "delivery", "may_be_billed"))
                copyScalar(one, attempt, key);
            Object status = attempt.get("http_status");
            if (status instanceof Number number) one.put("http_status", number.longValue());
            else if (status instanceof String text && text.matches("\\d{3}")) one.put("http_status", Long.parseLong(text));
            if (attempt.get("duration_millis") instanceof Number number) one.put("duration_ms", number.longValue());
            if (attempt.get("api_attempt_count") instanceof Number number) one.put("api_attempts", number.longValue());
            failures.add(one);
        }
        return failures;
    }

}
