package dev.oseer.dashboard;

import com.sun.net.httpserver.HttpServer;
import dev.oseer.config.Profile;
import dev.oseer.config.ConfigFileStore;
import dev.oseer.config.UserConfig;
import dev.oseer.config.ConnectionService;
import dev.oseer.execution.OsCredentialStore;
import dev.oseer.execution.OsCredentialStores;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Local projection and versioned settings editor. No dispatch, approval or terminal input.
 *
 * The next-run builder previews settings and exports commands without executing them.
 * Connection settings are edited through the same user service as local commands.
 * For a pending decision, put up its decision page, or go to the one already up.
 * A decision used to outlive the only process that knew its page, and the way back was
 * `oseer decide` typed in the worktree. The answer itself is still given on the decision
 * page, through the same path as `oseer approve`; this only finds or starts that page.
 * The action is a POST carrying a token that exists only in the page this server rendered,
 * so another page in the same browser can neither see it nor make the request.
 */
public final class Dashboard implements AutoCloseable {

    /** Finds or starts the decision page for one pending run, and returns its URL. */
    @FunctionalInterface
    public interface DecisionOpener {
        String open(String runId) throws Exception;
    }

    private final HttpServer server;
    private final String token;
    private final ExecutorService requests;
    private static final int MAX_SETTINGS_BODY = 16 * 1024;

    public Dashboard(Path project, int port) throws IOException {
        this(project, port, null);
    }

    /** @param decisions null when the dashboard does not offer decision-page actions */
    public Dashboard(Path project, int port, DecisionOpener decisions) throws IOException {
        this(project, port, decisions, UserConfig.defaultHome(), System::getenv, OsCredentialStores.platform());
    }

    /** Explicit user scope lets tests and embedded callers avoid the operator's configuration. */
    public Dashboard(Path project, int port, DecisionOpener decisions, Path configHome,
                     Function<String, String> environment) throws IOException {
        this(project, port, decisions, configHome, environment, OsCredentialStores.unavailable());
    }

    /** A null vault keeps the environment-reference form and does not probe the OS. */
    public Dashboard(Path project, int port, DecisionOpener decisions, Path configHome,
                     Function<String, String> environment, OsCredentialStore credentials) throws IOException {
        this(project, port, decisions, new NextRun(project, configHome,
                profile -> RoleRunner.executableFound(new ProcessRunner(), profile)), environment, credentials);
    }

    public Dashboard(Path project, int port, DecisionOpener decisions, NextRun nextRun) throws IOException {
        this(project, port, decisions, nextRun, System::getenv, OsCredentialStores.unavailable());
    }

    private Dashboard(Path project, int port, DecisionOpener decisions, NextRun nextRun,
                      Function<String, String> environment, OsCredentialStore credentials) throws IOException {
        Path home = nextRun.home;
        SettingsEditor settings = new SettingsEditor(nextRun);
        RunAnalytics analytics = new RunAnalytics(project);
        OsCredentialStore vault = credentials == null ? OsCredentialStores.unavailable() : credentials;
        ConnectionService connections = new ConnectionService(home, environment, vault);
        var models = new dev.oseer.config.ModelSettingsService(home, project, environment);
        boolean osStore = vault.available();
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        requests = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "oseer-dashboard-request");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(requests);
        server.createContext("/", exchange -> {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String authority = "127.0.0.1:" + server.getAddress().getPort();
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String contentType = "application/json; charset=utf-8";
            String body;
            byte[] binary = null;
            String location = null;
            if (!authority.equals(host) || (origin != null && !origin.equals("http://" + authority))) {
                status = 403; body = "{}";
            } else if ("POST".equals(exchange.getRequestMethod())
                    && (path.startsWith("/api/connections/") || path.startsWith("/api/models/"))) {
                String suppliedToken = exchange.getRequestHeaders().getFirst("X-Overseer-Token");
                String suppliedType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (suppliedToken == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {
                    status = 403; body = Json.write(Map.of("ok", false, "code", "settings_token_invalid"));
                } else if (suppliedType == null || !"application/json".equalsIgnoreCase(suppliedType.split(";", 2)[0].trim())) {
                    status = 415; body = Json.write(Map.of("ok", false, "code", "settings_json_required"));
                } else {
                    int bodyLimit="/api/models/save-jev-policy".equals(path)?160*1024:MAX_SETTINGS_BODY;
                    byte[] input = exchange.getRequestBody().readNBytes(bodyLimit + 1);
                    if (input.length > bodyLimit) {
                        status = 413; body = Json.write(Map.of("ok", false, "code", "settings_body_too_large"));
                    } else {
                        Map<String, Object> payload = null;
                        try {
                            String json = StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(input)).toString();
                            payload = Json.parseObject(json);
                        } catch (RuntimeException | java.nio.charset.CharacterCodingException invalid) {
                            // Parser diagnostics may quote pasted credentials. Return a static error.
                        }
                        if (payload == null) {
                            status = 400; body = Json.write(Map.of("ok", false, "code", "settings_json_invalid"));
                        } else {
                            if (path.startsWith("/api/models/")) {
                                var outcome = models.apply(path.substring("/api/models/".length()), payload);
                                status = outcome.status(); body = Json.write(outcome.report());
                            } else {
                                ConnectionService.Outcome outcome = connections.apply(path.substring("/api/connections/".length()), payload);
                                status = outcome.status(); body = Json.write(outcome.report());
                            }
                        }
                    }
                }
            } else if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/decide/")) {
                contentType = "text/html; charset=utf-8";
                String runId = path.substring("/decide/".length());
                Map<String, String> form = form(new String(
                        exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8));
                if (decisions == null) {
                    status = 404; body = notice("{{t:dash.this_action_is_not_connected}}");
                } else if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        form.getOrDefault("token", "").getBytes(StandardCharsets.UTF_8))) {
                    status = 403; body = notice("{{t:dash.request_did_not_originate_from}}");
                } else if (!runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
                    status = 404; body = notice("{{t:dash.no_such_run}}");
                } else {
                    try {
                        location = decisions.open(runId);
                        status = 303; body = "";
                    } catch (Exception refused) {
                        status = 409;
                        body = notice("{{t:dash.decision_page_for}} " + runId + " {{t:dash.is_not_open}} " + refused.getMessage());
                    }
                }
            } else if ("POST".equals(exchange.getRequestMethod()) && List.of("/api/next-run/preview",
                    "/api/settings/preview", "/api/settings/apply").contains(path)) {
                if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        String.valueOf(exchange.getRequestHeaders().getFirst("X-Overseer-Token"))
                                .getBytes(StandardCharsets.UTF_8))) {
                    status = 403; body = "{}";
                } else {
                    byte[] input = exchange.getRequestBody().readNBytes(32769);
                    if (input.length > 32768) {
                        status = 413; body = Json.write(Map.of("error", "preview_too_large"));
                    } else try {
                        Map<String, Object> request = Json.parseObject(new String(input, StandardCharsets.UTF_8));
                        body = Json.write(switch (path) {
                            case "/api/settings/preview" -> settings.preview(request);
                            case "/api/settings/apply" -> settings.apply(request);
                            default -> nextRun.preview(request);
                        });
                    } catch (NextRun.Conflict changed) {
                        status = 409; body = Json.write(Map.of("error", "config_conflict", "message", changed.getMessage()));
                    } catch (ConfigFileStore.Refused refused) {
                        status = 409; body = Json.write(Map.of("error", refused.code(), "message", refused.getMessage()));
                    } catch (IllegalArgumentException | Json.JsonException invalid) {
                        status = 400; body = Json.write(Map.of("error", "invalid_preview", "message", String.valueOf(invalid.getMessage())));
                    } catch (Exception unavailable) {
                        status = 503; body = Json.write(Map.of("error", "Settings unavailable; reload configuration"));
                    }
                }
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                status = 405; body = "{}";
            } else if ("/".equals(path)) {
                contentType = "text/html; charset=utf-8";
                body = HTML.replace("{{CONNECTION_SETTINGS}}", ConnectionSettingsUi.html(osStore))
                        .replace("{{MODEL_SETTINGS}}", ModelSettingsUi.html())
                        .replace("{{SETTINGS_TOKEN}}", token)
                        .replace("{{TOKEN}}", token)
                        .replace("{{ACTIONS}}", decisions == null ? "false" : "true");
            } else if ("/api/models".equals(path)) {
                try { body = Json.write(models.list()); }
                catch (Exception failed) { status = 503; body = Json.write(Map.of("ok", false, "code", "settings_unavailable")); }
            } else if ("/api/connections".equals(path)) {
                try { body = Json.write(connections.list()); }
                catch (RuntimeException unavailable) {
                    status = 503; body = Json.write(Map.of("ok", false, "code", "connections_unavailable"));
                }
            } else if ("/api/next-run".equals(path)) {
                try {
                    body = Json.write(nextRun.describe(form(exchange.getRequestURI()
                            .getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery()).get("task")));
                } catch (NextRun.Conflict changed) {
                    status = 409; body = Json.write(Map.of("error", "config_conflict", "message", changed.getMessage()));
                } catch (IllegalArgumentException invalid) {
                    status = 400; body = Json.write(Map.of("error", "invalid_task", "message", String.valueOf(invalid.getMessage())));
                } catch (Exception unavailable) {
                    status = 503; body = Json.write(Map.of("error", "Settings unavailable; reload configuration"));
                }
            } else if ("/api/config".equals(path)) {
                try {
                    ProcessRunner processes = new ProcessRunner();
                    body = Json.write(ConfigView.of(project, UserConfig.load(home),
                            profile -> RoleRunner.executableFound(processes, profile), environment));
                } catch (Exception failed) {
                    status = 503; body = Json.write(Map.of("error", "Settings unavailable; inspect Overseer configuration"));
                }
            } else if ("/favicon.ico".equals(path)) {
                status = 204; body = "";
            } else if (path.startsWith("/api/runs/")) {
                try { body = Json.write(RunTimeline.read(project, path.substring("/api/runs/".length()))); }
                catch (RunTimeline.NotFound missing) { status = 404; body = Json.write(Map.of("error", "run_not_found")); }
                catch (Exception unavailable) { status = 503; body = Json.write(Map.of("error", "Run unavailable")); }
            } else if (path.startsWith("/evidence/")) {
                // Evidence is opened by a link this page rendered, carrying the page token, so a
                // page on another origin cannot even embed a screenshot of this project's runs.
                Map<String, String> query = form(exchange.getRequestURI().getRawQuery() == null
                        ? "" : exchange.getRequestURI().getRawQuery());
                String[] parts = path.substring("/evidence/".length()).split("/", 3);
                if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        query.getOrDefault("t", "").getBytes(StandardCharsets.UTF_8))) {
                    status = 403; body = "{}";
                } else if (parts.length != 3) {
                    status = 404; body = "{}";
                } else {
                    try {
                        RunTimeline.Evidence evidence = RunTimeline.evidence(project, parts[0], parts[1], parts[2]);
                        contentType = evidence.contentType(); binary = evidence.bytes(); body = "";
                    } catch (RunTimeline.NotFound missing) { status = 404; body = "{}"; }
                    catch (Exception unavailable) { status = 503; body = "{}"; }
                }
            } else if ("/api/state".equals(path)) {
                try { body = Json.write(snapshot(project, UserConfig.load(home))); }
                catch (Exception failed) { status = 503; body = Json.write(Map.of("error", "State unavailable; inspect Overseer configuration")); }
            } else if ("/api/settings".equals(path)) {
                try {
                    body = Json.write(settings.describe(form(exchange.getRequestURI().getRawQuery() == null
                            ? "" : exchange.getRequestURI().getRawQuery()).get("task")));
                } catch (NextRun.Conflict changed) {
                    status = 409; body = Json.write(Map.of("error", "config_conflict", "message", changed.getMessage()));
                } catch (IllegalArgumentException invalid) {
                    status = 400; body = Json.write(Map.of("error", "invalid_settings", "message", String.valueOf(invalid.getMessage())));
                } catch (Exception unavailable) {
                    status = 503; body = Json.write(Map.of("error", "Personal settings unavailable"));
                }
            } else if ("/api/analytics".equals(path)) {
                try {
                    Map<String, String> query = form(exchange.getRequestURI().getRawQuery() == null
                            ? "" : exchange.getRequestURI().getRawQuery());
                    body = Json.write(analytics.read(query.get("task"), query.get("run")));
                } catch (IllegalArgumentException invalid) {
                    status = 400; body = Json.write(Map.of("error", "invalid_filter"));
                } catch (Exception unavailable) {
                    status = 503; body = Json.write(Map.of("error", "Analytics unavailable"));
                }
            } else { status = 404; body = "{}"; }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (location != null) exchange.getResponseHeaders().set("Location", location);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'");
            byte[] bytes = binary != null ? binary : Messages.render(body, Messages.language(exchange)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
    }

    private static String notice(String text) {
        return "<!doctype html><meta charset=utf-8><title>AI Overseer</title><p>" + text
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</p><p><a href=\"/\">{{t:dash.back_to_dashboard}}</a></p>";
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    public void start() { server.start(); }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    @Override public void close() { server.stop(0); requests.shutdownNow(); }

    public static Map<String, Object> snapshot(Path project, UserConfig user) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("project", project.toAbsolutePath().normalize().toString());
        state.put("config_home", user.home().toString());
        state.put("observed_at", Instant.now().toString());
        state.put("problems", user.problems());
        List<Map<String, Object>> plan = new ArrayList<>();
        if (user.policy() != null) for (var stage : user.policy().workflow().stages()) {
            if (stage.role() == null) continue;
            var role = user.policy().roles().get(stage.role());
            if (role == null) continue;
            for (String name : role.profiles()) {
                Profile profile = user.profiles().get(name);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage.name()); row.put("role", stage.role()); row.put("profile", name);
                row.put("strategy", role.strategy()); row.put("when", stage.when());
                row.put("position", user.policy().workflow().rotationPositionOf(stage));
                if (profile != null) {
                    row.put("vendor", profile.vendor());
                    row.put("model", profile.model()); row.put("effort", profile.effort());
                    row.put("runner", profile.runner()); row.put("verified", profile.verified());
                    row.put("read_only", profile.readOnly());
                }
                plan.add(row);
            }
        }
        state.put("plan", plan);
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
                    if (!Files.exists(summaryFile, LinkOption.NOFOLLOW_LINKS) && !directoryName.contains("--")
                            && Files.isRegularFile(directory.resolve("run.json"), LinkOption.NOFOLLOW_LINKS)) {
                        // Reserved and not summarised: still going, or stopped before it wrote
                        // a result. The timeline tells which from its live role views.
                        try {
                            Path reservation = directory.resolve("run.json");
                            if (Files.size(reservation) <= 64 * 1024 && reservation.toRealPath().startsWith(runsRoot)) {
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
                            if (Files.size(summaryFile) <= 2L * 1024 * 1024
                                    && summaryFile.toRealPath().startsWith(runsRoot)) {
                                Map<String, Object> source = Json.parseObject(Files.readString(summaryFile));
                                if (Boolean.TRUE.equals(source.get("dry_run"))) dryRunsHidden++;
                                else runRows.add(publicRun(source, summaryFile, directory, runsRoot));
                            }
                        } catch (RuntimeException | IOException invalid) { /* incomplete projection is not a result */ }
                    }
                    try (var files = Files.list(directory)) {
                        for (Path file : files.filter(p -> p.getFileName().toString().startsWith("view-")
                                && p.toString().endsWith(".json") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                            if (Files.size(file) > 1024 * 1024) continue;
                            if (!file.toRealPath().startsWith(runsRoot)) continue;
                            try { attempts.add(publicAttempt(Json.parseObject(Files.readString(file)))); }
                            catch (RuntimeException | IOException invalid) { /* incomplete projection is not a result */ }
                        }
                    }
                }
            }
        }
        runRows.sort(Comparator.comparing(row -> String.valueOf(row.get("updated_at")), Comparator.reverseOrder()));
        attempts.sort(Comparator.comparing(row -> String.valueOf(row.get("updated_at")), Comparator.reverseOrder()));
        state.put("runs", runRows.stream().limit(100).toList());
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
        state.put("open_runs", openRuns.stream().limit(20).toList());
        state.put("dry_runs_hidden", dryRunsHidden);
        state.put("attempts", attempts.stream().limit(200).toList());
        return state;
    }

    static Map<String, Object> publicRun(Map<String, Object> source, Path summaryFile, Path directory,
                                         Path runsRoot) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : List.of("run_id", "task_id", "ok", "reason", "next_action", "prepare", "risk",
                "dry_run", "candidate_review_passed", "open_blocking_findings", "role_runs",
                "total_cost_usd", "unpriced_calls", "attempts_used", "continued_from")) {
            copyScalar(row, source, key);
        }
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
        if (steps != null) row.put("steps", steps);
        List<Map<String, Object>> pending = objectList(source.get("pending_stages"), List.of("stage", "reason"));
        if (pending != null) row.put("pending_stages", pending);
        List<Map<String, Object>> failovers = failovers(source.get("steps"));
        if (!failovers.isEmpty()) row.put("failovers", failovers);
        Map<String, Object> decision = readDecision(directory, runsRoot);
        if (decision != null) row.put("decision", decision);
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
    public static List<Map<String, Object>> failovers(Object steps) {
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
                    || Files.size(file) > 2L * 1024 * 1024
                    || !file.toRealPath().startsWith(runsRoot)) return null;
            Map<String, Object> source = Json.parseObject(Files.readString(file));
            Map<String, Object> visible = new LinkedHashMap<>();
            for (String key : List.of("state", "kind", "decision", "actor", "updated_at",
                    "created_at", "expires_at")) {
                copyScalar(visible, source, key);
            }
            List<String> options = stringList(source.get("options"));
            if (options != null) visible.put("options", options);
            return visible;
        } catch (RuntimeException | IOException invalid) {
            return null;
        }
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

    public static Map<String, Object> publicAttempt(Map<String, Object> source) {
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
        if ("running".equals(row.get("view_state"))) {
            boolean controllerAlive = source.get("controller_pid") instanceof Number pid
                    && ProcessHandle.of(pid.longValue()).filter(ProcessHandle::isAlive)
                    .flatMap(p -> p.info().startInstant()).map(Instant::toString)
                    .filter(start -> start.equals(source.get("controller_started_at"))).isPresent();
            if (!controllerAlive) row.put("view_state", "unknown_controller_stopped");
        }
        return row;
    }

    private static final String HTML = """
            <!doctype html><html lang="{{t:meta.html_lang}}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>AI Overseer · Agents</title><style>
            :root{color-scheme:dark;font:14px/1.5 system-ui;background:#11171d;color:#e5edf4}body{max-width:1500px;margin:32px auto;padding:0 24px}
            h1{font-size:30px;margin-bottom:4px}h2{font-size:19px;margin-top:32px}.muted{color:#9caebd}#connection{color:#79d7ac}
            .scroll{overflow:auto;border:1px solid #30404d;border-radius:12px}table{border-collapse:collapse;width:100%;white-space:nowrap}
            th,td{text-align:left;padding:12px 14px;border-bottom:1px solid #263541}th{color:#9fb4c6;font-weight:500;background:#19232c}
            tr:last-child td{border:0}td:first-child{font-weight:600}code{font-size:12px}input{background:#19232c;border:1px solid #405363;border-radius:6px;padding:8px 12px;color:inherit;min-width:240px}
            button{background:#233a35;color:#9ee9c6;border:1px solid #2f5a4d;border-radius:6px;padding:6px 12px;cursor:pointer;font:inherit}.badge{background:#233a35;color:#9ee9c6;border-radius:5px;padding:3px 7px}.bad{color:#ffae94}#path{overflow-wrap:anywhere}.empty{padding:28px;color:#9caebd}
            details.timeline{white-space:normal;font-weight:400;max-width:480px}details.timeline summary{cursor:pointer;color:#9fb4c6}details.timeline div{padding:2px 0}
            #tasks td:nth-child(8){white-space:pre-line;min-width:320px;max-width:520px}
            select,textarea{background:#19232c;border:1px solid #405363;border-radius:6px;padding:8px;color:inherit;font:inherit;max-width:100%;box-sizing:border-box}
            #next-run{border:1px solid #405363;border-radius:10px;padding:16px;margin:24px 0}
            #next-run label{display:flex;flex-direction:column;gap:5px;min-width:0}
            #next-run select,#next-run input{min-width:0;width:100%;box-sizing:border-box}
            #next-run fieldset{border:1px solid #405363;border-radius:8px;margin:12px 0;padding:12px;min-width:0}
            .next-fields{display:grid;grid-template-columns:2fr 1fr 1fr;gap:12px}.next-filter{margin-bottom:8px}
            #next-run pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}#next-run textarea{width:100%;min-height:90px}
            #next-run button{margin:6px 6px 6px 0}#next-run button:disabled{opacity:.5;cursor:default}
            .editor{border:1px solid #405363;border-radius:10px;padding:16px;margin:24px 0;min-width:0}
            .editor label{display:flex;flex-direction:column;gap:5px;min-width:0}.editor input,.editor select{min-width:0;width:100%;box-sizing:border-box}
            .editor fieldset{border:1px solid #405363;min-width:0;border-radius:8px;margin:12px 0}.editor pre{white-space:pre-wrap;overflow-wrap:anywhere}
            .editor button:disabled{opacity:.5;cursor:default}.editor button{margin:6px 6px 6px 0}.editor h2{margin-top:0}
            .cards{display:flex;flex-wrap:wrap;gap:12px}.card{background:#19232c;border:1px solid #30404d;border-radius:8px;padding:12px;flex:1;min-width:140px;overflow-wrap:anywhere}.card strong{display:block;font-size:21px}
            nav{display:flex;flex-wrap:wrap;gap:16px;margin:20px 0}a{color:#9ee9c6}.editor details{margin:12px 0}.editor summary{cursor:pointer}
            @media(max-width:700px){.next-fields{grid-template-columns:1fr}#next-run{padding:10px}#next-run fieldset{padding:8px}}
            {{SHELL_CSS}}</style>
            <meta name="oseer-token" content="{{TOKEN}}"><meta name="oseer-actions" content="{{ACTIONS}}">
            <meta name="oseer-settings-token" content="{{SETTINGS_TOKEN}}">
            {{SHELL_HEADER}}
            <main>
            <div class="panel" id="panel-overview" role="tabpanel" aria-labelledby="tab-overview">{{SHELL_OVERVIEW}}
            <h2>{{t:dash.awaiting_your_decision}}</h2><p class="muted">{{t:dash.the_button_opens_the_decision}}</p>
            <div class="scroll" tabindex="0"><table><thead><tr><th>{{t:dash.run_task}}</th><th>{{t:dash.question}}</th><th>{{t:dash.options}}</th><th>{{t:dash.asked}}</th><th>{{t:dash.page}}</th><th></th></tr></thead><tbody id="pending"></tbody></table><div id="pending-empty" class="empty" hidden>{{t:dash.no_open_decisions}}</div></div>
            </div>
            <div class="panel" id="panel-runs" role="tabpanel" aria-labelledby="tab-runs">{{SHELL_RUN_DETAIL}}
            <h2>{{t:dash.runs}}</h2><p class="muted">{{t:dash.each_run_has_its_own}}</p>
            <p id="dry-hidden" class="muted"></p>
            <div class="scroll" tabindex="0"><table><thead><tr><th>{{t:dash.run_task}}</th><th>{{t:dash.outcome}}</th><th>Review</th><th>{{t:dash.blockers}}</th><th>{{t:dash.calls}}</th><th>{{t:dash.cost}}</th><th>{{t:dash.fix}}</th><th>{{t:dash.decision}}</th><th>Gate</th><th>{{t:dash.timeline}}</th></tr></thead><tbody id="runs"></tbody></table><div id="runs-empty" class="empty" hidden>{{t:dash.no_runs_yet}}</div></div>
            <h2>{{t:dash.execution_attempts}}</h2><input id="filter" aria-label="{{t:dash.run_filter}}" placeholder="{{t:dash.search_by_run_role_or}}">
            <p class="muted">{{t:dash.refreshes_every_2_seconds_the}}</p>
            <div class="scroll" tabindex="0"><table><thead><tr><th>{{t:dash.run_stage}}</th><th>{{t:dash.role_profile}}</th><th>Runner</th><th>{{t:dash.requested}}</th><th>{{t:dash.confirmed_by_orca}}</th><th>{{t:dash.status_dispatch}}</th></tr></thead><tbody id="attempts"></tbody></table><div id="empty" class="empty" hidden>{{t:dash.no_attempts_yet_this_dashboard}}</div></div>
            </div>
            <div class="panel" id="panel-next" role="tabpanel" aria-labelledby="tab-next">
            <section id="next-run" aria-labelledby="next-title">
            <h2 id="next-title">{{t:dash.next_run}}</h2>
            <p class="muted">{{t:dash.choose_a_task_and_run}}</p>
            <label>{{t:dash.task}}<select id="next-task"><option value="">{{t:dash.loading_tasks}}</option></select></label>
            <p id="next-status" role="status" aria-live="polite"></p>
            <pre id="next-contract"></pre><div id="next-stages"></div>
            {{BUDGET_HTML}}
            <datalist id="next-efforts"><option value="low"><option value="medium"><option value="high"><option value="xhigh"><option value="max"></datalist>
            <p class="muted">{{t:dash.effort_outside_the_profile_is}}</p>
            <button id="next-preview" type="button" disabled>{{t:dash.check_lineup_and_budget}}</button>
            <button id="next-reset" type="button" disabled>{{t:dash.reset_selection_and_reload}}</button>
            <div id="next-result" hidden>
            <h3>Preview</h3><pre id="next-cast"></pre><pre id="next-budget"></pre><pre id="next-problems" class="bad"></pre>
            <p id="task-edit-file" class="muted"></p><pre id="task-edit-diff"></pre>
            <button id="task-apply" type="button" disabled>{{t:dash.apply_to_task_file}}</button><p id="task-save-status" role="status"></p>
            <p class="muted">{{t:dash.preview_checks_settings_before_starting}}</p>
            <label>CLI dry-run<textarea id="next-dry-command" readonly></textarea></label>
            <button type="button" data-next-copy="next-dry-command">{{t:dash.copy_dry_run}}</button>
            <label>{{t:dash.cli_run}}<textarea id="next-run-command" readonly></textarea></label>
            <button type="button" data-next-copy="next-run-command">{{t:dash.copy_run_command}}</button>
            <label>{{t:dash.task_use_merged_with_task}}<textarea id="next-yaml" readonly></textarea></label>
            <button type="button" data-next-copy="next-yaml">{{t:dash.copy_use}}</button>
            </div></section>
            <p class="muted">{{t:dash.overseer_defines_the_workflow_and}}</p>
            <h2>{{t:dash.tasks}}</h2><div class="scroll" tabindex="0"><table><thead><tr><th>{{t:dash.task}}</th><th>{{t:dash.risk}}</th><th>{{t:dash.calls}}</th><th>{{t:dash.money}}</th><th>{{t:dash.time}}</th><th>{{t:dash.fixes}}</th><th>Visual QA</th><th>{{t:dash.will_run}}</th><th>Overlay</th></tr></thead><tbody id="tasks"></tbody></table><div id="tasks-empty" class="empty" hidden>{{t:dash.no_tasks}}</div></div>
            </div>
            <div class="panel" id="panel-roles" role="tabpanel" aria-labelledby="tab-roles">
            {{MODEL_SETTINGS}}
            {{PERSONAL_HTML}}
            <h2>{{t:dash.lineup_by_stage}}</h2><p class="muted">{{t:dash.based_on_policy_before_task}} <code>use:</code> {{t:dash.can_pin_another_profile_and}} <code>review_assurance</code> {{t:dash.allow_a_same_vendor_pair}}</p>
            <p><button id="reload-config" type="button">{{t:dash.reload_settings}}</button> <span id="config-status" class="muted"></span></p>
            <div class="scroll" tabindex="0"><table><thead><tr><th>{{t:dash.candidate_vendor}}</th><th>{{t:dash.model_effort_channel}}</th><th>{{t:dash.eligibility_confirmed}}</th><th>{{t:dash.eligibility}}</th><th>{{t:dash.cost}}</th></tr></thead><tbody id="stages"></tbody></table></div>
            <h2>{{t:dash.limits}}</h2><div class="scroll" tabindex="0"><table><thead><tr><th>{{t:dash.parameter}}</th><th>{{t:dash.value}}</th><th>{{t:dash.source}}</th></tr></thead><tbody id="limits"></tbody></table></div>
            </div>
            <div class="panel" id="panel-analytics" role="tabpanel" aria-labelledby="tab-analytics">{{ANALYTICS_HTML}}</div>
            <div class="panel" id="panel-connections" role="tabpanel" aria-labelledby="tab-connections">
            <details id="api-auth-settings"><summary>{{t:conn.api_keys_and_endpoints}}</summary>{{CONNECTION_SETTINGS}}</details>
            </div>
            </main>
            <p id="problems" class="bad"></p>
            <script>
            const $=id=>document.getElementById(id); let data;
            function cell(tr,value){const td=document.createElement('td');td.textContent=value??'—';tr.append(td)}
            function table(id,rows){const body=$(id);body.replaceChildren();for(const values of rows){const tr=document.createElement('tr');values.forEach(v=>cell(tr,v));body.append(tr)}}
            function line(parent,text){const div=document.createElement('div');div.textContent=text;parent.append(div)}
            let openRuns=new Set();
            function timeline(run){const details=document.createElement('details');details.className='timeline';details.dataset.run=run.run_id??'';if(openRuns.has(details.dataset.run))details.open=true;const summary=document.createElement('summary');summary.textContent='{{t:dash.timeline}}';details.append(summary);
            for(const s of run.steps||[]){const who=[s.profile,s.vendor].filter(Boolean).join('/');let text=(s.stage||s.step||'—')+(who?' · '+who:'');text+=' · '+(s.ok===true?'ok':s.ok===false?'fail':'—')+(s.code?' / '+s.code:'');if(s.cost_usd!=null)text+=' · '+s.cost_usd;if(s.reused_from)text+=' · reused from '+s.reused_from;if(s.fix_for)text+=' · fix for '+s.fix_for;line(details,text)}
            for(const c of run.review_coverage||[]){line(details,'coverage '+(c.stage??'')+' · '+(c.role??'')+(c.assurance!=null?' · '+c.assurance:''))}
            for(const p of run.pending_stages||[]){line(details,'pending '+(p.stage??'')+' · '+(p.reason??''))}
            for(const f of run.failovers||[]){line(details,f.kind==='switched'?('failover '+(f.stage??'')+' · '+(f.from_profiles||[]).join(', ')+' → '+(f.profile??'')):f.kind==='exhausted'?('{{t:dash.quota_exhausted}} '+(f.stage??'')+' · '+(f.exhausted_profiles||[]).join(', ')):('{{t:dash.awaiting_substitution_approval}} '+(f.stage??'')+' · '+(f.from_profile??'')+' → '+(f.to_profile??'')))}
            if(run.next_step?.summary)line(details,run.next_step.summary);
            const options=run.decision?.options;if(options&&options.length)line(details,'oseer approve '+(run.run_id??'')+' --decision '+options.join('|'));
            return details}
            const token=document.querySelector('meta[name=oseer-token]').content,actions=document.querySelector('meta[name=oseer-actions]').content==='true';
            function drawPending(){const body=$('pending');body.replaceChildren();const rows=data.pending_decisions||[];
            for(const d of rows){const tr=document.createElement('tr');[d.run_id+' / '+(d.task_id??'—'),(d.kind??'—')+(d.reason?' · '+d.reason:''),(d.options||[]).join(' | '),d.created_at?new Date(d.created_at).toLocaleString():'—',d.page_open?'{{t:dash.open}}':'{{t:dash.closed_the_process_that_asked}}'].forEach(v=>cell(tr,v));
            const td=document.createElement('td');if(actions){const form=document.createElement('form');form.method='post';form.action='/decide/'+encodeURIComponent(d.run_id);
            const hidden=document.createElement('input');hidden.type='hidden';hidden.name='token';hidden.value=token;const button=document.createElement('button');button.textContent=d.page_open?'{{t:dash.open_decision}}':'{{t:dash.resume_waiting_and_open}}';form.append(hidden,button);td.append(form)}else td.textContent='oseer decide '+d.run_id;
            tr.append(td);body.append(tr)}$('pending-empty').hidden=rows.length>0}
            const reasons={profile_unverified:'{{t:dash.profile_not_verified}}',harness_coding_unproven:'{{t:dash.coding_harness_tools_not_confirmed}}',connection_unavailable:'{{t:dash.connection_missing_or_disabled}}',connection_credential_unavailable:'{{t:dash.connection_key_missing_or_unavailable}}',judge_not_read_only:'{{t:dash.reviewer_has_write_access}}',vision_capability_unverified:'{{t:dash.vision_not_verified}}',same_vendor_as_writer:'{{t:dash.same_vendor_as_the_writer}}',shares_vendor_with_writer:'{{t:dash.shares_a_vendor_with_the}}',peer_not_allowed_by_task:'{{t:dash.task_does_not_allow_a}}',quota_exhausted_this_run:'{{t:dash.quota_exhausted_in_this_run}}',executable_not_found:'{{t:dash.program_not_found}}',role_mismatch:'{{t:dash.profile_belongs_to_another_role}}',profile_not_found:'{{t:dash.profile_missing}}',role_not_configured:'{{t:dash.role_not_configured}}',no_eligible_profile:'{{t:dash.no_one_available_to_fill}}'};
            const sources={personal:'{{t:dash.personal}}',project:'{{t:dash.project}}',task:'{{t:dash.task_2}}',default:'{{t:dash.default}}'};
            function reason(code){if(!code)return '{{t:dash.eligible}}';const base=code.split(':')[0];return (reasons[base]??code)+(code.includes(':')?' ('+code.slice(base.length+1)+')':'')}
            function sourced(v){if(!v)return '—';const value=v.value==null?'{{t:conn.no}}':typeof v.value==='object'?JSON.stringify(v.value):String(v.value);return value+' · '+(sources[v.source]??v.source)}
            function plannedDispatch(d){const stage=(d.stage??'—')+' → ';if(d.skipped)return stage+'{{t:dash.skip}}';
            if(!d.profile)return stage+'{{t:dash.no_one_available}} '+Object.entries(d.unresolved||{}).map(([p,r])=>p+' '+reason(r)).join(', ');
            const model=d.model??(d.runner==='orca'?'{{t:dash.default_agent_model}}':'{{t:dash.model_not_set}}');
            return stage+d.profile+' · '+(d.vendor??'{{t:dash.vendor_not_set}}')+' · '+model+' · '+(d.effort==null?'{{t:dash.effort_not_set}}':'effort='+d.effort)+' · '+(d.runner??'{{t:dash.runner_not_set}}')+(d.from_overlay===true?' (overlay)':'')+(d.refused?' {{t:dash.will_be_rejected}} '+reason(d.refused)+')':'')}
            let nextData=null,nextGeneration=0;
            function clearNext(){nextGeneration++;clearTaskEdit();$('next-result').hidden=true;for(const id of ['next-dry-command','next-run-command','next-yaml'])$(id).value=''}
            function labelled(parent,text,control){const label=document.createElement('label');label.append(document.createTextNode(text),control);parent.append(label);return control}
            function option(select,value,text){const item=document.createElement('option');item.value=value;item.textContent=text;select.append(item)}
            function nextTasks(tasks){const select=$('next-task'),old=select.value;select.replaceChildren();for(const t of tasks.filter(t=>!t.problem))option(select,t.task,t.task);
            if(!select.options.length)option(select,'','{{t:dash.no_available_tasks}}');else if(Array.from(select.options).some(o=>o.value===old))select.value=old;return loadNext()}
            async function loadNext(){clearNext();loadPersonal(null);const generation=nextGeneration,task=$('next-task').value;nextData=null;$('next-stages').replaceChildren();$('next-contract').textContent='';
            $('next-preview').disabled=true;$('next-reset').disabled=true;if(!task){$('next-status').textContent='{{t:dash.create_a_task_through_overseer}}';return}
            $('next-status').textContent='{{t:dash.reading_task}}';try{const response=await fetch('/api/next-run?task='+encodeURIComponent(task),{cache:'no-store'}),data=await response.json();if(generation!==nextGeneration)return;
            if(!response.ok)throw Error(data.message??data.error);nextData=data;
            $('next-contract').textContent=data.goal+'\\n{{t:dash.mode}} '+(data.mode??'{{t:dash.not_set}}')+' {{t:dash.risk_2}} '+data.risk+' · scope: '+data.scope.join(', ')+'\\n{{t:dash.checks}} '+data.acceptance.join('; ')+
            '\\nVisual QA: '+(data.visual_qa?'{{t:dash.required}}':'{{t:dash.not_required}}')+' · review assurance: '+data.review_assurance+'\\nTask use: '+JSON.stringify(data.task_use);
            fillLimits(data);await loadPersonal(task);if(generation!==nextGeneration)return;
            for(const s of data.stages){const field=document.createElement('fieldset'),legend=document.createElement('legend');legend.textContent=s.stage+(s.role?' · '+s.role:'');field.append(legend);
            if(s.kind!=='ROLE'){const note=document.createElement('p');note.textContent='{{t:dash.machine_stage_conditions}} '+(s.when.join(', ')||'{{t:dash.always}}');field.append(note);$('next-stages').append(field);continue}
            field.dataset.nextStage=s.stage;const filter=document.createElement('select');filter.className='next-filter';option(filter,'','{{t:dash.all_vendors_option_filter}}');for(const v of [...new Set(s.profiles.map(p=>p.vendor))])option(filter,v,v);labelled(field,'{{t:dash.vendor_profile_filter}}',filter);
            const grid=document.createElement('div');grid.className='next-fields';field.append(grid);
            const profile=document.createElement('select');profile.dataset.nextKind='profile';profile.setAttribute('aria-label',s.stage+' {{t:dash.profile}}');
            function fillProfiles(){profile.replaceChildren();option(profile,'','{{t:dash.from_policy_no_task_pin}}');for(const p of s.profiles.filter(p=>!filter.value||p.vendor===filter.value))option(profile,p.profile,p.profile+' · '+p.vendor+' · '+(p.model??'{{t:dash.default_model}}')+' · '+(p.effort??'{{t:dash.effort_not_set}}')+' · '+p.runner+(p.verified?' {{t:dash.verified}}':' {{t:dash.not_verified}}'))}
            fillProfiles();labelled(grid,'{{t:dash.profile_model_runner}}',profile);
            const effort=document.createElement('input');effort.dataset.nextKind='effort';effort.setAttribute('list','next-efforts');effort.setAttribute('aria-label',s.stage+' effort');effort.placeholder='{{t:dash.from_profile}}';effort.maxLength=120;labelled(grid,'Effort (custom)',effort);
            const host=document.createElement('select');host.dataset.nextKind='host';host.setAttribute('aria-label',s.stage+' {{t:dash.placement}}');option(host,'','{{t:dash.from_profile}}');option(host,'orca','{{t:dash.orca_declared_profile}}');labelled(grid,'{{t:dash.placement_2}}',host);
            for(const control of [profile,effort,host]){const saved=data.task_use[control.dataset.nextKind]?.[s.stage];if(saved){if(control===profile&&!Array.from(profile.options).some(o=>o.value===saved))option(profile,saved,saved+' {{t:dash.unavailable}}');control.value=saved}}
            const note=document.createElement('p');note.className='muted';note.textContent='{{t:dash.conditions}} '+(s.when.join(', ')||'{{t:dash.always}}')+' · policy '+(s.strategy??'—')+': '+(s.fallback.join(' → ')||'{{t:dash.role_not_configured}}');field.append(note);
            filter.addEventListener('change',()=>{fillProfiles();clearNext();$('next-status').textContent='{{t:dash.filter_changed_choose_a_profile}}'});
            for(const control of [profile,effort,host])control.addEventListener('input',()=>{clearNext();$('next-status').textContent='{{t:dash.selection_changed_repeat_preview}}'});$('next-stages').append(field)}
            $('next-status').textContent='{{t:dash.task_loaded_choose_parameters_and}}';$('next-preview').disabled=false;$('next-reset').disabled=false;
            }catch(e){if(generation===nextGeneration){$('next-status').textContent='{{t:dash.could_not_read_task}} '+e.message;$('next-reset').disabled=false}}}
            $('next-task').addEventListener('change',loadNext);$('next-reset').addEventListener('click',loadNext);
            $('next-preview').addEventListener('click',previewTask);
            for(const button of document.querySelectorAll('[data-next-copy]'))button.addEventListener('click',async()=>{const output=$(button.dataset.nextCopy);if(!output.value||$('next-result').hidden)return;
            try{await navigator.clipboard.writeText(output.value);$('next-status').textContent='{{t:dash.copied}}'}catch(e){output.focus();output.select();$('next-status').textContent='{{t:dash.text_selected_use_the_keyboard}}'}});
            function drawConfig(config){const stages=$('stages');stages.replaceChildren();
            for(const s of config.stages||[]){const head=document.createElement('tr');const td=document.createElement('td');td.colSpan=5;
            let text=s.stage+(s.role?' · '+s.role:'')+' · '+(s.kind==='role'?('{{t:dash.strategy}} '+(s.strategy??'—')+(s.require_independent_vendor?' {{t:dash.independent_vendor_required}}':'')+(s.same_vendor_peer?' {{t:dash.pair}} '+s.same_vendor_peer:'')+(s.judged_against?' {{t:dash.against_writer}} '+s.judged_against.profile+' ('+s.judged_against.vendor+')':'')):'{{t:dash.machine_stage_not_filled_by}}')+(s.when&&s.when.length?' {{t:dash.when}} '+s.when.join(', '):'');
            if(s.unresolved)text+=' · '+reason(s.unresolved);td.textContent=text;td.style.fontWeight='600';head.append(td);stages.append(head);
            for(const c of s.candidates||[]){const tr=document.createElement('tr');const verdict=s.judged_against?c.rejected_after_writer:c.rejected;
            [(c.would_run?'▶ ':'')+c.profile+' · '+(c.vendor??'{{t:dash.vendor_not_set}}'),[(c.model??(c.model_note==='agent_default'?'{{t:dash.default_agent_model}}':'{{t:dash.model_not_set}}')),c.effort??'{{t:dash.effort_not_set}}',c.runner,c.connection?('{{t:dash.connection}} '+c.connection):null,c.model_vendor?('{{t:dash.model_vendor}} '+c.model_vendor):null,c.execution==='text_completion_only'?'{{t:dash.text_completion}}':null].filter(Boolean).join(' · '),c.verified?'{{t:conn.yes}}':'{{t:conn.no}}',reason(verdict)+(c.assurance&&c.assurance!=='independent'&&!verdict?' · '+c.assurance:''),c.cost_reported===false?'{{t:dash.unknown_orca_does_not_report}}':(c.max_cost_usd!=null?'{{t:dash.bound}} $'+c.max_cost_usd:'{{t:dash.as_reported_by_vendor}}')].forEach(v=>cell(tr,v));stages.append(tr)}}
            table('limits',(config.limits||[]).map(l=>[l.key,l.value==null?'—':typeof l.value==='object'?JSON.stringify(l.value):String(l.value),sources[l.source]??l.source]));
            const tasks=config.tasks||[];table('tasks',tasks.map(t=>t.problem?[t.task,'{{t:dash.error}} '+t.problem,'','','','','','','']:[t.task,sourced(t.risk),sourced(t.max_role_runs),(t.cost_cap?.value==='off'?'{{t:dash.tracking_without_a_spending_limit}}':(t.cost_cap?.value==='strict'?'{{t:dash.strict_cap}} $':'{{t:dash.threshold}} $')+(t.max_cost_usd?.value??'—')+' · '+(sources[t.max_cost_usd?.source]??'')),sourced(t.max_elapsed_minutes),sourced(t.max_fix_attempts),sourced(t.visual_qa),(t.would_run||[]).map(plannedDispatch).join('\\n')||'—',(t.overlay||[]).map(o=>o.stage+' '+o.what+'='+o.value+(o.refused?' {{t:dash.will_be_rejected}} '+reason(o.refused)+')':o.skipped?' {{t:dash.stage_skipped}}':'')).join('; ')||'—']));
            $('tasks-empty').hidden=tasks.length>0}
            async function loadConfig(){$('config-status').textContent='{{t:dash.reading}}';try{const response=await fetch('/api/config',{cache:'no-store'});if(!response.ok)throw Error('unavailable');const config=await response.json();drawConfig(config);await nextTasks(config.tasks||[]);$('config-status').textContent='{{t:dash.loaded}} '+new Date().toLocaleTimeString()}catch(e){$('config-status').textContent='{{t:dash.settings_unavailable}}'}}
            $('reload-config').addEventListener('click',loadConfig);loadConfig();
            document.addEventListener('oseer:connections-changed',loadConfig);
            document.addEventListener('oseer:config-saved',loadConfig);
            function drawRuns(){openRuns=new Set([...document.querySelectorAll('#runs details[open]')].map(d=>d.dataset.run));const body=$('runs');body.replaceChildren();const runs=data.runs||[];
            for(const r of runs){const tr=document.createElement('tr');const chain=r.chain||{},decision=r.decision||{},gate=r.orca_gate||{};
            const cost=chain.cost_usd??r.total_cost_usd,unpriced=chain.unpriced_calls??r.unpriced_calls;
            let costText=cost==null?'—':String(cost);if(unpriced)costText+=' · '+unpriced+' {{t:dash.unpriced}}';
            const outcome=(r.ok===true?'ok':r.ok===false?'{{t:conn.no}}':'—')+(r.reason?' · '+r.reason:'');
            [r.run_id+' / '+(r.task_id??'—'),outcome,r.candidate_review_passed===true?'{{t:conn.yes}}':r.candidate_review_passed===false?'{{t:conn.no}}':'—',r.open_blocking_findings,chain.role_runs??r.role_runs,costText,chain.fix_attempts??r.attempts_used,decision.state??'—',gate.gate_id??'—'].forEach(v=>cell(tr,v));
            const td=document.createElement('td');td.append(timeline(r));tr.append(td);const first=tr.firstChild,button=document.createElement('button');button.type='button';button.className='linkish';button.textContent=first.textContent;button.title='{{t:dash.open_run_timeline}}';button.addEventListener('click',()=>window.overseerOpenRun&&overseerOpenRun(r.run_id));first.replaceChildren(button);body.append(tr)}
            $('runs-empty').hidden=runs.length>0;$('dry-hidden').textContent=data.dry_runs_hidden?('{{t:dash.hidden_dry_run_entries}} '+data.dry_runs_hidden):''}
            const states={running:'{{t:dash.in_progress}}',completed:'{{t:dash.completed}}',failed:'{{t:dash.error_2}}',needs_you:'{{t:dash.answer_needed}}',dry_run:'Dry run',unknown_controller_stopped:'{{t:dash.controller_stopped_agent_unchecked}}'};
            function draw(){if(!data)return;drawPending();drawRuns();if(window.overseerShellDraw)overseerShellDraw();const q=$('filter').value.toLowerCase();const rows=data.attempts.filter(r=>JSON.stringify(r).toLowerCase().includes(q));
            table('attempts',rows.map(r=>{const l=r.launch,e=l?.effective;return [r.workflow_run_id+' / '+r.stage,r.role+' / '+r.profile,r.runner+(r.runner==='orca'&&!r.cost_known?' {{t:dash.cost_unknown}}':''),(r.model??(r.model_note==='agent_default'?'{{t:dash.default_agent_model}}':'{{t:dash.default}}'))+' · '+(r.effort_requested??'{{t:dash.effort_not_set}}'),l?.status==='matched'?(e?.model??'{{t:dash.not_set_2}}')+' · '+(e?.effort??'{{t:dash.effort_unknown}}'):(l?.status??'{{t:dash.unconfirmed}}'),(states[r.view_state]??r.view_state)+' / '+(r.orca_dispatch_id??'—')+(r.agent_blocked_on?' {{t:dash.did_not_start}} '+(r.agent_blocked_on==='folder_trust'?'{{t:dash.folder_trust_prompt}}':r.agent_blocked_on==='cli_update_prompt'?'{{t:dash.cli_update_prompt}}':r.agent_blocked_on)+(r.resolution?' — '+r.resolution:''):'')]}));$('empty').hidden=rows.length>0;
            $('path').textContent=data.project+' · '+data.config_home;$('problems').textContent=Object.entries(data.problems).map(([k,v])=>k+': '+v).join('; ')}
            $('filter').addEventListener('input',draw);
            async function refresh(){try{const response=await fetch('/api/state',{cache:'no-store'});if(!response.ok)throw Error('unavailable');data=await response.json();draw();$('connection').textContent='{{t:dash.data_updated}} '+new Date(data.observed_at).toLocaleTimeString();$('connection').className=''}catch(e){$('connection').textContent='{{t:dash.disconnected_showing_last_received_data}}';$('connection').className='bad'}finally{setTimeout(refresh,2000)}}refresh();
            {{SETTINGS_SCRIPT}}
            {{SHELL_SCRIPT}}
            </script></html>
            """.replace("{{BUDGET_HTML}}", DashboardControls.BUDGET_HTML)
            .replace("{{PERSONAL_HTML}}", DashboardControls.HTML.substring(0, DashboardControls.HTML.indexOf("<section id=\"analytics\"")))
            .replace("{{ANALYTICS_HTML}}", DashboardControls.HTML.substring(DashboardControls.HTML.indexOf("<section id=\"analytics\"")))
            .replace("{{SHELL_CSS}}", DashboardShell.CSS)
            .replace("{{SHELL_HEADER}}", DashboardShell.HEADER)
            .replace("{{SHELL_OVERVIEW}}", DashboardShell.OVERVIEW)
            .replace("{{SHELL_RUN_DETAIL}}", DashboardShell.RUN_DETAIL)
            .replace("{{SETTINGS_SCRIPT}}", DashboardControls.SCRIPT)
            .replace("{{SHELL_SCRIPT}}", DashboardShell.SCRIPT);
}
