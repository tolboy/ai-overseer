package dev.oseer.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.oseer.BundledResources;
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
import java.nio.file.Path;
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
        return DashboardState.snapshot(project, user);
    }

    public static List<Map<String, Object>> failovers(Object steps) {
        return DashboardState.failovers(steps);
    }

    public static Map<String, Object> publicAttempt(Map<String, Object> source) {
        return DashboardState.attemptView(source);
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
