package dev.oseer;

import dev.oseer.dashboard.Dashboard;
import dev.oseer.dashboard.RunLauncher;
import dev.oseer.json.Json;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The browser starts runs only from a previewed, confirmed plan, in a git repository's top. */
public final class RunLauncherTest implements Suite {
    public String name() { return "run-launcher"; }

    static final String FAKE = """
            log="$1"; shift
            printf '%s\\n' "$*" >> "$log"
            rid=""; prev=""
            for a in "$@"; do if [ "$prev" = "--run-id" ]; then rid="$a"; fi; prev="$a"; done
            echo "run   $rid" >&2
            echo "plan  implement -> review" >&2
            echo "bound 3 vendor call(s)" >&2
            echo "vendor said api_key=sk-abcdefghijklmnop" >&2
            case "$*" in *stops*) echo '{"ok":false,"reason":"vendor_protocol_failed"}'; exit 1;; esac
            case "$*" in *--dry-run*) mkdir -p ".oseer/runs/$rid" ".oseer/runs/$rid--implementer-0";; *slow*) sleep 30;; esac
            echo "home $OSEER_CONFIG_HOME" >&2
            echo '{"ok":true,"reason":"done","run_id":"'$rid'"}'
            """;

    public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("oseer-run-launcher-");
        Path home = scratch.resolve("home"), repo = scratch.resolve("repo"), plain = scratch.resolve("plain");
        Files.createDirectories(home);
        Files.createDirectories(repo.resolve(".oseer/tasks"));
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(plain);
        Files.writeString(repo.resolve(".oseer/tasks/hello.yaml"), "id: hello\n");
        Files.writeString(repo.resolve(".oseer/tasks/slow.yaml"), "id: slow\n");
        Files.writeString(repo.resolve(".oseer/tasks/stops.yaml"), "id: stops\n");
        git(repo, "init", "-q");
        git(repo, "-c", "user.email=t@example.invalid", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "init");
        Path script = scratch.resolve("fake-oseer.sh"), log = scratch.resolve("argv.log");
        Files.writeString(script, FAKE);
        RunLauncher launcher = new RunLauncher(home, repo, List.of("sh", script.toString(), log.toString()));

        // The target is shown before anything starts, and only a repository's top is accepted.
        check.rejects("a relative path is refused", "absolute", () -> launcher.inspect(Map.of("project", "repo")));
        check.rejects("a missing directory is refused", "no such directory", () -> launcher.inspect(Map.of("project", scratch.resolve("nope").toString())));
        check.rejects("a directory outside git is refused", "not a git repository", () -> launcher.inspect(Map.of("project", plain.toString())));
        try { launcher.inspect(Map.of("project", repo.resolve("src").toString())); check.that("a subdirectory is refused", false); }
        catch (RunLauncher.Refused refused) {
            check.eq("a subdirectory is refused by code", "project_not_repo_root", refused.code());
            check.eq("the refusal names only the top folder, the page says the rest", repo.toRealPath().toString(), refused.getMessage());
        }
        try { launcher.inspect(Map.of("project", repo.resolve(".oseer/tasks").toString())); check.that("a folder inside .oseer is refused", false); }
        catch (RunLauncher.Refused refused) {
            check.eq("a folder inside .oseer is called Overseer's own", "project_inside_oseer", refused.code());
        }
        Map<String, Object> inspected = launcher.inspect(Map.of("project", repo.toString()));
        check.eq("inspect names the repository", repo.toRealPath().toString(), inspected.get("project"));
        check.eq("inspect lists its task contracts", List.of("hello", "slow", "stops"), inspected.get("tasks"));
        check.that("inspect shows the branch and HEAD", inspected.get("branch") != null && inspected.get("head") != null);
        check.eq("an empty project means the dashboard's own", repo.toRealPath().toString(), launcher.inspect(Map.of()).get("project"));

        // The browser sends fields; each is validated before it becomes an argument.
        check.rejects("do needs a goal", "goal", () -> launcher.preview(Map.of("kind", "do", "params", Map.of())));
        check.rejects("a call cap above 50 is refused", "max_role_runs", () -> launcher.preview(Map.of("kind", "do",
                "params", Map.of("goal", "x", "max_role_runs", 99))));
        check.rejects("an unknown mode is refused", "mode", () -> launcher.preview(Map.of("kind", "do",
                "params", Map.of("goal", "x", "mode", "yolo"))));
        check.rejects("a vendor override must name a stage and a profile", "use", () -> launcher.preview(Map.of("kind", "run",
                "params", Map.of("task", "hello", "use", Map.of("review", "x; rm -rf /")))));
        check.rejects("run needs a known task", "task", () -> launcher.preview(Map.of("kind", "run", "params", Map.of("task", "nope"))));
        check.rejects("only do and run are previewed", "kind", () -> launcher.preview(Map.of("kind", "land", "params", Map.of())));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("goal", "Сделай кнопку синей; it's \"quoted\"");
        params.put("mode", "quick");
        params.put("risk", "low");
        params.put("max_role_runs", 3);
        params.put("max_cost_usd", "0.5");
        params.put("draft_only", true);
        params.put("use", Map.of("review", "codex-review"));
        params.put("effort", Map.of("implement", "high"));
        Map<String, Object> preview = launcher.preview(Map.of("kind", "do", "params", params));
        String argv = Files.readString(log);
        check.contains("the preview is a dry run", argv, "--dry-run");
        check.contains("the goal travels in a file, not on the command line", argv, "--goal-file");
        check.that("so the goal text is not an argument", !argv.contains("синей"));
        check.contains("the call cap is passed", argv, "--max-role-runs 3");
        check.contains("the spend threshold is passed", argv, "--max-cost-usd 0.5");
        check.contains("the vendor override is passed", argv, "--use review=codex-review");
        check.contains("the effort override is passed", argv, "--effort implement=high");
        check.contains("draft-only is passed", argv, "--draft-only");
        check.contains("the job never waits on a terminal gate", argv, "--no-wait-for-gate");
        check.that("the preview has an id to start", preview.get("preview_id") instanceof String);
        try (var left = Files.list(repo.resolve(".oseer/runs"))) {
            check.eq("the dry run leaves no run or step directories behind", List.of(), left.map(p -> p.getFileName().toString()).toList());
        }
        check.contains("the plan shows the narrated bound", String.valueOf(preview.get("plan")), "bound 3 vendor call(s)");
        check.that("the plan masks a quoted key", !String.valueOf(preview.get("plan")).contains("sk-abcdefghijklmnop"));
        check.contains("the plan runs with the server's configuration home", String.valueOf(preview.get("plan")), home.toString());

        // The start names the preview, carries the confirmation, and finds nothing moved.
        check.rejects("a start without confirmation is refused", "confirm", () -> launcher.start(Map.of("preview_id", preview.get("preview_id"))));
        check.rejects("an unknown preview is refused", "preview again", () -> launcher.start(Map.of("preview_id", "nope", "confirm", true)));
        Map<String, Object> stale = launcher.preview(Map.of("kind", "run", "params", Map.of("task", "hello")));
        Files.writeString(repo.resolve(".oseer/tasks/hello.yaml"), "id: hello\n# edited\n");
        check.rejects("a task edited after the preview is refused", "changed since the preview",
                () -> launcher.start(Map.of("preview_id", stale.get("preview_id"), "confirm", true)));
        check.rejects("a refused preview cannot be reused", "preview again",
                () -> launcher.start(Map.of("preview_id", stale.get("preview_id"), "confirm", true)));

        Map<String, Object> fresh = launcher.preview(Map.of("kind", "run", "params", Map.of("task", "hello", "no_orca_gate", true)));
        Map<String, Object> started = launcher.start(Map.of("preview_id", fresh.get("preview_id"), "confirm", true));
        String job = String.valueOf(started.get("id"));
        Map<String, Object> done = waitFor(launcher, job);
        check.eq("the job finishes (" + done.get("note") + " exit " + done.get("exit_code") + ")", "finished", done.get("state"));
        check.eq("the job runs under the previewed run id", fresh.get("run_id"), done.get("run_id"));
        check.eq("the job reports the engine's result", true, done.get("result") instanceof Map<?, ?> result ? result.get("ok") : null);
        check.that("the job's narration is redacted", !String.valueOf(done.get("narration")).contains("sk-abcdefghijklmnop"));
        check.that("a real start is not a dry run", !Files.readAllLines(log).getLast().contains("--dry-run"));
        check.contains("the Orca gate switch reaches the engine", Files.readAllLines(log).getLast(), "--no-orca-gate");
        check.rejects("a preview starts once", "preview again", () -> launcher.start(Map.of("preview_id", fresh.get("preview_id"), "confirm", true)));

        Map<String, Object> stopsPlan = launcher.preview(Map.of("kind", "run", "params", Map.of("task", "stops")));
        Map<String, Object> stopped = waitFor(launcher, String.valueOf(launcher.start(Map.of("preview_id", stopsPlan.get("preview_id"), "confirm", true)).get("id")));
        check.eq("a run that stops for a person is stopped, not failed", "stopped", stopped.get("state"));

        // One job per project; cancel stops it.
        Map<String, Object> slowPlan = launcher.preview(Map.of("kind", "run", "params", Map.of("task", "slow")));
        Map<String, Object> slow = launcher.start(Map.of("preview_id", slowPlan.get("preview_id"), "confirm", true));
        Map<String, Object> second = launcher.preview(Map.of("kind", "run", "params", Map.of("task", "hello")));
        check.eq("the preview says the project is busy", true, second.get("busy"));
        check.rejects("a second job in the same project is refused", "still running",
                () -> launcher.start(Map.of("preview_id", second.get("preview_id"), "confirm", true)));
        long before = System.nanoTime();
        launcher.cancel(Map.of("job", slow.get("id")));
        Map<String, Object> cancelled = waitFor(launcher, String.valueOf(slow.get("id")));
        check.eq("cancel stops the job", "cancelled", cancelled.get("state"));
        check.that("cancel does not wait for the job to finish on its own", System.nanoTime() - before < 15_000_000_000L);
        check.eq("the job list shows every job", 3, launcher.jobs().size());
        check.rejects("an unknown job is refused", "no such job", () -> launcher.job("job-nope"));

        Files.createDirectories(repo.resolve(".oseer/runs/run-earlier"));
        Map<String, Object> attached = launcher.attach(Map.of("project", repo.toString(), "run_id", "run-earlier"));
        check.eq("a run started elsewhere can be attached", "run-earlier", attached.get("run_id"));
        check.eq("attaching runs nothing", "finished", attached.get("state"));
        check.rejects("an unknown run cannot be attached", "no such run", () -> launcher.attach(Map.of("project", repo.toString(), "run_id", "run-nope")));

        // Answers and lands are validated before anything starts.
        check.rejects("an answer needs a pending decision", "no pending decision",
                () -> launcher.answer(Map.of("project", repo.toString(), "run_id", "run-none", "decision", "abort")));
        check.rejects("a push needs its own confirmation", "confirm the push",
                () -> launcher.land(Map.of("project", repo.toString(), "run_id", "run-x", "push", true)));

        // Over HTTP every launcher action needs the page token and the page's own origin.
        try (Dashboard server = new Dashboard(repo, 0, null, home, name -> null);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String base = server.url();
            HttpResponse<String> disabled = post(client, base, "inspect", Map.of(), token(client, base), null);
            check.eq("an embedded dashboard does not launch", 404, disabled.statusCode());
            server.enableLauncher(launcher);
            String token = token(client, base);
            check.eq("no token, no launch", 403, post(client, base, "inspect", Map.of(), null, null).statusCode());
            check.eq("a foreign origin cannot launch", 403, post(client, base, "inspect", Map.of(), token, "https://foreign.invalid").statusCode());
            HttpResponse<String> ok = post(client, base, "inspect", Map.of("project", repo.toString()), token, null);
            check.eq("the page can inspect a project", 200, ok.statusCode());
            check.contains("inspect answers JSON", ok.body(), "\"tasks\"");
            HttpResponse<String> bad = post(client, base, "inspect", Map.of("project", plain.toString()), token, null);
            check.eq("a refusal is a client error", 400, bad.statusCode());
            check.contains("with its code", bad.body(), "project_not_git");
            check.contains("the job list is readable", get(client, base, "api/launch/jobs").body(), job);
            check.eq("a job's view is readable", 200, get(client, base, "api/launch/jobs/" + job).statusCode());
            check.eq("a run of an unknown job's project is not readable", 404, get(client, base, "api/runs/run-x?job=job-nope").statusCode());
            check.contains("the page has the launch tab", get(client, base, "").body(), "id=\"launch-form\"");
        }
        check.that("redaction masks bearer tokens", !String.valueOf(redact("Authorization: Bearer abcdefghijkl1234")).contains("abcdefghijkl1234"));
    }

    static Object redact(String line) throws Exception {
        var method = RunLauncher.class.getDeclaredMethod("redact", List.class);
        method.setAccessible(true);
        return method.invoke(null, List.of(line));
    }

    static Map<String, Object> waitFor(RunLauncher launcher, String job) throws Exception {
        long deadline = System.nanoTime() + 40_000_000_000L;
        Map<String, Object> view = launcher.job(job);
        while ("running".equals(view.get("state")) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            view = launcher.job(job);
        }
        return view;
    }

    static void git(Path dir, String... args) throws Exception {
        List<String> argv = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        argv.addAll(List.of(args));
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("git " + List.of(args) + " failed");
    }

    static String token(HttpClient client, String base) throws Exception {
        var match = Pattern.compile("name=\"oseer-settings-token\" content=\"([^\"]+)\"").matcher(get(client, base, "").body());
        if (!match.find()) throw new IllegalStateException("no token");
        return match.group(1);
    }

    static HttpResponse<String> get(HttpClient client, String base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient client, String base, String action, Map<String, Object> payload,
                                     String token, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "api/launch/" + action))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("X-Overseer-Token", token);
        if (origin != null) request.header("Origin", origin);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(Json.write(payload))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
