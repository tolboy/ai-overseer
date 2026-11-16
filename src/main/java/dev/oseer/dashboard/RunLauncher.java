package dev.oseer.dashboard;

import dev.oseer.json.Json;
import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starts {@code oseer do}, {@code run}, {@code approve} and {@code land} for the browser.
 *
 * <p>The browser never names a command line. It sends a form; this class turns the form into
 * the same argument vector a person would type, validated field by field. Every command that
 * can pay a vendor is started only from a preview: the same arguments run first with
 * {@code --dry-run}, the operator reads the cast, the call cap and the estimate, and the start
 * request names that preview. A preview is bound to the arguments, the project's HEAD and the
 * policy, profile and task files it read; when any of them changed the start is refused and
 * the operator previews again. That preview is the plan approval.
 *
 * <p>Each command runs as a child process of the dashboard, in the project directory, with
 * the dashboard's environment and configuration home. Its JSON result and its narration go to
 * {@code <home>/ui-jobs/<job>/}. One job runs per project at a time; the engine's own lease
 * still guards the run and refuses a second writer.
 */
public final class RunLauncher implements AutoCloseable {
    static final Set<String> RISKS = Set.of("low", "medium", "high");
    static final Set<String> PREPARE = Set.of("off", "auto", "always");
    static final Set<String> CONTINUING = Set.of("retry", "switch", "advance", "apply", "replan", "proceed");
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9_./*-][A-Za-z0-9_./*, -]{0,399}");
    static final Pattern STAGE = Pattern.compile("[a-z][a-z0-9-]{0,40}");
    static final Pattern EFFORT = Pattern.compile("[a-z]{1,16}");
    static final Pattern NARRATED_RUN = Pattern.compile("^run\\s+(\\S+)");
    static final Pattern SECRETISH = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_-]{8,}|xai-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{8,}|bearer\\s+[A-Za-z0-9._-]{8,}"
                    + "|(api[_-]?key|token|secret|password)\\s*[=:]\\s*\\S+)");
    static final int MAX_JOBS = 50;
    public static final String JOB_RECORD = "job.json";
    static final int TAIL_LINES = 120;
    static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    static final Duration PREVIEW_TIMEOUT = Duration.ofMinutes(3);
    static final Duration CANCEL_GRACE = Duration.ofSeconds(20);

    /** A refusal the browser shows by code; the message never carries file contents. */
    public static final class Refused extends Exception {
        final String code; final int status;
        Refused(int status, String code, String message) { super(message); this.status = status; this.code = code; }
        public String code() { return code; }
        public int status() { return status; }
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", false); map.put("code", code); map.put("message", getMessage());
            return map;
        }
    }

    record Preview(String id, Path project, List<String> argv, String fingerprint, Instant created,
                   String kind, String runId, Path goalFile, String label) {}

    static final class Job {
        final String id, kind, label; final Path project, dir; final Instant started;
        final List<List<String>> chain; int step; volatile Process process; volatile String runId;
        volatile String state = "running"; volatile Integer exit; volatile Instant ended;
        volatile boolean cancelled; volatile Map<String, Object> result; volatile String note;
        Job(String id, String kind, String label, Path project, Path dir, List<List<String>> chain, String runId) {
            this(id, kind, label, project, dir, chain, runId, Instant.now());
        }
        Job(String id, String kind, String label, Path project, Path dir, List<List<String>> chain, String runId, Instant started) {
            this.id = id; this.kind = kind; this.label = label; this.project = project; this.dir = dir;
            this.chain = chain; this.runId = runId; this.started = started;
        }
        boolean live() { return "running".equals(state); }
        Map<String, Object> summary() { return summaryOf(this); }
        Map<String, Object> view() { return viewOf(this); }
    }

    private final Path home;
    private final List<String> command;
    private final Path defaultProject;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Preview> previews = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** The dashboard's own Java and class path run the engine; tests pass a stand-in. */
    public RunLauncher(Path home, Path defaultProject) {
        this(home, defaultProject, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "dev.oseer.Main"));
    }

    public RunLauncher(Path home, Path defaultProject, List<String> command) {
        this.home = home.toAbsolutePath().normalize();
        this.defaultProject = defaultProject == null ? null : defaultProject.toAbsolutePath().normalize();
        this.command = List.copyOf(command);
        restore();
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Each job keeps a small record beside its step files, so a restarted dashboard lists the
     * jobs it had and re-attaches to their runs. Only identity and outcome are kept: the argv
     * stays in memory, and the narration and result are read back from the step files.
     */
    private void persist(Job job) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", job.id); record.put("kind", job.kind); record.put("label", job.label);
        record.put("project", job.project.toString()); record.put("steps", (long) job.chain.size());
        record.put("step", (long) job.step); record.put("run_id", job.runId); record.put("state", job.state);
        record.put("exit_code", job.exit == null ? null : (long) job.exit);
        record.put("started_at", job.started.toString());
        record.put("ended_at", job.ended == null ? null : job.ended.toString());
        record.put("note", job.note);
        try {
            Files.createDirectories(job.dir);
            dev.oseer.json.JsonFile.writeAtomically(job.dir.resolve(JOB_RECORD), record);
        } catch (IOException ignored) {
            // A job that cannot be recorded still runs; it is only absent after a restart.
        }
    }

    /** Jobs a previous dashboard recorded. One that was still running is shown as interrupted. */
    private void restore() {
        Path root = home.resolve("ui-jobs");
        if (!Files.isDirectory(root)) return;
        List<Job> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                Path file = dir.resolve(JOB_RECORD);
                if (!Files.isRegularFile(file)) continue;
                try {
                    Map<String, Object> r = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                    String id = String.valueOf(r.get("id"));
                    if (!id.equals(dir.getFileName().toString()) || !id.matches("job-[0-9a-f]{10}")) continue;
                    Path project = Path.of(String.valueOf(r.get("project")));
                    if (!project.isAbsolute()) continue;
                    int steps = r.get("steps") instanceof Number n ? Math.max(0, Math.min(16, n.intValue())) : 0;
                    List<List<String>> chain = new ArrayList<>();
                    for (int i = 0; i < steps; i++) chain.add(List.of());
                    String runId = r.get("run_id") instanceof String text && RunTimeline.RUN_ID.matcher(text).matches() ? text : null;
                    Job job = new Job(id, String.valueOf(r.get("kind")), String.valueOf(r.get("label")), project, dir,
                            chain, runId, Instant.parse(String.valueOf(r.get("started_at"))));
                    job.step = r.get("step") instanceof Number n ? Math.max(0, Math.min(steps, n.intValue())) : 0;
                    job.exit = r.get("exit_code") instanceof Number n ? n.intValue() : null;
                    job.ended = r.get("ended_at") instanceof String text ? Instant.parse(text) : null;
                    job.note = r.get("note") instanceof String text ? text : null;
                    String state = String.valueOf(r.get("state"));
                    if ("running".equals(state)) {
                        state = "stopped";
                        job.note = "the dashboard restarted while this job ran; the run's own state is in its timeline";
                        if (job.ended == null) job.ended = Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis());
                    }
                    job.state = Set.of("finished", "failed", "stopped", "cancelled").contains(state) ? state : "stopped";
                    if (steps > 0) job.result = lastJson(dir.resolve("stdout-" + Math.min(job.step, steps - 1) + ".json"));
                    found.add(job);
                } catch (Exception unreadable) {
                    // A damaged record hides that one job; it never stops the dashboard.
                }
            }
        } catch (IOException ignored) {
            return;
        }
        found.sort(java.util.Comparator.comparing(job -> job.started));
        for (Job job : found.subList(Math.max(0, found.size() - MAX_JOBS), found.size())) jobs.put(job.id, job);
    }

    // ---------------------------------------------------------------- inspect

    /** What the operator is about to point Overseer at: it must be a git repository's top. */
    public Map<String, Object> inspect(Map<String, Object> payload) throws Refused {
        Path project = project(payload.get("project"));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ok", true);
        view.put("project", project.toString());
        view.put("branch", git(project, "rev-parse", "--abbrev-ref", "HEAD"));
        view.put("head", git(project, "rev-parse", "--short", "HEAD"));
        String status = git(project, "status", "--porcelain", "--untracked-files=no");
        view.put("dirty_files", status == null || status.isBlank() ? 0 : status.split("\n").length);
        view.put("project_config", Files.isRegularFile(project.resolve(".oseer/project.yaml")));
        view.put("tasks", tasks(project));
        view.put("busy", busy(project) != null);
        view.put("default", project.equals(defaultProject));
        return view;
    }

    static List<String> tasks(Path project) {
        Path dir = project.resolve(".oseer/tasks");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml"))
                    .map(n -> n.substring(0, n.length() - 5)).filter(n -> NAME.matcher(n).matches()).sorted().toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    Path project(Object raw) throws Refused {
        String text = raw instanceof String s ? s.trim() : "";
        if (text.isEmpty() && defaultProject != null) text = defaultProject.toString();
        if (text.isEmpty() || text.length() > 1000 || text.contains("\0")) throw new Refused(400, "project_invalid", "name the project directory");
        Path path = Path.of(text);
        if (!path.isAbsolute()) throw new Refused(400, "project_not_absolute", "the project directory must be an absolute path");
        if (!Files.isDirectory(path)) throw new Refused(400, "project_missing", "no such directory: " + path);
        Path real;
        try { real = path.toRealPath(); } catch (IOException e) { throw new Refused(400, "project_missing", "no such directory: " + path); }
        String top = git(real, "rev-parse", "--show-toplevel");
        if (top == null) throw new Refused(400, "project_not_git", "not a git repository: " + real);
        try {
            if (!Path.of(top).toRealPath().equals(real)) {
                // The message is only the top folder: the page already says what is wrong, and a
                // path inside .oseer gets its own code because that folder is Overseer's, not the project.
                boolean internal = false;
                for (Path part : Path.of(top).toRealPath().relativize(real)) if (".oseer".equals(part.toString())) internal = true;
                throw new Refused(400, internal ? "project_inside_oseer" : "project_not_repo_root", top);
            }
        } catch (IOException e) { throw new Refused(400, "project_not_git", "not a git repository: " + real); }
        return real;
    }

    // ---------------------------------------------------------------- preview

    /** Builds the argument vector, runs it with {@code --dry-run}, and keeps the plan to start. */
    public Map<String, Object> preview(Map<String, Object> payload) throws Refused {
        String kind = string(payload.get("kind"), "do");
        Map<String, Object> params = payload.get("params") instanceof Map<?, ?> m ? cast(m) : Map.of();
        Path project = project(params.get("project"));
        String runId = newRunId();
        Path goalFile = null;
        List<String> argv = new ArrayList<>();
        String label;
        if ("do".equals(kind)) {
            String goal = string(params.get("goal"), "").strip();
            if (goal.isEmpty() || goal.length() > 8000) throw new Refused(400, "goal_invalid", "describe the goal (up to 8000 characters)");
            String taskId = optional(params, "task_id", NAME, "task id");
            String scope = optional(params, "scope", SCOPE, "scope");
            String risk = choice(params, "risk", RISKS);
            String mode = choice(params, "mode", dev.oseer.config.TaskSpec.MODES);
            String prepare = choice(params, "prepare", PREPARE);
            goalFile = scratch().resolve("goal-" + runId + ".txt");
            write(goalFile, goal);
            argv.addAll(List.of("do", "--goal-file", goalFile.toString(), "--project", project.toString()));
            if (taskId != null) argv.addAll(List.of("--task-id", taskId));
            if (scope != null) argv.addAll(List.of("--scope", scope));
            if (risk != null) argv.addAll(List.of("--risk", risk));
            if (mode != null) argv.addAll(List.of("--mode", mode));
            if (prepare != null) argv.addAll(List.of("--prepare", prepare));
            Long calls = number(params, "max_role_runs", 1, 50);
            if (calls != null) argv.addAll(List.of("--max-role-runs", Long.toString(calls)));
            Double cost = decimal(params, "max_cost_usd", 0, 10000);
            if (cost != null) argv.addAll(List.of("--max-cost-usd", Double.toString(cost)));
            if (flag(params, "draft_only")) argv.add("--draft-only");
            if (flag(params, "in_place")) argv.add("--in-place");
            argv.add("--no-wait-for-gate");
            argv.add("--no-decision-page");
            label = "do · " + (goal.length() > 80 ? goal.substring(0, 80) + "…" : goal);
        } else if ("run".equals(kind)) {
            String task = optional(params, "task", NAME, "task");
            if (task == null || !tasks(project).contains(task)) throw new Refused(400, "task_unknown", "pick a task contract from .oseer/tasks");
            argv.addAll(List.of("run", task));
            String from = optional(params, "continue", RunTimeline.RUN_ID, "run to continue");
            if (from != null) argv.addAll(List.of("--continue", from));
            String prepare = choice(params, "prepare", PREPARE);
            if (prepare != null) argv.addAll(List.of("--prepare", prepare));
            argv.add("--no-decision-page");
            label = "run · " + task + (from == null ? "" : " ← " + from);
        } else {
            throw new Refused(400, "kind_invalid", "kind must be do or run");
        }
        argv.addAll(overrides(params));
        if (flag(params, "no_orca_gate")) argv.add("--no-orca-gate");
        argv.addAll(List.of("--run-id", runId));
        List<String> dry = new ArrayList<>(argv);
        dry.add("--dry-run");
        dry.set(dry.indexOf("--run-id") + 1, runId + "-plan");
        Outcome outcome = execute(project, dry, PREVIEW_TIMEOUT);
        deleteDryRun(project, runId + "-plan");
        Preview preview = new Preview(token(), project, List.copyOf(argv), fingerprint(project, argv), Instant.now(),
                kind, runId, goalFile, label);
        synchronized (this) {
            previews.values().removeIf(p -> p.created.plus(PREVIEW_TTL).isBefore(Instant.now()));
            while (previews.size() > 20) previews.remove(previews.keySet().iterator().next());
            previews.put(preview.id, preview);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ok", true);
        view.put("preview_id", preview.id);
        view.put("kind", kind);
        view.put("project", project.toString());
        view.put("run_id", runId);
        view.put("command", display(argv));
        view.put("plan", redact(outcome.narration));
        view.put("dry_run_exit", outcome.exit);
        if (outcome.result != null) {
            for (String key : List.of("reason", "next_action", "decision_reason")) {
                if (outcome.result.get(key) != null) view.put("dry_run_" + key, String.valueOf(outcome.result.get(key)));
            }
        }
        view.put("expires_at", preview.created.plus(PREVIEW_TTL).toString());
        view.put("busy", busy(project) != null);
        return view;
    }

    /** {@code --use stage=profile} and {@code --effort stage=level}, one per stage. */
    static List<String> overrides(Map<String, Object> params) throws Refused {
        List<String> argv = new ArrayList<>();
        for (String[] spec : new String[][]{{"use", "--use"}, {"effort", "--effort"}}) {
            Object raw = params.get(spec[0]);
            if (raw == null) continue;
            if (!(raw instanceof Map<?, ?> map) || map.size() > 12) throw new Refused(400, spec[0] + "_invalid", spec[0] + " must map stages to values");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String stage = String.valueOf(entry.getKey()), value = entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim();
                if (value.isEmpty()) continue;
                Pattern allowed = "use".equals(spec[0]) ? NAME : EFFORT;
                if (!STAGE.matcher(stage).matches() || !allowed.matcher(value).matches()) {
                    throw new Refused(400, spec[0] + "_invalid", spec[0] + " " + stage + "=" + value + " is not a stage and a name");
                }
                argv.addAll(List.of(spec[1], stage + "=" + value));
            }
        }
        return argv;
    }

    // ---------------------------------------------------------------- start / answer / land

    /** Starts what a preview showed, once the operator confirmed it, and only if nothing moved. */
    public Map<String, Object> start(Map<String, Object> payload) throws Refused {
        if (!Boolean.TRUE.equals(payload.get("confirm"))) throw new Refused(400, "confirmation_required", "confirm the plan before paid calls start");
        String id = string(payload.get("preview_id"), "");
        Preview preview;
        synchronized (this) { preview = previews.remove(id); }
        if (preview == null) throw new Refused(409, "preview_unknown", "preview again; this plan is unknown or was already started");
        if (preview.created.plus(PREVIEW_TTL).isBefore(Instant.now())) throw new Refused(409, "preview_stale", "the preview expired; preview again");
        if (!fingerprint(preview.project, preview.argv).equals(preview.fingerprint)) {
            throw new Refused(409, "preview_stale", "the project, the policy or the task changed since the preview; preview again");
        }
        return launch(preview.kind, preview.label, preview.project, List.of(preview.argv), preview.runId).view();
    }

    /**
     * Answers a pending decision as the decision page does, then, for a continuing answer,
     * starts the continuation in the same job. A vendor override is a {@code --use} on the
     * continuation, which is how "pick another vendor" reaches the engine.
     */
    public Map<String, Object> answer(Map<String, Object> payload) throws Refused {
        Path project = project(payload.get("project"));
        String runId = optional(payload, "run_id", RunTimeline.RUN_ID, "run");
        if (runId == null) throw new Refused(400, "run_invalid", "name the run");
        HumanDecision pending = pending(project, runId);
        if (pending == null) throw new Refused(409, "decision_not_pending", "this run has no pending decision");
        String decision = string(payload.get("decision"), "");
        if (!pending.options().contains(decision)) throw new Refused(400, "decision_invalid", "choose one of " + pending.options());
        String note = string(payload.get("note"), "").strip();
        if (note.length() > 2000) throw new Refused(400, "note_invalid", "the note is limited to 2000 characters");
        boolean continues = CONTINUING.contains(decision);
        if (continues && !Boolean.TRUE.equals(payload.get("confirm"))) {
            throw new Refused(400, "confirmation_required", "confirm the continuation; it can pay vendors");
        }
        List<String> approve = new ArrayList<>(List.of("approve", runId, "--decision", decision,
                "--actor", "dashboard-launcher", "--expected-updated-at", pending.updatedAt().toString(), "--no-start"));
        if (!note.isEmpty()) approve.addAll(List.of("--note", note));
        String finding = string(payload.get("finding"), "").strip();
        if (!finding.isEmpty()) {
            if (!NAME.matcher(finding).matches()) throw new Refused(400, "finding_invalid", "a finding id is a short name");
            approve.addAll(List.of("--finding", finding));
        }
        List<List<String>> chain = new ArrayList<>();
        chain.add(approve);
        String next = null;
        if (continues) {
            Map<String, Object> params = payload.get("params") instanceof Map<?, ?> m ? cast(m) : Map.of();
            next = newRunId();
            // Filled after the approve step reports the engine's own continuation command.
            List<String> run = new ArrayList<>(List.of("@continue", pending.taskId(), runId, next));
            run.addAll(overrides(params));
            chain.add(run);
        }
        return launch("answer", decision + " · " + runId, project, chain, next == null ? runId : next).view();
    }

    /** Lands a run: the plan, or a commit; a push or pull request needs a second confirmation. */
    public Map<String, Object> land(Map<String, Object> payload) throws Refused {
        Path project = project(payload.get("project"));
        String runId = optional(payload, "run_id", RunTimeline.RUN_ID, "run");
        if (runId == null) throw new Refused(400, "run_invalid", "name the run");
        List<String> argv = new ArrayList<>(List.of("land", runId));
        if (flag(payload, "commit")) argv.add("--commit");
        boolean remote = flag(payload, "push") || flag(payload, "pull_request");
        if (remote && !Boolean.TRUE.equals(payload.get("confirm_remote"))) {
            throw new Refused(400, "confirmation_required", "confirm the push; it leaves this machine");
        }
        if (flag(payload, "push")) argv.add("--push");
        if (flag(payload, "pull_request")) argv.add("--pull-request");
        return launch("land", "land · " + runId, project, List.of(argv), runId).view();
    }

    /**
     * Shows a run this server did not start, such as one a terminal or a previous dashboard
     * started, so its decision can be answered and its vendor switched from here. Runs nothing.
     */
    public Map<String, Object> attach(Map<String, Object> payload) throws Refused {
        Path project = project(payload.get("project"));
        String runId = optional(payload, "run_id", RunTimeline.RUN_ID, "run");
        if (runId == null || runId.contains("--") || !Files.isDirectory(project.resolve(".oseer/runs").resolve(runId))) {
            throw new Refused(404, "run_unknown", "no such run in " + project);
        }
        Job job;
        synchronized (this) {
            String id = "job-" + token().substring(0, 10);
            job = new Job(id, "attach", "attach · " + runId, project, home.resolve("ui-jobs").resolve(id), List.of(), runId);
            job.state = pending(project, runId) != null ? "stopped" : "finished";
            job.ended = job.started;
            jobs.put(id, job);
        }
        persist(job);
        return job.view();
    }

    public Map<String, Object> cancel(Map<String, Object> payload) throws Refused {
        Job job;
        synchronized (this) { job = jobs.get(string(payload.get("job"), "")); }
        if (job == null) throw new Refused(404, "job_unknown", "no such job");
        if (!job.live()) return job.view();
        job.cancelled = true;
        Process process = job.process;
        if (process != null) {
            // SIGTERM first: the engine's interruption guard records the cancel and releases
            // its lease. Children (the vendor CLIs) follow; a process that ignores it is killed.
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            Thread.ofVirtual().start(() -> {
                try {
                    if (!process.waitFor(CANCEL_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        return job.view();
    }

    // ---------------------------------------------------------------- views

    public synchronized List<Map<String, Object>> jobs() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Job job : jobs.values()) rows.add(0, job.summary());
        return rows;
    }

    public Map<String, Object> job(String id) throws Refused {
        Job job;
        synchronized (this) { job = jobs.get(id); }
        if (job == null) throw new Refused(404, "job_unknown", "no such job");
        return job.view();
    }

    /** The project a job ran in, so the run timeline can be read for it. */
    public synchronized Path projectOf(String jobId) {
        Job job = jobs.get(jobId);
        return job == null ? null : job.project;
    }

    // ---------------------------------------------------------------- internals

    private Job launch(String kind, String label, Path project, List<List<String>> chain, String runId) throws Refused {
        Job job;
        synchronized (this) {
            Job other = busy(project);
            if (other != null) throw new Refused(409, "project_busy", "job " + other.id + " is still running in this project");
            String id = "job-" + token().substring(0, 10);
            Path dir = home.resolve("ui-jobs").resolve(id);
            try { Files.createDirectories(dir); } catch (IOException e) { throw new Refused(503, "job_dir_unavailable", "cannot create " + dir); }
            job = new Job(id, kind, label, project, dir, chain, runId);
            jobs.put(id, job);
            while (jobs.size() > MAX_JOBS) {
                String oldest = jobs.keySet().stream().filter(k -> !jobs.get(k).live()).findFirst().orElse(null);
                if (oldest == null) break;
                jobs.remove(oldest);
            }
        }
        persist(job);
        Job running = job;
        Thread.ofPlatform().daemon(true).name("oseer-ui-" + job.id).start(() -> drive(running));
        return job;
    }

    private void drive(Job job) {
        try {
            for (job.step = 0; job.step < job.chain.size() && !job.cancelled; job.step++) {
                List<String> argv = job.chain.get(job.step);
                if (!argv.isEmpty() && "@continue".equals(argv.get(0))) {
                    argv = continuation(job, argv);
                    if (argv == null) break;
                }
                // One pair of files per step, each truncated on open: the same redirection the
                // preview uses, which behaves alike on Windows and POSIX.
                Path out = job.dir.resolve("stdout-" + job.step + ".json");
                ProcessBuilder builder = new ProcessBuilder(concat(command, argv)).directory(job.project.toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(job.dir.resolve("narration-" + job.step + ".log").toFile());
                builder.environment().put("OSEER_CONFIG_HOME", home.toString());
                Process process = builder.start();
                job.process = process;
                persist(job);
                int code = process.waitFor();
                job.exit = code;
                job.result = lastJson(out);
                if (code != 0 && !(job.step < job.chain.size() - 1 && job.result != null && Boolean.TRUE.equals(job.result.get("ok")))) break;
            }
            // A run that stopped for a person exits non-zero but answers with its own JSON;
            // that is a stop to answer, not a crash.
            job.state = job.cancelled ? "cancelled" : job.exit != null && job.exit == 0 ? "finished"
                    : job.result != null && job.result.get("ok") instanceof Boolean ? "stopped" : "failed";
        } catch (IOException | InterruptedException failure) {
            job.state = "failed";
            // The message names the executable or file that could not be opened; never output.
            job.note = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            job.ended = Instant.now();
            job.process = null;
            try { viewOf(job); } catch (RuntimeException ignored) { } // learns the run id from the narration
            persist(job);
        }
    }

    /** The command the engine itself printed for this continuation, or {@code run --continue}. */
    private List<String> continuation(Job job, List<String> spec) {
        Map<String, Object> approved = job.result;
        if (approved == null || !Boolean.TRUE.equals(approved.get("ok"))) return null;
        if (Boolean.TRUE.equals(approved.get("waiter"))) {
            job.note = "a process already waiting on this decision starts the next run";
            return null;
        }
        String task = spec.get(1), from = spec.get(2), next = spec.get(3);
        List<String> argv = new ArrayList<>(List.of("run", task, "--continue", from, "--run-id", next));
        if (approved.get("next_run") instanceof Map<?, ?> started) {
            Object prepare = started.get("prepare");
            Object engineId = started.get("run_id");
            if (engineId instanceof String id && RunTimeline.RUN_ID.matcher(id).matches()) {
                argv.set(5, id);
                job.runId = id;
            }
            if (prepare instanceof String p && PREPARE.contains(p)) argv.addAll(List.of("--prepare", p));
        }
        argv.add("--no-decision-page");
        argv.addAll(spec.subList(4, spec.size()));
        return argv;
    }

    record Outcome(int exit, List<String> narration, Map<String, Object> result) {}

    private Outcome execute(Path project, List<String> argv, Duration timeout) throws Refused {
        Path out, err;
        try {
            out = Files.createTempFile(scratch(), "preview-", ".json");
            err = Files.createTempFile(scratch(), "preview-", ".log");
        } catch (IOException e) {
            throw new Refused(503, "preview_unavailable", "cannot write the preview");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(concat(command, argv)).directory(project.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile());
            builder.environment().put("OSEER_CONFIG_HOME", home.toString());
            Process process = builder.start();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new Refused(504, "preview_timeout", "the dry run did not finish in " + timeout.toMinutes() + " minutes");
            }
            return new Outcome(process.exitValue(), tail(err, 400), lastJson(out));
        } catch (IOException e) {
            throw new Refused(503, "preview_unavailable", "the dry run could not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refused(503, "preview_unavailable", "interrupted");
        } finally {
            try { Files.deleteIfExists(out); Files.deleteIfExists(err); } catch (IOException ignored) { }
        }
    }

    /** The dry run's own directory is scratch the operator did not ask for. */
    static void deleteDryRun(Path project, String runId) {
        Path runs = project.resolve(".oseer/runs");
        if (!runId.endsWith("-plan") || !RunTimeline.RUN_ID.matcher(runId).matches() || !Files.isDirectory(runs)) return;
        List<Path> dirs;
        try (Stream<Path> list = Files.list(runs)) {
            // The run directory and its step directories, which sit beside it as RUN--STEP.
            dirs = list.filter(p -> p.getFileName().toString().equals(runId)
                    || p.getFileName().toString().startsWith(runId + "--")).toList();
        } catch (IOException unreadable) {
            return;
        }
        for (Path dir : dirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    static HumanDecision pending(Path project, String runId) {
        try {
            HumanDecision decision = new ApprovalStore(project).read(runId);
            return decision != null && decision.state() == HumanDecision.State.PENDING ? decision : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** HEAD, the arguments, and every configuration file the run reads. */
    String fingerprint(Path project, List<String> argv) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.join("\0", argv).getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(git(project, "rev-parse", "HEAD")).getBytes(StandardCharsets.UTF_8));
            List<Path> files = new ArrayList<>();
            for (Path root : List.of(home, project.resolve(".oseer"))) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root, 2)) {
                    walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".yaml")).sorted().forEach(files::add);
                }
            }
            for (Path file : files) {
                digest.update(file.toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception unreadable) {
            return "unreadable-" + System.nanoTime();
        }
    }

    private Job busy(Path project) {
        synchronized (this) {
            for (Job job : jobs.values()) if (job.live() && job.project.equals(project)) return job;
        }
        return null;
    }

    static String git(Path dir, String... args) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] out = process.getInputStream().readNBytes(1 << 20);
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            return new String(out, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static Map<String, Object> lastJson(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) return null;
            int start = text.lastIndexOf("\n{");
            return Json.parseObject(start < 0 ? text : text.substring(start + 1));
        } catch (Exception unreadable) {
            return null;
        }
    }

    static List<String> tail(Path file, int lines) {
        try {
            if (!Files.isRegularFile(file)) return List.of();
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return List.copyOf(all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** Narration never carries a key, but a vendor's quoted error could; it is masked. */
    static List<String> redact(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String masked = SECRETISH.matcher(RunTimeline.redact(line)).replaceAll("[redacted]");
            out.add(masked.length() > 600 ? masked.substring(0, 600) + "…" : masked);
        }
        return out;
    }

    static List<String> display(List<String> argv) {
        List<String> shown = new ArrayList<>(List.of("oseer"));
        for (String arg : argv) shown.add(arg.matches("[A-Za-z0-9_./=:@,+-]+") ? arg : "'" + arg.replace("'", "'\\''") + "'");
        return List.of(String.join(" ", shown));
    }

    private Path scratch() throws Refused {
        Path dir = home.resolve("ui-jobs").resolve("previews");
        try { Files.createDirectories(dir); } catch (IOException e) { throw new Refused(503, "job_dir_unavailable", "cannot create " + dir); }
        return dir;
    }

    private static void write(Path file, String text) throws Refused {
        try { Files.writeString(file, text + "\n", StandardCharsets.UTF_8); }
        catch (IOException e) { throw new Refused(503, "job_dir_unavailable", "cannot write the goal file"); }
    }

    private String token() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    String newRunId() {
        return "run-" + Long.toString(System.currentTimeMillis(), 36) + "-" + token().substring(0, 8);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) { return (Map<String, Object>) map; }

    private static String string(Object value, String fallback) { return value instanceof String s ? s : fallback; }

    private static boolean flag(Map<String, Object> params, String key) { return Boolean.TRUE.equals(params.get(key)); }

    private static String optional(Map<String, Object> params, String key, Pattern pattern, String what) throws Refused {
        Object raw = params.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) return null;
        if (!(raw instanceof String s) || !pattern.matcher(s.trim()).matches()) throw new Refused(400, key + "_invalid", what + " is not valid");
        return s.trim();
    }

    private static String choice(Map<String, Object> params, String key, Set<String> allowed) throws Refused {
        Object raw = params.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) return null;
        if (!(raw instanceof String s) || !allowed.contains(s)) throw new Refused(400, key + "_invalid", key + " must be one of " + new java.util.TreeSet<>(allowed));
        return s;
    }

    private static Long number(Map<String, Object> params, String key, long min, long max) throws Refused {
        Object raw = params.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) return null;
        try {
            double value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw));
            if (value != Math.rint(value) || value < min || value > max) throw new NumberFormatException();
            return (long) value;
        } catch (NumberFormatException e) {
            throw new Refused(400, key + "_invalid", key + " must be a whole number " + min + ".." + max);
        }
    }

    private static Double decimal(Map<String, Object> params, String key, double min, double max) throws Refused {
        Object raw = params.get(key);
        if (raw == null || (raw instanceof String s && s.isBlank())) return null;
        try {
            double value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw));
            if (Double.isNaN(value) || value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new Refused(400, key + "_invalid", key + " must be a number " + min + ".." + max);
        }
    }

    /** Cancels whatever still runs when the dashboard stops; the engine records the cancel. */
    @Override
    public void close() {
        List<Job> live;
        synchronized (this) { live = jobs.values().stream().filter(Job::live).toList(); }
        for (Job job : live) {
            try { cancel(Map.of("job", job.id)); } catch (Refused ignored) { }
        }
    }

    private static Object elapsed(Job job) {
        Instant end = job.ended == null ? Instant.now() : job.ended;
        return Duration.between(job.started, end).toMillis();
    }

    static String narratedRun(List<String> lines) {
        String found = null;
        for (String line : lines) {
            Matcher m = NARRATED_RUN.matcher(line);
            if (m.find() && RunTimeline.RUN_ID.matcher(m.group(1)).matches()) found = m.group(1);
        }
        return found;
    }

    /** The list row reads no files; the full view adds the narration tail and the decision. */
    static Map<String, Object> summaryOf(Job job) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", job.id);
        row.put("kind", job.kind);
        row.put("label", job.label);
        row.put("project", job.project.toString());
        row.put("state", job.state);
        row.put("run_id", job.runId);
        row.put("exit_code", job.exit);
        row.put("started_at", job.started.toString());
        row.put("ended_at", job.ended == null ? null : job.ended.toString());
        row.put("elapsed_ms", elapsed(job));
        return row;
    }

    static Map<String, Object> viewOf(Job job) {
        Map<String, Object> view = summaryOf(job);
        List<String> narration = new ArrayList<>();
        for (int step = 0; step < Math.max(1, job.chain.size()); step++) narration.addAll(tail(job.dir.resolve("narration-" + step + ".log"), 2000));
        String narrated = narratedRun(narration);
        if (narrated != null && !narrated.contains("--")) job.runId = narrated;
        view.put("run_id", job.runId);
        view.put("narration", redact(narration.subList(Math.max(0, narration.size() - TAIL_LINES), narration.size())));
        view.put("step", Math.min(job.step + 1, job.chain.size()));
        view.put("steps", job.chain.size());
        view.put("note", job.note);
        Map<String, Object> result = job.result;
        if (result != null) {
            Map<String, Object> shown = new LinkedHashMap<>();
            for (String key : List.of("ok", "code", "reason", "next_action", "message", "next", "run_id")) {
                Object value = result.get(key);
                if (value instanceof Boolean flag) shown.put(key, flag);
                else if (value != null) shown.put(key, redact(List.of(String.valueOf(value))).get(0));
            }
            view.put("result", shown);
        }
        HumanDecision pending = job.runId == null || job.live() ? null : pending(job.project, job.runId);
        if (pending != null) {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("kind", pending.kind().name().toLowerCase(java.util.Locale.ROOT));
            decision.put("reason", redact(List.of(String.valueOf(pending.reason()))).get(0));
            decision.put("options", pending.options());
            decision.put("task", pending.taskId());
            view.put("decision", decision);
        }
        return view;
    }
}
