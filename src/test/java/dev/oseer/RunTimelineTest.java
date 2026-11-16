package dev.oseer;

import dev.oseer.dashboard.Dashboard;
import dev.oseer.dashboard.RunTimeline;
import dev.oseer.json.Json;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The run timeline projection, its evidence route and the dashboard frame that shows them. */
public final class RunTimelineTest implements Suite {
    public String name() { return "run-timeline"; }

    public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("oseer-run-timeline-");
        Path runs = root.resolve(".oseer/runs");
        String id = "run-alpha";
        Files.createDirectories(runs.resolve(id));
        Files.writeString(runs.resolve(id + "/run.json"), Json.write(Map.of("run_id", id, "task_id", "demo",
                "reserved_at", "2026-11-16T10:00:00Z")));
        Path baselineDir = Files.createDirectories(runs.resolve(id + "--baseline-0"));
        Files.writeString(baselineDir.resolve("baseline-gate.json"), Json.write(Map.of("phase", "baseline", "ok", true,
                "commands", List.of(command("make test", 0, 1200)))));
        Path implementer = Files.createDirectories(runs.resolve(id + "--implementer-0"));
        Files.createDirectories(implementer.resolve("prompts"));
        Files.createDirectories(implementer.resolve("raw"));
        Files.createDirectories(implementer.resolve("artifacts"));
        Files.writeString(implementer.resolve("prompts/implementer.md"), "Do the task");
        Files.writeString(implementer.resolve("raw/implementer.stdout.txt"),
                "working\nkey sk-proj-ABCDEFGHIJKLMNOPQRSTUVWX01 used\nAuthorization: Bearer abcdefghijklmnop12345\napi_key=\"hunter2hunter2\"\ndone");
        Files.writeString(implementer.resolve("artifacts/implementer.json"), "{\"status\":\"done\"}");
        Files.writeString(implementer.resolve("view-implementer.json"), "{\"command\":\"secret argv\"}");
        Files.writeString(implementer.resolve("evidence.jsonl"), "{}\n");
        Path gates = Files.createDirectories(runs.resolve(id + "--gates-0"));
        Files.writeString(gates.resolve("machine-gate.json"), Json.write(Map.of("phase", "acceptance", "ok", false,
                "violations", List.of(), "changed_paths", List.of("a", "b"),
                "commands", List.of(command("make test", 2, 900), command("make lint", 1, 300), command("make build", 0, 500)))));
        Path look = Files.createDirectories(runs.resolve(id + "--visual-qa-0/screenshots"));
        Files.write(look.resolve("800x600.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0});
        Files.writeString(look.resolve("fake.png"), "not an image at all");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", id); summary.put("task_id", "demo"); summary.put("ok", false);
        summary.put("reason", "gates_failed"); summary.put("total_cost_usd", 0.5); summary.put("unpriced_calls", 1L);
        summary.put("baseline_required", true); summary.put("baseline_ok", true); summary.put("baseline_code", "passed");
        summary.put("baseline_commands", List.of("make test"));
        summary.put("baseline_report", root.resolve(".oseer/runs/" + id + "--baseline-0/baseline-gate.json").toString());
        summary.put("decision_note", "PRIVATE_NOTE_MARKER");
        summary.put("workflow", List.of(Map.of("stage", "implement", "run", "role", "role", "implementer", "when", List.of()),
                Map.of("stage", "gates", "run", "machine_gates", "when", List.of()),
                Map.of("stage", "review", "run", "role", "role", "reviewer", "when", List.of("review_required"))));
        summary.put("completed_stages", List.of("implement"));
        summary.put("pending_stages", List.of(Map.of("stage", "gates", "reason", "did_not_pass")));
        summary.put("inapplicable_stages", List.of("review"));
        Map<String, Object> attempt1 = new LinkedHashMap<>();
        attempt1.put("duration_millis", 1000L); attempt1.put("model_requested", "m-1"); attempt1.put("model_reported", null);
        attempt1.put("effort_requested", "high"); attempt1.put("runner", "direct"); attempt1.put("raw_stdout", "SECRET_RAW_MARKER");
        Map<String, Object> attempt2 = new LinkedHashMap<>(attempt1);
        attempt2.put("duration_millis", 2500L); attempt2.put("model_reported", "m-1-build");
        attempt2.put("tokens", Map.of("input", 10L, "output", 20L, "cache", 99L));
        Map<String, Object> roleStep = new LinkedHashMap<>();
        roleStep.put("step", "implementer"); roleStep.put("stage", "implement"); roleStep.put("attempt", 0L);
        roleStep.put("ok", true); roleStep.put("code", "ok"); roleStep.put("profile", "writer"); roleStep.put("vendor", "acme");
        roleStep.put("cost_usd", null); roleStep.put("vendor_attempts", List.of(attempt1, attempt2));
        roleStep.put("artifact_path", ".oseer/runs/" + id + "--implementer-0/artifacts/implementer.json");
        summary.put("steps", List.of(
                Map.of("step", "baseline", "attempt", 0L, "ok", true, "code", "passed",
                        "report", root.resolve(".oseer/runs/" + id + "--baseline-0/baseline-gate.json").toString()),
                roleStep,
                Map.of("step", "gates", "stage", "gates", "attempt", 0L, "ok", false, "code", "gates_failed",
                        "report", root.resolve(".oseer/runs/" + id + "--gates-0/machine-gate.json").toString()),
                Map.of("step", "visual_qa", "stage", "browser", "attempt", 0L, "ok", true, "code", "passed",
                        "report", root.resolve(".oseer/runs/" + id + "--visual-qa-0/visual-qa.json").toString())));
        Files.writeString(runs.resolve(id + "/task-run.json"), Json.write(summary));

        Map<String, Object> timeline = RunTimeline.read(root, id);
        check.eq("a summarised run is finished", "finished", timeline.get("state"));
        check.eq("task identity is read", "demo", timeline.get("task_id"));
        List<?> steps = (List<?>) timeline.get("steps");
        check.eq("every summary step is on the timeline", 4, steps.size());
        Map<?, ?> baselineStep = (Map<?, ?>) steps.get(0), role = (Map<?, ?>) steps.get(1), gate = (Map<?, ?>) steps.get(2);
        check.eq("baseline is a gate", "gate", baselineStep.get("kind"));
        check.eq("baseline duration comes from its commands", 1200L, ((Number) baselineStep.get("duration_ms")).longValue());
        check.eq("role duration sums every vendor attempt", 3500L, ((Number) role.get("duration_ms")).longValue());
        check.eq("model is the one the last attempt reported", "m-1-build", role.get("model"));
        check.eq("requested model is kept beside it", "m-1", role.get("model_requested"));
        check.eq("effort is the requested effort", "high", role.get("effort"));
        check.eq("attempt count is shown", 2, ((Number) role.get("vendor_attempts")).intValue());
        check.eq("a vendor call without a price is unpriced, not free", true, role.get("unpriced"));
        check.eq("only input and output tokens are shown", Map.of("input", 10L, "output", 20L), role.get("tokens"));
        check.eq("role evidence directory comes from the artifact path", id + "--implementer-0", role.get("evidence_dir"));
        List<String> evidence = ((List<?>) role.get("evidence")).stream().map(f -> String.valueOf(((Map<?, ?>) f).get("path"))).toList();
        check.that("prompt, output and artifact are offered", evidence.containsAll(List.of("prompts/implementer.md",
                "raw/implementer.stdout.txt", "artifacts/implementer.json")));
        check.that("live views and ledgers are not offered as evidence", evidence.stream().noneMatch(p -> p.startsWith("view-") || p.endsWith(".jsonl")));
        List<?> commands = (List<?>) gate.get("commands");
        check.eq("gate commands are listed", 3, commands.size());
        check.eq("a red baseline command that was green before is a regression", "regression", ((Map<?, ?>) commands.get(0)).get("attribution"));
        check.eq("a red command outside the baseline has no earlier record", "no_baseline", ((Map<?, ?>) commands.get(1)).get("attribution"));
        check.that("a green command carries no attribution", !((Map<?, ?>) commands.get(2)).containsKey("attribution"));
        check.eq("gate duration sums its commands", 1700L, ((Number) gate.get("duration_ms")).longValue());
        check.eq("gate changed paths are counted", 2, ((Number) gate.get("changed_paths")).intValue());
        Map<?, ?> baseline = (Map<?, ?>) timeline.get("baseline");
        check.eq("baseline is recorded", true, baseline.get("recorded"));
        check.eq("baseline commands are read from its report", 1, ((List<?>) baseline.get("commands")).size());
        List<?> workflow = (List<?>) timeline.get("workflow");
        check.eq("completed stage", "completed", ((Map<?, ?>) workflow.get(0)).get("state"));
        check.eq("pending stage", "pending", ((Map<?, ?>) workflow.get(1)).get("state"));
        check.eq("inapplicable stage", "skipped", ((Map<?, ?>) workflow.get(2)).get("state"));
        Map<?, ?> totals = (Map<?, ?>) timeline.get("totals");
        check.eq("one unpriced step is counted", 1L, ((Number) totals.get("unpriced_steps")).longValue());
        String json = Json.write(timeline);
        check.that("raw output paths and prose are not in the projection", !json.contains("SECRET_RAW_MARKER"));
        check.that("the person's note stays on the decision page", !json.contains("PRIVATE_NOTE_MARKER"));

        // A run with no summary yet is read from its step directories and live views.
        String live = "run-live";
        Files.createDirectories(runs.resolve(live));
        Files.writeString(runs.resolve(live + "/run.json"), Json.write(Map.of("run_id", live, "task_id", "demo")));
        Path liveStep = Files.createDirectories(runs.resolve(live + "--implementer-0"));
        var self = ProcessHandle.current();
        Files.writeString(liveStep.resolve("view-implementer.json"), Json.write(Map.of("stage", "implement",
                "role", "implementer", "profile", "writer", "vendor", "acme", "view_state", "running",
                "controller_pid", self.pid(), "controller_started_at", self.info().startInstant().orElseThrow().toString(),
                "raw_stdout", "SECRET_RAW_MARKER")));
        Map<String, Object> running = RunTimeline.read(root, live);
        check.eq("a run with a live role view is running", "running", running.get("state"));
        check.eq("the live step is shown as running", "running", ((Map<?, ?>) ((List<?>) running.get("steps")).get(0)).get("status"));
        check.that("live projection carries no raw output", !Json.write(running).contains("SECRET_RAW_MARKER"));
        Files.writeString(liveStep.resolve("view-implementer.json"), Json.write(Map.of("stage", "implement",
                "view_state", "running", "controller_pid", -1L)));
        check.eq("a dead controller is not running", "no_summary", RunTimeline.read(root, live).get("state"));

        for (String bad : List.of("", "../x", "run-alpha--implementer-0", "missing", ".hidden", "a/b")) {
            boolean refused;
            try { RunTimeline.read(root, bad); refused = false; } catch (RunTimeline.NotFound expected) { refused = true; }
            check.that("run id '" + bad + "' is refused", refused);
        }

        var served = RunTimeline.evidence(root, id, id + "--implementer-0", "raw/implementer.stdout.txt");
        String text = new String(served.bytes(), StandardCharsets.UTF_8);
        check.eq("text evidence is plain text", "text/plain; charset=utf-8", served.contentType());
        check.that("an API-key shaped token is masked", !text.contains("ABCDEFGHIJKLMNOPQRSTUVWX01") && text.contains("[redacted]"));
        check.that("a bearer token is masked", !text.contains("abcdefghijklmnop12345"));
        check.that("an api_key assignment is masked", !text.contains("hunter2hunter2"));
        check.that("ordinary output survives", text.contains("working") && text.contains("done"));
        check.eq("a real PNG is served as an image", "image/png",
                RunTimeline.evidence(root, id, id + "--visual-qa-0", "screenshots/800x600.png").contentType());
        refused(check, "a .png that is not a PNG", () -> RunTimeline.evidence(root, id, id + "--visual-qa-0", "screenshots/fake.png"));
        refused(check, "a live view file", () -> RunTimeline.evidence(root, id, id + "--implementer-0", "view-implementer.json"));
        refused(check, "the ledger", () -> RunTimeline.evidence(root, id, id + "--implementer-0", "evidence.jsonl"));
        refused(check, "another run's directory", () -> RunTimeline.evidence(root, id, live + "--implementer-0", "prompts/implementer.md"));
        refused(check, "a parent path", () -> RunTimeline.evidence(root, id, id + "--implementer-0", "prompts/../../x.md"));
        try {
            Files.createSymbolicLink(implementer.resolve("prompts/linked.md"), root.resolve("outside.md"));
            Files.writeString(root.resolve("outside.md"), "outside");
            refused(check, "a symbolic link", () -> RunTimeline.evidence(root, id, id + "--implementer-0", "prompts/linked.md"));
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            check.that("symbolic links unsupported here", true);
        }

        try (var dashboard = new Dashboard(root, 0); var client = HttpClient.newHttpClient()) {
            dashboard.start();
            var page = client.send(HttpRequest.newBuilder(URI.create(dashboard.url())).build(), HttpResponse.BodyHandlers.ofString());
            check.contains("the page has a tab list", page.body(), "role=\"tablist\"");
            for (String panel : List.of("overview", "runs", "next", "roles", "analytics", "connections")) {
                check.contains("panel " + panel + " exists", page.body(), "id=\"panel-" + panel + "\"");
            }
            check.contains("the run timeline is on the page", page.body(), "id=\"run-detail\"");
            check.that("the frame never assigns innerHTML", !page.body().contains("innerHTML"));
            check.that("the frame loads nothing from another origin", !page.body().contains("src=\"http") && !page.body().contains("href=\"http"));
            check.contains("screenshots may be shown from this origin only", String.valueOf(page.headers().firstValue("Content-Security-Policy").orElse("")), "img-src 'self'");
            String token = page.body().replaceAll("(?s).*name=\"oseer-token\" content=\"([^\"]+)\".*", "$1");
            var detail = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/runs/" + id)).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("run detail is readable", 200, detail.statusCode());
            check.contains("run detail is the timeline", detail.body(), "\"evidence_dir\"");
            check.eq("an unknown run is 404", 404, client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/runs/nope")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            String path = "evidence/" + id + "/" + id + "--implementer-0/prompts/implementer.md";
            check.eq("evidence needs the page token", 403, client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + path)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            check.eq("a wrong token is refused", 403, client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + path + "?t=wrong")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var file = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + path + "?t=" + token)).build(), HttpResponse.BodyHandlers.ofString());
            check.eq("evidence with the token is served", 200, file.statusCode());
            check.eq("evidence body", "Do the task", file.body());
            check.eq("evidence is not sniffed", "nosniff", file.headers().firstValue("X-Content-Type-Options").orElse(""));
            var image = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "evidence/" + id + "/" + id
                    + "--visual-qa-0/screenshots/800x600.png?t=" + token)).build(), HttpResponse.BodyHandlers.ofByteArray());
            check.eq("a screenshot is served as bytes", 10, image.body().length);
            var foreign = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + path + "?t=" + token))
                    .header("Origin", "https://example.invalid").build(), HttpResponse.BodyHandlers.ofString());
            check.eq("another origin cannot read evidence", 403, foreign.statusCode());
            var state = Json.parseObject(client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/state")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            List<?> open = (List<?>) state.get("open_runs");
            check.that("a reserved run without a summary is listed as open", open.stream().anyMatch(r -> live.equals(((Map<?, ?>) r).get("run_id"))));
            check.that("a summarised run is not listed as open", open.stream().noneMatch(r -> id.equals(((Map<?, ?>) r).get("run_id"))));
        }
    }

    private interface Throwing { Object run() throws Exception; }

    private static void refused(Check check, String what, Throwing action) {
        boolean refused;
        try { action.run(); refused = false; }
        catch (RunTimeline.NotFound expected) { refused = true; }
        catch (Exception other) { refused = false; }
        check.that(what + " is not served", refused);
    }

    private static Map<String, Object> command(String command, int exit, long millis) {
        return Map.of("command", command, "exit_code", (long) exit, "timed_out", false, "duration_ms", millis);
    }
}
