package dev.oseer.dashboard;

import dev.oseer.config.Profile;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.CredentialAudit;
import dev.oseer.execution.CredentialResolver;
import dev.oseer.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only view of workflow runs and attempts for the dashboard and timeline. */
final class DashboardState {
    private static final int MAX_VISIBLE_RUNS = 100;
    private static final int MAX_OPEN_RUNS = 20;
    private static final int MAX_VISIBLE_ATTEMPTS = 200;
    private static final long MAX_REPORT_BYTES = 2L * 1024 * 1024;
    private static final long MAX_RESERVATION_BYTES = 64 * 1024;
    private static final long MAX_ATTEMPT_BYTES = 1024 * 1024;

    private DashboardState() {}

    static Map<String, Object> snapshot(Path project, UserConfig user) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("project", project.toAbsolutePath().normalize().toString());
        state.put("config_home", user.home().toString());
        state.put("observed_at", Instant.now().toString());
        state.put("problems", user.problems());
        try {
            state.put("credential_warnings", CredentialAudit.unresolved(
                    user, new CredentialResolver()));
        } catch (RuntimeException unreadable) {
            state.put("credential_warnings", List.of());
        }
        state.put("plan", rosterPlan(user));
        var observed = readRuns(project);
        var attempts = observed.attempts();
        var runRows = observed.runs();
        var openRuns = observed.openRuns();
        // A run that has not written task-run.json is still a run. Leaving it out of this
        // list is why an in-progress review showed up only under execution attempts.
        foldOpenRuns(runRows, openRuns, attempts);
        runRows.sort(Comparator.comparing(DashboardState::sortTime).reversed());
        // Projection only: decision.json stays pending, so a person can still answer it.
        // The list stops counting a question a later run of the same tree has overtaken.
        markSuperseded(runRows);
        attempts.sort(Comparator.comparing(row -> String.valueOf(row.get("updated_at")), Comparator.reverseOrder()));
        state.put("runs", runRows.stream().limit(MAX_VISIBLE_RUNS).toList());
        // Every question still open, oldest first, with whether a page is up for it now. The
        // list is the way back to a decision whose page went away with the process that asked.
        List<Map<String, Object>> pendingDecisions = new ArrayList<>();
        for (Map<String, Object> run : runRows) {
            if (!(run.get("decision") instanceof Map<?, ?> decision)
                    || !"pending".equals(decision.get("state"))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run_id", run.get("run_id"));
            row.put("task_id", run.get("task_id"));
            row.put("kind", decision.get("kind"));
            row.put("reason", run.get("reason"));
            row.put("options", decision.get("options"));
            row.put("created_at", decision.get("created_at"));
            row.put("expires_at", decision.get("expires_at"));
            row.put("page_open", run.get("run_id") instanceof String id
                    && DecisionPage.liveLease(project, id) != null);
            pendingDecisions.add(row);
        }
        pendingDecisions.sort(Comparator.comparing(row -> String.valueOf(row.get("created_at"))));
        state.put("pending_decisions", pendingDecisions);
        openRuns.sort(Comparator.comparing(row -> String.valueOf(row.get("reserved_at")), Comparator.reverseOrder()));
        state.put("open_runs", openRuns.stream().limit(MAX_OPEN_RUNS).toList());
        state.put("dry_runs_hidden", observed.dryRunsHidden());
        state.put("attempts", attempts.stream().limit(MAX_VISIBLE_ATTEMPTS).toList());
        return state;
    }

    private static List<Map<String, Object>> rosterPlan(UserConfig user) {
        List<Map<String, Object>> plan = new ArrayList<>();
        if (user.policy() == null) return plan;
        var workflow = user.policy().workflow();
        for (var stage : workflow.stages()) {
            if (stage.role() == null) continue;
            var role = user.policy().roles().get(stage.role());
            if (role == null) continue;
            for (String name : role.profiles()) {
                Profile profile = user.profiles().get(name);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage.name()); row.put("role", stage.role()); row.put("profile", name);
                row.put("strategy", role.strategy()); row.put("when", stage.when());
                row.put("position", workflow.rotationPositionOf(stage));
                if (profile != null) {
                    row.put("vendor", profile.vendor());
                    row.put("model", profile.model()); row.put("effort", profile.effort());
                    row.put("runner", profile.runner()); row.put("verified", profile.verified());
                    row.put("read_only", profile.readOnly());
                }
                plan.add(row);
            }
        }
        return plan;
    }

    private record RunEvidence(List<Map<String, Object>> attempts, List<Map<String, Object>> runs,
                               List<Map<String, Object>> openRuns, long dryRunsHidden) {}

    private static RunEvidence readRuns(Path project) throws IOException {
        List<Map<String, Object>> attempts = new ArrayList<>();
        List<Map<String, Object>> runRows = new ArrayList<>();
        List<Map<String, Object>> openRuns = new ArrayList<>();
        long dryRunsHidden = 0L;
        Path runs = project.toAbsolutePath().normalize().resolve(".oseer/runs");
        // Never follow project-controlled links out of the evidence directory.
        if (Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)
                && runs.toRealPath().startsWith(project.toRealPath())) {
            Path runsRoot = runs.toRealPath();
            try (var directories = Files.list(runs)) {
                for (Path directory : directories.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (!directory.toRealPath().startsWith(runsRoot)) continue;
                    Path summaryFile = directory.resolve("task-run.json");
                    String directoryName = directory.getFileName().toString();
                    if (!Files.exists(summaryFile, LinkOption.NOFOLLOW_LINKS)
                            && Files.isRegularFile(directory.resolve("run.json"), LinkOption.NOFOLLOW_LINKS)) {
                        // Reserved and not summarised: still going, or stopped before it wrote
                        // a result. The timeline tells which from its live role views.
                        try {
                            Path reservation = directory.resolve("run.json");
                            if (Files.size(reservation) <= MAX_RESERVATION_BYTES && reservation.toRealPath().startsWith(runsRoot)) {
                                Map<String, Object> reserved = Json.parseObject(Files.readString(reservation));
                                Map<String, Object> open = new LinkedHashMap<>();
                                open.put("run_id", directoryName);
                                copyScalar(open, reserved, "task_id");
                                copyScalar(open, reserved, "reserved_at");
                                open.put("updated_at", Files.getLastModifiedTime(directory).toInstant().toString());
                                openRuns.add(open);
                            }
                        } catch (RuntimeException | IOException invalid) { /* an unreadable reservation is not a run */ }
                    }
                    if (Files.isRegularFile(summaryFile, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            if (Files.size(summaryFile) <= MAX_REPORT_BYTES
                                    && summaryFile.toRealPath().startsWith(runsRoot)) {
                                Map<String, Object> source = Json.parseObject(Files.readString(summaryFile));
                                if (Boolean.TRUE.equals(source.get("dry_run"))) dryRunsHidden++;
                                else runRows.add(stampStart(runView(source, summaryFile, directory, runsRoot), directory));
                            }
                        } catch (RuntimeException | IOException invalid) { /* incomplete projection is not a result */ }
                    }
                    try (var files = Files.list(directory)) {
                        for (Path file : files.filter(p -> p.getFileName().toString().startsWith("view-")
                                && p.toString().endsWith(".json") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                            if (Files.size(file) > MAX_ATTEMPT_BYTES) continue;
                            if (!file.toRealPath().startsWith(runsRoot)) continue;
                            try { attempts.add(attemptView(Json.parseObject(Files.readString(file)))); }
                            catch (RuntimeException | IOException invalid) { /* incomplete projection is not a result */ }
                        }
                    }
                }
            }
        }
        return new RunEvidence(attempts, runRows, openRuns, dryRunsHidden);
    }

    private static Map<String, Object> runView(Map<String, Object> source, Path summaryFile, Path directory,
                                         Path runsRoot) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("run_id", "task_id", "ok", "reason", "next_action", "prepare", "risk",
                "dry_run", "candidate_review_passed", "open_blocking_findings", "role_runs",
                "total_cost_usd", "unpriced_calls", "attempts_used", "continued_from",
                "candidate_fingerprint")) {
            copyScalar(row, source, key);
        }
        if (mayBeBilled(source.get("steps"))) row.put("may_be_billed", true);
        List<String> writers = stringList(source.get("writer_vendors"));
        if (writers != null) row.put("writer_vendors", writers);
        if (source.get("chain") instanceof Map<?, ?> chain) {
            Map<String, Object> visible = new LinkedHashMap<>();
            List<String> chainRuns = stringList(chain.get("runs"));
            if (chainRuns != null) visible.put("runs", chainRuns);
            for (String key : List.of("role_runs", "cost_usd", "unpriced_calls", "fix_attempts",
                    "elapsed_seconds", "elapsed_known", "calls_remaining")) {
                copyScalar(visible, chain, key);
            }
            row.put("chain", visible);
        }
        List<Map<String, Object>> coverage = objectList(source.get("review_coverage"),
                List.of("stage", "role", "source", "ok", "blocking_findings", "profile", "vendor", "assurance"));
        if (coverage != null) row.put("review_coverage", coverage);
        List<Map<String, Object>> steps = objectList(source.get("steps"),
                List.of("step", "stage", "attempt", "ok", "code", "profile", "vendor", "cost_usd",
                        "reused_from", "fix_for", "blocking_findings"));
        if (steps != null) {
            RunTimeline.markFailedStageCards(source.get("pending_stages"), steps);
            row.put("steps", steps);
        }
        List<Map<String, Object>> pending = objectList(source.get("pending_stages"), List.of("stage", "reason"));
        if (pending != null) row.put("pending_stages", pending);
        List<Map<String, Object>> failovers = failovers(source.get("steps"));
        if (!failovers.isEmpty()) row.put("failovers", failovers);
        attachDecision(row, directory, runsRoot);
        if (source.get("orca_gate") instanceof Map<?, ?> gate) {
            Map<String, Object> visible = new LinkedHashMap<>();
            copyScalar(visible, gate, "published");
            copyScalar(visible, gate, "gate_id");
            row.put("orca_gate", visible);
        }
        if (source.get("next_step") instanceof Map<?, ?> step) {
            Map<String, Object> visible = new LinkedHashMap<>();
            copyScalar(visible, step, "kind");
            copyScalar(visible, step, "summary");
            row.put("next_step", visible);
        } else if (source.get("safe_next_step") instanceof String summary) {
            row.put("next_step", Map.of("summary", summary));
        }
        if (source.get("budget_plan") instanceof Map<?, ?> plan) {
            Map<String, Object> visible = new LinkedHashMap<>();
            copyScalar(visible, plan, "requested_cap");
            copyScalar(visible, plan, "minimum_success_calls");
            row.put("budget_plan", visible);
        }
        row.put("updated_at", Files.getLastModifiedTime(summaryFile).toInstant().toString());
        return row;
    }

    /**
     * Who ran out and who took over, per stage, as the summary recorded it: a vendor switch
     * inside a role, a spent subscription that stopped it, and a switch waiting for a person.
     * The failover machinery existed and was invisible; a panel that says "review ok" without
     * "claude was spent, codex read it" hides who actually judged the work.
     */
    static List<Map<String, Object>> failovers(Object steps) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!(steps instanceof List<?> list)) return rows;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> step)) continue;
            Object stage = step.get("stage") != null ? step.get("stage") : step.get("step");
            List<String> from = stringList(step.get("failed_over_from"));
            if (from != null && !from.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage);
                row.put("kind", "switched");
                row.put("from_profiles", from);
                copyScalar(row, step, "profile");
                copyScalar(row, step, "vendor");
                rows.add(row);
            }
            List<String> spent = stringList(step.get("exhausted_profiles"));
            if ("role_quota_exhausted".equals(step.get("code")) && spent != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage);
                row.put("kind", "exhausted");
                row.put("exhausted_profiles", spent);
                rows.add(row);
            }
            if (step.get("failover_pending") instanceof Map<?, ?> waiting) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage);
                row.put("kind", "awaiting_confirmation");
                for (String key : List.of("from_profile", "from_vendor", "to_profile", "to_vendor", "to_model")) {
                    copyScalar(row, waiting, key);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> readDecision(Path directory, Path runsRoot) {
        Path file = directory.resolve("decision.json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_REPORT_BYTES
                    || !file.toRealPath().startsWith(runsRoot)) return null;
            Map<String, Object> source = Json.parseObject(Files.readString(file));
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String key : List.of("state", "kind", "decision", "actor", "updated_at",
                    "created_at", "expires_at", "candidate_fingerprint")) {
                copyScalar(visible, source, key);
            }
            List<String> options = stringList(source.get("options"));
            if (options != null) visible.put("options", options);
            return visible;
        } catch (RuntimeException | IOException invalid) {
            return null;
        }
    }

    private static void attachDecision(Map<String, Object> row, Path directory, Path runsRoot) {
        Map<String, Object> decision = readDecision(directory, runsRoot);
        if (decision == null) return;
        Object candidate = decision.remove("candidate_fingerprint");
        if ("success".equals(decision.get("kind"))) {
            // A writing run records the starting tree in its summary. The approval is
            // bound to the tree that actually passed the gates.
            row.remove("candidate_fingerprint");
        }
        if (candidate instanceof String fingerprint && !fingerprint.isBlank()) {
            row.put("candidate_fingerprint", fingerprint);
        }
        row.put("decision", decision);
    }

    private static void copyScalar(Map<String, Object> dest, Map<?, ?> source, String key) {
        if (!source.containsKey(key)) return;
        Object value = source.get(key);
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            dest.put(key, value);
        }
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<String> values = new ArrayList<>();
        for (Object item : list) if (item instanceof String text) values.add(text);
        return List.copyOf(values);
    }

    private static List<Map<String, Object>> objectList(Object raw, List<String> keys) {
        if (!(raw instanceof List<?> list)) return null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : keys) copyScalar(row, map, key);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    static Map<String, Object> attemptView(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("workflow_run_id", "run_id", "task_id", "stage", "role", "profile", "vendor",
                "model", "effort_requested", "runner", "read_only", "view_state", "updated_at", "code",
                "orca_dispatch_id", "orca_task_id", "vendor_attempt", "cost_usd", "model_reported",
                "agent_blocked_on")) {
            Object value = source.get(key);
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) row.put(key, value);
        }
        if (source.get("launch") instanceof Map<?, ?> launch) {
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String key : List.of("status", "source", "effort_source")) {
                if (launch.get(key) instanceof String value) visible.put(key, value);
            }
            Map<String, Object> effective = new LinkedHashMap<>();
            if (launch.get("effective") instanceof Map<?, ?> options) {
                for (String key : List.of("agent", "model", "effort")) {
                    if (options.get(key) instanceof String value) effective.put(key, value);
                }
            }
            visible.put("effective", effective); row.put("launch", visible);
        }
        // Orca workers report no price and no tokens, and an Orca profile with no model runs
        // the agent's own default. Both are stated rather than shown as blanks, which read as
        // "free" and "the model you asked for".
        row.put("cost_known", source.get("cost_usd") instanceof Number);
        if ("orca".equals(source.get("runner")) && source.get("model") == null) {
            row.put("model_note", "agent_default");
        }
        // Overseer's own sentence about the one click that unblocks the worker; the agent's
        // screen itself stays out, as every other raw output does.
        if (source.get("agent_blocked_on") instanceof String && source.get("resolution") instanceof String fix) {
            row.put("resolution", fix);
        }
        if ("running".equals(row.get("view_state")) && !controllerAlive(source)) {
            row.put("view_state", "unknown_controller_stopped");
        }
        return row;
    }

    /**
     * The view says the role is still running. That holds while the process that wrote it
     * is still that process.
     *
     * <p>The start time Linux exposes is in clock ticks, so it can disagree with the stamp
     * the controller recorded by a few milliseconds. An exact string compare treated that
     * tick as a dead controller, and the run then disappeared from the runs list. A recycled
     * pid starts at a different moment, not a tick later.
     */
    private static boolean controllerAlive(Map<String, Object> source) {
        if (!(source.get("controller_pid") instanceof Number pid)) return false;
        var handle = ProcessHandle.of(pid.longValue()).filter(ProcessHandle::isAlive);
        if (handle.isEmpty()) return false;
        if (!(source.get("controller_started_at") instanceof String recorded) || recorded.isBlank()) return false;
        var observed = handle.get().info().startInstant();
        if (observed.isEmpty()) return true;
        try {
            long skew = Math.abs(Duration.between(Instant.parse(recorded), observed.get()).toMillis());
            return skew <= 2_000;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static Map<String, Object> stampStart(Map<String, Object> row, Path directory) {
        Path reservation = directory.resolve("run.json");
        try {
            if (Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS) && Files.size(reservation) <= MAX_RESERVATION_BYTES) {
                Map<String, Object> reserved = Json.parseObject(Files.readString(reservation));
                if (reserved.get("reserved_at") instanceof String at && !at.isBlank()) row.put("started_at", at);
            }
        } catch (RuntimeException | IOException ignored) { /* a start time is optional; mtime still sorts */ }
        return row;
    }

    private static void foldOpenRuns(List<Map<String, Object>> runs, List<Map<String, Object>> openRuns,
                                      List<Map<String, Object>> attempts) {
        Map<String, String> view = new HashMap<>();
        for (Map<String, Object> attempt : attempts) {
            if (!(attempt.get("workflow_run_id") instanceof String id) || id.isBlank()) continue;
            String state = String.valueOf(attempt.get("view_state"));
            if ("running".equals(state)) view.put(id, "running");
            else if ("unknown_controller_stopped".equals(state)) {
                view.merge(id, state, (previous, stopped) -> "running".equals(previous) ? previous : stopped);
            }
        }
        for (Map<String, Object> open : openRuns) {
            if (!(open.get("run_id") instanceof String id)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run_id", id);
            copyScalar(row, open, "task_id");
            if (open.get("reserved_at") instanceof String at) row.put("started_at", at);
            copyScalar(row, open, "updated_at");
            String state = view.get(id);
            if ("running".equals(state)) row.put("state", "running");
            else if ("unknown_controller_stopped".equals(state)) row.put("state", "controller_stopped");
            else row.put("state", "no_summary");
            runs.add(row);
        }
    }

    private static Instant sortTime(Map<String, Object> row) {
        for (String key : List.of("started_at", "updated_at")) {
            Instant parsed = instantOf(row.get(key));
            if (parsed != null) return parsed;
        }
        return Instant.EPOCH;
    }

    private static Instant instantOf(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        try { return Instant.parse(text); }
        catch (RuntimeException unreadable) { return null; }
    }

    private static boolean mayBeBilled(Object steps) {
        if (!(steps instanceof List<?> list)) return false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> step)) continue;
            if (Boolean.TRUE.equals(step.get("may_be_billed"))) return true;
            if (!(step.get("vendor_attempts") instanceof List<?> attempts)) continue;
            for (Object raw : attempts) {
                if (raw instanceof Map<?, ?> attempt && Boolean.TRUE.equals(attempt.get("may_be_billed"))) return true;
            }
        }
        return false;
    }

    /**
     * A pending decision another run has overtaken. The caller shows {@code superseded}
     * and does not write {@code decision.json}.
     */
    static boolean decisionSuperseded(Path runsRoot, String runId) throws IOException {
        return supersededIds(runFacts(runsRoot)).contains(runId);
    }

    private static void markSuperseded(List<Map<String, Object>> runs) {
        Set<String> ids = supersededIds(runs);
        for (Map<String, Object> run : runs) {
            if (!ids.contains(String.valueOf(run.get("run_id")))) continue;
            if (run.get("decision") instanceof Map<?, ?> decision && "pending".equals(decision.get("state"))) {
                @SuppressWarnings("unchecked") Map<String, Object> writable = (Map<String, Object>) decision;
                writable.put("state", "superseded");
            }
        }
    }

    private static Set<String> supersededIds(List<Map<String, Object>> runs) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> run : runs) {
            if (Boolean.TRUE.equals(run.get("dry_run"))) continue;
            if (run.get("continued_from") instanceof String from && !from.isBlank()) ids.add(from);
        }
        for (Map<String, Object> run : runs) {
            if (Boolean.TRUE.equals(run.get("dry_run"))) continue;
            String id = String.valueOf(run.get("run_id"));
            if (hasSuccessDecision(run)) ids.remove(id);
            if (laterApprovalOfSameTree(runs, run)) ids.add(id);
        }
        return ids;
    }

    private static boolean hasSuccessDecision(Map<String, Object> run) {
        return run.get("decision") instanceof Map<?, ?> decision && "success".equals(decision.get("kind"));
    }

    /** A replacement must have reached its own approval for this exact candidate. */
    private static boolean laterApprovalOfSameTree(List<Map<String, Object>> runs, Map<String, Object> run) {
        if (!(run.get("task_id") instanceof String task) || task.isBlank()) return false;
        if (!(run.get("candidate_fingerprint") instanceof String fingerprint) || fingerprint.isBlank()) return false;
        Instant start = instantOf(run.get("started_at"));
        if (start == null) return false;
        String id = String.valueOf(run.get("run_id"));
        for (Map<String, Object> other : runs) {
            if (id.equals(String.valueOf(other.get("run_id"))) || Boolean.TRUE.equals(other.get("dry_run"))) continue;
            if (!Boolean.TRUE.equals(other.get("ok")) || !hasSuccessDecision(other)) continue;
            if (!task.equals(other.get("task_id")) || !fingerprint.equals(other.get("candidate_fingerprint"))) continue;
            Instant later = instantOf(other.get("started_at"));
            if (later != null && later.isAfter(start)) return true;
        }
        return false;
    }

    private static List<Map<String, Object>> runFacts(Path runsRoot) throws IOException {
        List<Map<String, Object>> runs = new ArrayList<>();
        if (!Files.isDirectory(runsRoot, LinkOption.NOFOLLOW_LINKS)) return runs;
        try (var directories = Files.list(runsRoot)) {
            for (Path directory : directories.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (!directory.toRealPath().startsWith(runsRoot)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("run_id", directory.getFileName().toString());
                Path summary = directory.resolve("task-run.json");
                if (Files.isRegularFile(summary, LinkOption.NOFOLLOW_LINKS) && Files.size(summary) <= MAX_REPORT_BYTES
                        && summary.toRealPath().startsWith(runsRoot)) {
                    try {
                        Map<String, Object> source = Json.parseObject(Files.readString(summary));
                        for (String key : List.of("task_id", "ok", "continued_from", "candidate_fingerprint", "dry_run")) {
                            copyScalar(row, source, key);
                        }
                        attachDecision(row, directory, runsRoot);
                    } catch (RuntimeException invalid) { /* an unreadable summary supersedes nothing */ }
                }
                Path reservation = directory.resolve("run.json");
                if (Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS) && Files.size(reservation) <= MAX_RESERVATION_BYTES
                        && reservation.toRealPath().startsWith(runsRoot)) {
                    try {
                        Map<String, Object> reserved = Json.parseObject(Files.readString(reservation));
                        if (!row.containsKey("task_id")) copyScalar(row, reserved, "task_id");
                        if (reserved.get("reserved_at") instanceof String at) row.put("started_at", at);
                    } catch (RuntimeException invalid) { /* a start time is optional */ }
                }
                if (row.containsKey("task_id") || row.containsKey("continued_from") || row.containsKey("started_at")) runs.add(row);
            }
        }
        return runs;
    }
}
