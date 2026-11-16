package dev.oseer.dashboard;

import dev.oseer.json.Json;
import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starts {@code oseer do}, {@code run}, {@code approve} and {@code land} for the browser.
 *
 * <p>The browser never names a command line. It sends a form; this class turns the form into
 * the same argument vector a person would type, validated field by field. New runs start
 * from a preview: the same arguments run first with
 * {@code --dry-run}, the operator reads the cast, the call cap and the estimate, and the start
 * request names that preview. A preview is bound to the arguments, the project's HEAD and the
 * policy, profile and task files it read; when any of them changed the start is refused and
 * the operator previews again. A decision that resumes a run requires confirmation.
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
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,79}");
    static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9_./*-][A-Za-z0-9_./*, -]{0,399}");
    static final Pattern STAGE = Pattern.compile("[a-z][a-z0-9-]{0,40}");
    static final Pattern EFFORT = Pattern.compile("[a-z]{1,16}");
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

    record Preview(String id, Path project, Path worktree, List<String> argv, String fingerprint, Instant created,
                   String kind, String runId, Path goalFile, String label) {}
    record PreviewInputs(String fingerprint, Set<Path> draftFiles) {}

    static final class Job {
        final String id, kind, label; final Path project, dir; final Instant started;
        volatile Path worktree;
        Path inputFile;
        final List<List<String>> chain; int step; volatile Process process; volatile String runId;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String state = "running"; volatile Integer exit; volatile Instant ended;
        volatile boolean cancelled; volatile Map<String, Object> result; volatile String note;
        Job(String id, String kind, String label, Path project, Path dir, List<List<String>> chain, String runId) {
            this(id, kind, label, project, dir, chain, runId, Instant.now());
        }
        Job(String id, String kind, String label, Path project, Path dir, List<List<String>> chain, String runId, Instant started) {
            this.id = id; this.kind = kind; this.label = label; this.project = project; this.dir = dir;
            this.chain = chain; this.runId = runId; this.started = started; this.worktree = project;
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
    private boolean closed;

    /** The dashboard's own Java and class path run the engine; tests pass a stand-in. */
    public RunLauncher(Path home, Path defaultProject) {
        this(home, defaultProject, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", absoluteClasspath(), "dev.oseer.Main"));
    }

    private static String absoluteClasspath() {
        return String.join(File.pathSeparator, Stream.of(System.getProperty("java.class.path").split(
                        Pattern.quote(File.pathSeparator), -1))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).toList());
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
        record.put("worktree", job.worktree.toString());
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
                    Path worktree = reportedWorktree(r.get("worktree"));
                    if (worktree != null) job.worktree = worktree;
                    locateWorktree(job);
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
                    if (steps > 0) recordResult(job, lastJson(dir.resolve("stdout-" + Math.min(job.step, steps - 1) + ".json")));
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
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml"))
                    .map(n -> n.substring(0, n.length() - 5)).filter(n -> NAME.matcher(n).matches()).sorted().toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    Path project(Object raw) throws Refused {
        String text = raw instanceof String s ? s.trim() : "";
        if (text.isEmpty() && defaultProject != null) text = defaultProject.toString();
        if (text.isEmpty() || text.length() > 1000 || text.contains("\0")) throw new Refused(400, "project_invalid", "name the project directory");
        Path path;
        try { path = Path.of(text); }
        catch (InvalidPathException invalid) { throw new Refused(400, "project_invalid", "name a valid project directory"); }
        if (!path.isAbsolute()) throw new Refused(400, "project_not_absolute", "the project directory must be an absolute path");
        if (!Files.isDirectory(path)) throw new Refused(400, "project_missing", "no such directory: " + path);
        Path real;
        try { real = path.toRealPath(); } catch (IOException e) { throw new Refused(400, "project_missing", "no such directory: " + path); }
        String top = git(real, "rev-parse", "--show-toplevel");
        if (top == null) {
            // Inside .git (or another folder git will not treat as a work tree) rev-parse has no
            // top; the nearest parent that is a work tree still names the folder to pick.
            for (Path parent = real.getParent(); parent != null; parent = parent.getParent()) {
                if (!Files.exists(parent.resolve(".git"))) continue;
                String enclosing = git(parent, "rev-parse", "--show-toplevel");
                if (enclosing == null) break;
                try {
                    Path topPath = Path.of(enclosing).toRealPath();
                    if (real.startsWith(topPath)) throw new Refused(400, "project_not_repo_root", topPath.toString());
                } catch (IOException ignored) { }
                break;
            }
            throw new Refused(400, "project_not_git", "not a git repository: " + real);
        }
        try {
            Path topPath = Path.of(top).toRealPath();
            if (!topPath.equals(real)) {
                // The message is only the top folder: the page already says what is wrong, and a
                // path inside .oseer gets its own code because that folder is Overseer's, not the project.
                boolean internal = false;
                for (Path part : topPath.relativize(real)) if (".oseer".equals(part.toString())) internal = true;
                // The real path, not git's spelling: git prints forward slashes on Windows.
                throw new Refused(400, internal ? "project_inside_oseer" : "project_not_repo_root", topPath.toString());
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
        String draftedTask = null;
        if ("do".equals(kind)) {
            String goal = string(params.get("goal"), "").strip();
            if (goal.isEmpty() || goal.length() > 8000) throw new Refused(400, "goal_invalid", "describe the goal (up to 8000 characters)");
            String taskId = optional(params, "task_id", NAME, "task id");
            String scope = optional(params, "scope", SCOPE, "scope");
            String risk = choice(params, "risk", RISKS);
            String mode = choice(params, "mode", dev.oseer.config.TaskSpec.MODES);
            String prepare = choice(params, "prepare", PREPARE);
            goalFile = scratch().resolve("goal-" + runId + ".txt");
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
            draftedTask = taskId != null ? taskId : dev.oseer.config.TaskDraft.slug(goal);
            label = "do · " + draftedTask;
        } else if ("run".equals(kind)) {
            String task = optional(params, "task", NAME, "task");
            if (task == null || !tasks(project).contains(task)) throw new Refused(400, "task_unknown", "pick a task contract from .oseer/tasks");
            argv.addAll(List.of("run", project.resolve(".oseer/tasks").resolve(task + ".yaml").toString()));
            String from = optional(params, "continue", RunTimeline.RUN_ID, "run to continue");
            if (from != null) argv.addAll(List.of("--continue", from));
            label = "run · " + task + (from == null ? "" : " ← " + from);
        } else {
            throw new Refused(400, "kind_invalid", "kind must be do or run");
        }
        argv.addAll(overrides(params));
        if (flag(params, "no_orca_gate")) argv.add("--no-orca-gate");
        argv.addAll(List.of("--no-wait-for-gate", "--no-decision-page", "--run-id", runId));
        if (goalFile != null) write(goalFile, string(params.get("goal"), "").strip());
        List<String> dry = new ArrayList<>(argv);
        dry.add("--dry-run");
        dry.set(dry.indexOf("--run-id") + 1, runId + "-plan");
        Outcome outcome;
        Map<Path, PreviewInputs> before = previewInputs(project, argv, goalFile, draftedTask,
                draftedTask != null && !flag(params, "in_place"));
        try { outcome = execute(project, dry, PREVIEW_TIMEOUT); }
        catch (Refused failure) { delete(goalFile); throw failure; }
        Path worktree = outcome.worktree == null ? project : outcome.worktree;
        Map<String, Object> view = new LinkedHashMap<>();
        boolean succeeded = outcome.exit == 0 && outcome.result != null && Boolean.TRUE.equals(outcome.result.get("ok"));
        if (inputsChanged(before, project, project, argv, goalFile)
                || (!project.equals(worktree) && inputsChanged(before, project, worktree, argv, goalFile))) {
            delete(goalFile);
            throw new Refused(409, "preview_stale", "the project, the policy or the task changed since the preview; preview again");
        }
        view.put("ok", succeeded);
        if (succeeded) {
            Preview preview = new Preview(token(), project, worktree, List.copyOf(argv),
                    fingerprint(project, worktree, argv, goalFile, Set.of()), Instant.now(), kind, runId, goalFile, label);
            synchronized (this) {
                if (closed) {
                    delete(goalFile);
                    throw new Refused(503, "launcher_disabled", "the launcher is off");
                }
                previews.values().removeIf(p -> {
                    if (!p.created.plus(PREVIEW_TTL).isBefore(Instant.now())) return false;
                    delete(p.goalFile);
                    return true;
                });
                while (previews.size() >= 20) delete(previews.remove(previews.keySet().iterator().next()).goalFile);
                previews.put(preview.id, preview);
            }
            view.put("preview_id", preview.id);
            view.put("expires_at", preview.created.plus(PREVIEW_TTL).toString());
        } else {
            delete(goalFile);
        }
        view.put("kind", kind);
        view.put("project", project.toString());
        view.put("worktree", worktree.toString());
        view.put("run_id", runId);
        view.put("command", display(argv));
        view.put("plan", redact(outcome.narration));
        view.put("dry_run_exit", outcome.exit);
        if (outcome.result != null) {
            for (String key : List.of("code", "reason", "next_action", "decision_reason", "message")) {
                if (outcome.result.get(key) != null) view.put("dry_run_" + key,
                        redact(List.of(String.valueOf(outcome.result.get(key)))).get(0));
            }
        }
        view.put("busy", busy(project) != null || busy(worktree) != null);
        return view;
    }

    private Map<Path, PreviewInputs> previewInputs(Path project, List<String> argv, Path goal, String task,
                                                    boolean isolated) {
        Set<Path> candidates = new java.util.LinkedHashSet<>(List.of(project));
        if (isolated) {
            String listed = git(project, "worktree", "list", "--porcelain", "-z");
            if (listed != null) for (String field : listed.split("\0")) {
                if (!field.startsWith("worktree ")) continue;
                Path candidate = reportedWorktree(field.substring("worktree ".length()));
                if (candidate != null) candidates.add(candidate);
            }
        }
        Map<Path, PreviewInputs> inputs = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            Set<Path> draft = new java.util.LinkedHashSet<>();
            if (task != null) {
                for (Path root : List.of(project, candidate)) {
                    for (Path file : List.of(root.resolve(".oseer/project.yaml"),
                            root.resolve(".oseer/tasks").resolve(task + ".yaml"))) {
                        if (!Files.exists(file)) draft.add(file);
                    }
                }
            }
            inputs.put(candidate, new PreviewInputs(fingerprint(project, candidate, argv, goal, draft), draft));
        }
        return inputs;
    }

    private boolean inputsChanged(Map<Path, PreviewInputs> before, Path project, Path worktree, List<String> argv, Path goal) {
        PreviewInputs inputs = before.get(worktree);
        return inputs != null && !inputs.fingerprint.equals(fingerprint(project, worktree, argv, goal, inputs.draftFiles));
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
        try {
            if (preview.created.plus(PREVIEW_TTL).isBefore(Instant.now())) throw new Refused(409, "preview_stale", "the preview expired; preview again");
            if (!fingerprint(preview.project, preview.worktree, preview.argv, preview.goalFile, Set.of()).equals(preview.fingerprint)) {
                throw new Refused(409, "preview_stale", "the project, the policy or the task changed since the preview; preview again");
            }
            return launch(preview.kind, preview.label, preview.project, preview.worktree,
                    List.of(preview.argv), preview.runId, preview.goalFile).view();
        } catch (Refused failure) {
            delete(preview.goalFile);
            throw failure;
        }
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
        if (!pending.updatedAt().toString().equals(payload.get("expected_updated_at"))) {
            throw new Refused(409, "stale_decision", "the decision changed; reload it before answering");
        }
        String decision = string(payload.get("decision"), "");
        if (!pending.options().contains(decision)) throw new Refused(400, "decision_invalid", "choose one of " + pending.options());
        String note = string(payload.get("note"), "").strip();
        if (note.length() > 2000) throw new Refused(400, "note_invalid", "the note is limited to 2000 characters");
        boolean continues = CONTINUING.contains(decision) || (pending.kind() == HumanDecision.Kind.SUCCESS
                && "reject".equals(decision) && !note.isEmpty());
        if (continues && !Boolean.TRUE.equals(payload.get("confirm"))) {
            throw new Refused(400, "confirmation_required", "confirm the continuation; it can pay vendors");
        }
        List<String> approve = new ArrayList<>(List.of("approve", runId, "--decision", decision,
                "--actor", "dashboard-launcher", "--expected-updated-at", pending.updatedAt().toString()));
        if (Set.of("accept", "waive").contains(decision) && pending.expiredAt(Instant.now())) {
            if (!flag(payload, "acknowledge_expired")) {
                throw new Refused(409, "gate_expired", "the gate expired; confirm that its evidence is still current");
            }
            approve.add("--acknowledge-expired");
        }
        String finding = string(payload.get("finding"), "").trim();
        if ("waive".equals(decision) && finding.isEmpty()) {
            throw new Refused(400, "finding_invalid", "name the contract-gap finding to waive");
        }
        if (!finding.isEmpty()) {
            if (finding.length() > 80 || finding.indexOf('\0') != -1 || finding.startsWith("--")) {
                throw new Refused(400, "finding_invalid", "name a valid finding id of at most 80 characters");
            }
            approve.addAll(List.of("--finding", dev.oseer.process.ProcessRunner.argument(command.getFirst(), finding)));
        }
        List<List<String>> chain = new ArrayList<>();
        chain.add(approve);
        boolean startsInApprove = Set.of("advance", "apply", "replan", "proceed").contains(decision);
        if (!startsInApprove) approve.add("--no-start");
        if (continues) {
            Map<String, Object> params = payload.get("params") instanceof Map<?, ?> m ? cast(m) : Map.of();
            approve.add("--start-unless-waiting");
            List<String> overrides = overrides(params);
            if (!overrides.isEmpty()) {
                try (var supervision = DecisionPage.supervise(project, runId)) {
                    if (supervision == null) throw new Refused(409, "continuation_supervised",
                            "a process is already waiting; clear the stage overrides or stop that process before answering");
                } catch (IOException unavailable) {
                    throw new Refused(503, "continuation_unavailable", "cannot check who is supervising the decision");
                }
            }
            if (flag(params, "no_orca_gate")) overrides.add("--no-orca-gate");
            approve.addAll(overrides);
            if (!startsInApprove) {
                List<String> run = new ArrayList<>(List.of("@continue", pending.taskId(), runId, newRunId()));
                run.addAll(overrides);
                chain.add(run);
            }
        }
        Path noteFile = note.isEmpty() ? null : scratch().resolve("note-" + token() + ".txt");
        try {
            if (noteFile != null) {
                Files.writeString(noteFile, note, StandardCharsets.UTF_8);
                approve.addAll(List.of("--note-file", noteFile.toString()));
            }
            return launch("answer", decision + " · " + runId, project, project, chain, runId, noteFile).view();
        } catch (IOException failure) {
            delete(noteFile);
            throw new Refused(503, "job_dir_unavailable", "cannot write the decision note");
        } catch (Refused failure) {
            delete(noteFile);
            throw failure;
        }
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
        return launch("land", "land · " + runId, project, project, List.of(argv), runId, null).view();
    }

    /**
     * Shows a run this server did not start, such as one a terminal or a previous dashboard
     * started, so its decision can be answered and its vendor switched from here. Runs nothing.
     */
    public Map<String, Object> attach(Map<String, Object> payload) throws Refused {
        Path project = project(payload.get("project"));
        String runId = optional(payload, "run_id", RunTimeline.RUN_ID, "run");
        Path run = runId == null ? null : project.resolve(".oseer/runs").resolve(runId);
        if (run == null || (!Files.isRegularFile(run.resolve("run.json"))
                && !Files.isRegularFile(run.resolve("task-run.json")) && pending(project, runId) == null)) {
            throw new Refused(404, "run_unknown", "no such run in " + project);
        }
        Job job;
        synchronized (this) {
            if (closed) throw new Refused(503, "launcher_disabled", "the launcher is off");
            String id = "job-" + token().substring(0, 10);
            job = new Job(id, "attach", "attach · " + runId, project, home.resolve("ui-jobs").resolve(id), List.of(), runId);
            job.state = pending(project, runId) != null ? "stopped" : "finished";
            job.ended = job.started;
            remember(job);
        }
        persist(job);
        return job.view();
    }

    public Map<String, Object> cancel(Map<String, Object> payload) throws Refused {
        Job job;
        synchronized (this) { job = jobs.get(string(payload.get("job"), "")); }
        if (job == null) throw new Refused(404, "job_unknown", "no such job");
        requestCancel(job);
        return job.view();
    }

    private void requestCancel(Job job) {
        Process process;
        synchronized (job) {
            if (!job.live() || job.cancelled) return;
            job.cancelled = true;
            process = job.process;
        }
        if (process != null) {
            // SIGTERM first: the engine's interruption guard records the cancel and releases
            // its lease. Children (the vendor CLIs) follow; a process that ignores it is killed.
            List<ProcessHandle> descendants = process.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            process.destroy();
            Thread.ofVirtual().start(() -> {
                try {
                    if (!process.waitFor(CANCEL_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
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
        if (job == null) return null;
        locateWorktree(job);
        return job.worktree;
    }

    // ---------------------------------------------------------------- internals

    private Job launch(String kind, String label, Path project, Path worktree, List<List<String>> chain,
                       String runId, Path inputFile) throws Refused {
        Job job;
        synchronized (this) {
            if (closed) throw new Refused(503, "launcher_disabled", "the launcher is off");
            Job other = busy(project);
            if (other == null && !worktree.equals(project)) other = busy(worktree);
            if (other != null) throw new Refused(409, "project_busy", "job " + other.id + " is still running in this project");
            String id = "job-" + token().substring(0, 10);
            Path dir = home.resolve("ui-jobs").resolve(id);
            try { Files.createDirectories(dir); } catch (IOException e) { throw new Refused(503, "job_dir_unavailable", "cannot create " + dir); }
            job = new Job(id, kind, label, project, dir, chain, runId);
            job.worktree = worktree;
            job.inputFile = inputFile;
            remember(job);
        }
        persist(job);
        Job running = job;
        Thread.ofPlatform().daemon(true).name("oseer-ui-" + job.id).start(() -> drive(running));
        return job;
    }

    private void remember(Job job) {
        jobs.put(job.id, job);
        while (jobs.size() > MAX_JOBS) {
            String oldest = jobs.keySet().stream().filter(id -> !id.equals(job.id) && !jobs.get(id).live())
                    .findFirst().orElse(null);
            if (oldest == null) break;
            jobs.remove(oldest);
        }
    }

    private void drive(Job job) {
        String terminal = "failed";
        try {
            for (int step = 0; step < job.chain.size() && !job.cancelled; step++) {
                List<String> argv = job.chain.get(step);
                if (!argv.isEmpty() && "@continue".equals(argv.get(0))) {
                    argv = continuation(job, argv);
                    if (argv == null) break;
                }
                // One pair of files per step, each truncated on open: the same redirection the
                // preview uses, which behaves alike on Windows and POSIX.
                Path out = job.dir.resolve("stdout-" + step + ".json");
                ProcessBuilder builder = new ProcessBuilder(concat(command, argv)).directory(job.project.toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(job.dir.resolve("narration-" + step + ".log").toFile());
                builder.environment().put("OSEER_CONFIG_HOME", home.toString());
                builder.environment().put("OSEER_UI_WORKTREE_FILE", job.dir.resolve("worktree.txt").toString());
                if ("do".equals(job.kind)) {
                    builder.environment().put("OSEER_UI_EXPECTED_WORKTREE", job.worktree.toString());
                }
                Process process;
                synchronized (job) {
                    if (job.cancelled) break;
                    process = builder.start();
                    job.process = process;
                    job.step = step;
                }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                persist(job);
                int code = process.waitFor();
                job.exit = code;
                recordResult(job, lastJson(out));
                if (code != 0) break;
            }
            locateWorktree(job);
            if (job.runId != null && pending(job.worktree, job.runId) != null) terminal = "stopped";
            else if (job.exit != null && job.exit == 0
                    && (job.result == null || !Boolean.FALSE.equals(job.result.get("ok")))) terminal = "finished";
        } catch (IOException | InterruptedException failure) {
            // The message names the executable or file that could not be opened; never output.
            job.note = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            delete(job.inputFile);
            synchronized (job) {
                job.ended = Instant.now();
                job.process = null;
                job.state = job.cancelled ? "cancelled" : terminal;
                persist(job);
                job.done.countDown();
            }
        }
    }

    private static void recordResult(Job job, Map<String, Object> result) {
        job.result = result;
        if (result == null) return;
        if ("do".equals(job.kind)) {
            Path worktree = reportedWorktree(result.get("worktree"));
            if (worktree != null) job.worktree = worktree;
        }
        if (result.get("next_run") instanceof Map<?, ?> next) {
            if (Boolean.TRUE.equals(next.get("started"))) setRunId(job, next.get("run_id"));
            if (Boolean.FALSE.equals(next.get("ok"))) {
                Map<String, Object> failure = new LinkedHashMap<>();
                for (String key : List.of("ok", "code", "reason", "message", "next_action", "run_id")) {
                    if (next.containsKey(key)) failure.put(key, next.get(key));
                }
                job.result = failure;
            }
        } else {
            setRunId(job, result.get("run_id"));
        }
    }

    private static void setRunId(Job job, Object value) {
        if (value instanceof String id && RunTimeline.RUN_ID.matcher(id).matches()) job.runId = id;
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
        job.runId = next;
        List<String> argv = new ArrayList<>(List.of("run", job.project.resolve(".oseer/tasks").resolve(task + ".yaml").toString(),
                "--continue", from, "--run-id", next, "--no-wait-for-gate", "--no-decision-page"));
        argv.addAll(spec.subList(4, spec.size()));
        return argv;
    }

    record Outcome(int exit, List<String> narration, Map<String, Object> result, Path worktree) {}

    private Outcome execute(Path project, List<String> argv, Duration timeout) throws Refused {
        Path dir;
        try {
            dir = Files.createTempDirectory(scratch(), "preview-");
        } catch (IOException e) {
            throw new Refused(503, "preview_unavailable", "cannot write the preview");
        }
        Path out = dir.resolve("stdout.json"), err = dir.resolve("narration.log"), placement = dir.resolve("worktree.txt");
        Path worktree = null;
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(concat(command, argv)).directory(project.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile());
            builder.environment().put("OSEER_CONFIG_HOME", home.toString());
            builder.environment().put("OSEER_UI_WORKTREE_FILE", placement.toString());
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                throw new Refused(504, "preview_timeout", "the dry run did not finish in " + timeout.toMinutes() + " minutes");
            }
            Map<String, Object> result = lastJson(out);
            if (result != null) worktree = reportedWorktree(result.get("worktree"));
            if (worktree == null) worktree = readWorktree(placement);
            return new Outcome(process.exitValue(), tail(err, 400), result, worktree);
        } catch (IOException e) {
            throw new Refused(503, "preview_unavailable", "the dry run could not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refused(503, "preview_unavailable", "interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (worktree == null) worktree = readWorktree(placement);
            deleteDryRun(worktree == null ? project : worktree, argv.get(argv.indexOf("--run-id") + 1));
            delete(out);
            delete(err);
            delete(placement);
            delete(dir);
        }
    }

    private static void delete(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }

    private static Path reportedWorktree(Object value) {
        if (!(value instanceof String raw) || raw.isBlank()) return null;
        try {
            Path path = Path.of(raw.strip());
            if (path.isAbsolute()) return path.toRealPath();
        } catch (InvalidPathException | IOException ignored) { }
        return null;
    }

    private static Path readWorktree(Path placement) {
        try {
            if (Files.isRegularFile(placement) && Files.size(placement) <= 8192) {
                return reportedWorktree(Files.readString(placement, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
        return null;
    }

    private static void locateWorktree(Job job) {
        if (!"do".equals(job.kind)) return;
        Path root = readWorktree(job.dir.resolve("worktree.txt"));
        if (root != null) job.worktree = root;
    }

    /** The dry run's own directory is scratch the operator did not ask for. */
    static void deleteDryRun(Path project, String runId) {
        Path runs = project.resolve(".oseer/runs");
        if (!runId.endsWith("-plan") || !RunTimeline.RUN_ID.matcher(runId).matches() || !Files.isDirectory(runs)) return;
        List<Path> dirs;
        try {
            if (!runs.toRealPath().startsWith(project.toRealPath())) return;
        } catch (IOException unreadable) {
            return;
        }
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
    String fingerprint(Path project, Path worktree, List<String> argv, Path goal, Set<Path> ignored) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.join("\0", argv).getBytes(StandardCharsets.UTF_8));
            List<Path> files = new ArrayList<>();
            List<Path> roots = project.equals(worktree) ? List.of(project) : List.of(project, worktree);
            if (goal != null) files.add(goal);
            for (Path root : roots) {
                digest.update(String.valueOf(git(root, "rev-parse", "HEAD")).getBytes(StandardCharsets.UTF_8));
                Path rotation = root.resolve(".oseer/runs/rotation.json");
                if (Files.isRegularFile(rotation)) files.add(rotation);
                int from = argv.indexOf("--continue");
                if (from != -1) {
                    Path prior = root.resolve(".oseer/runs").resolve(argv.get(from + 1));
                    for (String name : List.of("decision.json", "task-run.json")) {
                        if (Files.isRegularFile(prior.resolve(name))) files.add(prior.resolve(name));
                    }
                }
            }
            List<Path> configRoots = new ArrayList<>(List.of(home));
            for (Path root : roots) configRoots.add(root.resolve(".oseer"));
            for (Path root : configRoots) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root, 2)) {
                    walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".yaml") && !ignored.contains(p))
                            .sorted().forEach(files::add);
                }
            }
            for (Path file : files) {
                digest.update(file.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (var input = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception unreadable) {
            return "unreadable-" + System.nanoTime();
        }
    }

    private Job busy(Path project) {
        synchronized (this) {
            for (Job job : jobs.values()) {
                if (!job.live()) continue;
                locateWorktree(job);
                if (job.project.equals(project) || job.worktree.equals(project)) return job;
            }
        }
        return null;
    }

    static String git(Path dir, String... args) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        try {
            var result = new dev.oseer.process.ProcessRunner().run(argv, dir, Duration.ofSeconds(20));
            return result.ok() ? result.stdout().strip() : null;
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
            ArrayDeque<String> recent = new ArrayDeque<>(lines);
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (recent.size() == lines) recent.removeFirst();
                    recent.addLast(line);
                }
            }
            return List.copyOf(recent);
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** Narration never carries a key, but a vendor's quoted error could; it is masked. */
    static List<String> redact(List<String> lines) {
        if (lines.isEmpty()) return List.of();
        String masked = RunTimeline.redact(String.join("\n", lines));
        return Stream.of(masked.split("\n", -1))
                .map(line -> SECRETISH.matcher(line).replaceAll("[redacted]"))
                .map(line -> line.length() > 600 ? line.substring(0, 600) + "…" : line).toList();
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
        synchronized (this) {
            if (closed) return;
            closed = true;
            live = jobs.values().stream().filter(Job::live).toList();
            previews.values().forEach(p -> delete(p.goalFile));
            previews.clear();
        }
        live.forEach(this::requestCancel);
        long deadline = System.nanoTime() + CANCEL_GRACE.toNanos();
        try {
            for (Job job : live) {
                if (!job.done.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) break;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            for (Job job : live) {
                Process process = job.process;
                if (process == null || !process.isAlive()) continue;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static Object elapsed(Job job) {
        Instant end = job.ended == null ? Instant.now() : job.ended;
        return Duration.between(job.started, end).toMillis();
    }

    /**
     * The list row reads no step files; the full view adds the narration tail and the decision.
     * A stopped job with a run says whether a person is awaited, for the tab badge.
     */
    static Map<String, Object> summaryOf(Job job) {
        locateWorktree(job);
        Path worktree = job.worktree;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", job.id);
        row.put("kind", job.kind);
        row.put("label", job.label);
        row.put("project", worktree.toString());
        row.put("state", job.state);
        row.put("run_id", job.runId);
        row.put("exit_code", job.exit);
        row.put("started_at", job.started.toString());
        row.put("ended_at", job.ended == null ? null : job.ended.toString());
        row.put("elapsed_ms", elapsed(job));
        row.put("waiting", !job.live() && job.runId != null && pending(worktree, job.runId) != null);
        return row;
    }

    static Map<String, Object> viewOf(Job job) {
        Map<String, Object> view = summaryOf(job);
        List<String> narration = new ArrayList<>();
        for (int step = 0; step < Math.max(1, job.chain.size()); step++) narration.addAll(tail(job.dir.resolve("narration-" + step + ".log"), 2000));
        List<String> masked = redact(narration);
        view.put("narration", masked.subList(Math.max(0, masked.size() - TAIL_LINES), masked.size()));
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
        HumanDecision pending = job.runId == null || job.live() ? null : pending(job.worktree, job.runId);
        if (pending != null) {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("kind", pending.kind().name().toLowerCase(java.util.Locale.ROOT));
            decision.put("reason", redact(List.of(String.valueOf(pending.reason()))).get(0));
            decision.put("options", pending.options());
            decision.put("task", pending.taskId());
            decision.put("updated_at", pending.updatedAt().toString());
            if (pending.expiresAt() != null) decision.put("expires_at", pending.expiresAt().toString());
            view.put("decision", decision);
        }
        return view;
    }
}
