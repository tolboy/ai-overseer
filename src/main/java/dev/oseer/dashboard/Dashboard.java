package dev.oseer.dashboard;

import com.sun.net.httpserver.HttpServer;
import dev.oseer.BundledResources;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private volatile RunLauncher launcher;
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
        requests = Executors.newFixedThreadPool(8, task -> {
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
                    && (path.startsWith("/api/connections/") || path.startsWith("/api/models/")
                        || path.startsWith("/api/launch/"))) {
                String suppliedToken = exchange.getRequestHeaders().getFirst("X-Overseer-Token");
                String suppliedType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (suppliedToken == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {
                    status = 403; body = Json.write(Map.of("ok", false, "code", "settings_token_invalid"));
                } else if (suppliedType == null || !"application/json".equalsIgnoreCase(suppliedType.split(";", 2)[0].trim())) {
                    status = 415; body = Json.write(Map.of("ok", false, "code", "settings_json_required"));
                } else {
                    int bodyLimit="/api/models/save-jev-policy".equals(path)||path.startsWith("/api/launch/")?160*1024:MAX_SETTINGS_BODY;
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
                            if (path.startsWith("/api/launch/")) {
                                RunLauncher runs = launcher;
                                if (runs == null) {
                                    status = 404; body = Json.write(Map.of("ok", false, "code", "launcher_disabled"));
                                } else try {
                                    body = Json.write(switch (path) {
                                        case "/api/launch/inspect" -> runs.inspect(payload);
                                        case "/api/launch/preview" -> runs.preview(payload);
                                        case "/api/launch/start" -> runs.start(payload);
                                        case "/api/launch/answer" -> runs.answer(payload);
                                        case "/api/launch/land" -> runs.land(payload);
                                        case "/api/launch/cancel" -> runs.cancel(payload);
                                        case "/api/launch/attach" -> runs.attach(payload);
                                        default -> throw new RunLauncher.Refused(404, "launch_unknown", "no such action");
                                    });
                                } catch (RunLauncher.Refused refused) {
                                    status = refused.status(); body = Json.write(refused.toMap());
                                }
                            } else if (path.startsWith("/api/models/")) {
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
                            case "/api/settings/preview" -> settings.preview(request, Messages.language(exchange));
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
            } else if ("/api/launch/jobs".equals(path)) {
                RunLauncher runs = launcher;
                body = Json.write(Map.of("enabled", runs != null, "jobs", runs == null ? List.of() : runs.jobs()));
            } else if (path.startsWith("/api/launch/jobs/")) {
                RunLauncher runs = launcher;
                try {
                    if (runs == null) throw new RunLauncher.Refused(404, "launcher_disabled", "the launcher is off");
                    body = Json.write(runs.job(path.substring("/api/launch/jobs/".length())));
                } catch (RunLauncher.Refused refused) { status = refused.status(); body = Json.write(refused.toMap()); }
            } else if (path.startsWith("/api/runs/")) {
                // A launcher job names its own project; any other project is not readable here.
                Path runProject = jobProject(exchange, project);
                try {
                    if (runProject == null) throw new RunTimeline.NotFound();
                    body = Json.write(RunTimeline.read(runProject, path.substring("/api/runs/".length())));
                }
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
                        Path runProject = jobProject(exchange, project);
                        if (runProject == null) throw new RunTimeline.NotFound();
                        RunTimeline.Evidence evidence = RunTimeline.evidence(runProject, parts[0], parts[1], parts[2]);
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
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'");
            if (binary == null && contentType.startsWith("text/html")) body = Messages.render(body, Messages.language(exchange));
            byte[] bytes = binary != null ? binary : body.getBytes(StandardCharsets.UTF_8);
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
    @Override public void close() {
        server.stop(0);
        requests.shutdownNow();
        RunLauncher runs = launcher;
        if (runs != null) runs.close();
    }

    /**
     * Lets the browser start, continue and cancel runs. Off unless the caller turns it on:
     * {@code oseer dashboard} does; an embedded dashboard stays read-only.
     */
    public Dashboard enableLauncher(RunLauncher runs) {
        this.launcher = runs;
        return this;
    }

    /** {@code ?job=} reads the timeline of a launcher job's project; nothing else widens it. */
    private Path jobProject(HttpExchange exchange, Path project) {
        String raw = exchange.getRequestURI().getRawQuery();
        String job = form(raw == null ? "" : raw).get("job");
        if (job == null || job.isBlank()) return project;
        RunLauncher runs = launcher;
        return runs == null ? null : runs.projectOf(job);
    }

    public static Map<String, Object> snapshot(Path project, UserConfig user) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("project", project.toAbsolutePath().normalize().toString());
        state.put("config_home", user.home().toString());
        state.put("observed_at", Instant.now().toString());
        state.put("problems", user.problems());
        try {
            state.put("credential_warnings", dev.oseer.execution.CredentialAudit.unresolved(
                    user, new dev.oseer.execution.CredentialResolver()));
        } catch (RuntimeException unreadable) {
            state.put("credential_warnings", List.of());
        }
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
                    if (!Files.exists(summaryFile, LinkOption.NOFOLLOW_LINKS)
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
                                else runRows.add(stampStart(publicRun(source, summaryFile, directory, runsRoot), directory));
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
        // A run that has not written task-run.json is still a run. Leaving it out of this
        // list is why an in-progress review showed up only under execution attempts.
        foldOpenRuns(runRows, openRuns, attempts);
        runRows.sort(Comparator.comparing(Dashboard::sortTime).reversed());
        // Projection only: decision.json stays pending, so a person can still answer it.
        // The list stops counting a question a later run of the same tree has overtaken.
        markSuperseded(runRows);
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
    static boolean controllerAlive(Map<String, Object> source) {
        if (!(source.get("controller_pid") instanceof Number pid)) return false;
        var handle = ProcessHandle.of(pid.longValue()).filter(ProcessHandle::isAlive);
        if (handle.isEmpty()) return false;
        if (!(source.get("controller_started_at") instanceof String recorded) || recorded.isBlank()) return false;
        var observed = handle.get().info().startInstant();
        if (observed.isEmpty()) return true;
        try {
            long skew = Math.abs(java.time.Duration.between(Instant.parse(recorded), observed.get()).toMillis());
            return skew <= 2_000;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static Map<String, Object> stampStart(Map<String, Object> row, Path directory) {
        Path reservation = directory.resolve("run.json");
        try {
            if (Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS) && Files.size(reservation) <= 64 * 1024) {
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
                if (Files.isRegularFile(summary, LinkOption.NOFOLLOW_LINKS) && Files.size(summary) <= 2L * 1024 * 1024
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
                if (Files.isRegularFile(reservation, LinkOption.NOFOLLOW_LINKS) && Files.size(reservation) <= 64 * 1024
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

    private static final String HTML = BundledResources.readText(
            "/dev/oseer/dashboard/dashboard.html")
            .replace("{{BUDGET_HTML}}", DashboardControls.BUDGET_HTML)
            .replace("{{PERSONAL_HTML}}", DashboardControls.HTML.substring(0, DashboardControls.HTML.indexOf("<section id=\"analytics\"")))
            .replace("{{ANALYTICS_HTML}}", DashboardControls.HTML.substring(DashboardControls.HTML.indexOf("<section id=\"analytics\"")))
            .replace("{{SHELL_CSS}}", DashboardShell.CSS)
            .replace("{{SHELL_HEADER}}", DashboardShell.HEADER)
            .replace("{{SHELL_OVERVIEW}}", DashboardShell.OVERVIEW)
            .replace("{{SHELL_RUN_DETAIL}}", DashboardShell.RUN_DETAIL)
            .replace("{{SETTINGS_SCRIPT}}", DashboardControls.SCRIPT)
            .replace("{{SHELL_SCRIPT}}", DashboardShell.SCRIPT)
            .replace("{{LAUNCH_CSS}}", RunLauncherUi.CSS)
            .replace("{{LAUNCH_HTML}}", RunLauncherUi.HTML)
            .replace("{{LAUNCH_SCRIPT}}", RunLauncherUi.SCRIPT);
}
