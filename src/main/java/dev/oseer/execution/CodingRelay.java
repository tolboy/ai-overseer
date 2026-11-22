package dev.oseer.execution;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.oseer.config.Connection;
import dev.oseer.json.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Run-scoped, bounded observer for explicit coding probes. Never gives the agent an upstream key.
 */
final class CodingRelay implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers =
            Executors.newSingleThreadExecutor();
    private final Connection connection;
    private final String model, secret, bearer;
    private final long deadline;
    private final int maxRequests, maxOutputTokens;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final List<Map<String, Object>> receipts = new ArrayList<>();
    private final Map<String, String> calls = new HashMap<>();
    private final Set<String> observedTools = new TreeSet<>();
    private final List<Map<String, Object>> upstreamAttempts = new ArrayList<>();
    private final String effort;
    private final Map<String,Object> effortReceipt = new LinkedHashMap<>();
    private boolean allEffortsReported = true;
    private String failure;
    private Map<String, Object> failureDetail;
    private int turns;
    private volatile Map<String, Object> latestEvidence;
    /** Pause between resends of a request that never left; replaceable by tests. */
    ApiRetry.Sleeper sleeper = ApiRetry.REAL_SLEEP;

    /**
     * Upper bound for one upstream turn. JoinGonka answered a bare ping in about 30 s on
     * 2026-10-28 and a coding turn with the tool schema took longer than the earlier 45 s cap,
     * so the probe and every OpenCode dispatch on it died on its first real turn. The
     * dispatch's own time budget still bounds the whole relay.
     */
    static final Duration TURN_CAP = Duration.ofSeconds(120);

    /**
     * Whole-relay ceiling. Four minutes fit OpenRouter-speed gateways but not JoinGonka, whose
     * nonstreaming 2048-token coding turns ran past two minutes on 2026-10-28. The profile's own
     * wall clock still tightens it.
     */
    static final Duration MAX_BUDGET = Duration.ofMinutes(12);

    static Duration budget(long wallClockMinutes) {
        return Duration.ofMinutes(Math.max(1, Math.min(MAX_BUDGET.toMinutes(), wallClockMinutes)));
    }

    static Duration turnDeadline(long remainingNanos) {
        return Duration.ofNanos(Math.max(0, Math.min(TURN_CAP.toNanos(), remainingNanos)));
    }

    CodingRelay(Connection connection, String model, String secret, Duration limit)
            throws IOException {
        this(connection, model, secret, limit, 8, 2048);
    }

    CodingRelay(
            Connection connection,
            String model,
            String secret,
            Duration limit,
            int maxRequests,
            int maxOutputTokens)
            throws IOException {
        this(connection, model, secret, limit, maxRequests, maxOutputTokens, null);
    }

    CodingRelay(Connection connection, String model, String secret, Duration limit,
                int maxRequests, int maxOutputTokens, String effort) throws IOException {
        if (maxRequests < 1 || maxRequests > dev.oseer.config.CodingAttestation.REQUEST_CEILING
                || maxOutputTokens < 1 || maxOutputTokens > dev.oseer.config.CodingAttestation.OUTPUT_CEILING)
            throw new IllegalArgumentException("unsupported coding relay limits");
        this.maxRequests = maxRequests;
        this.maxOutputTokens = maxOutputTokens;
        this.connection = connection;
        this.model = model;
        this.secret = secret;
        this.effort = effort;
        ApiEffort.initialize(effort, effortReceipt);
        deadline = System.nanoTime() + limit.toNanos();
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        bearer = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        latestEvidence = snapshot(false);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    String bearer() {
        return bearer;
    }

    Map<String, Object> evidence() {
        return latestEvidence;
    }

    // The single request worker owns the counters. Other threads read a published copy.
    private Map<String, Object> snapshot(boolean handling) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", turns);
        result.put("responses", List.copyOf(receipts));
        result.put("observed_tool_results", List.copyOf(observedTools));
        result.put(
                "identity_confirmed",
                !handling && failure == null && !receipts.isEmpty() && receipts.size() == turns);
        result.put("cost_status", "provider_usage_recorded_no_strict_cap");
        result.put("upstream_attempts", List.copyOf(upstreamAttempts));
        ApiEffort.copy(effortReceipt, result);
        if (failure != null && !"probe_served_effort_mismatch".equals(failure)) {
            // Earlier confirmed responses do not confirm the failed/incomplete dispatch.
            result.put("effort_reported", null);
            result.put("effort_verification", "not_reported_by_provider");
        }
        if (failure != null) result.put("failure", failure);
        if (failureDetail != null) result.put("failure_detail", failureDetail);
        return sanitizedMap(result);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Reply reply;
            if (!authorized(exchange)) {
                reply = Reply.empty(403);
            } else if (!"POST".equals(exchange.getRequestMethod())
                    || !"/v1/chat/completions".equals(exchange.getRequestURI().getRawPath())
                    || exchange.getRequestURI().getRawQuery() != null) {
                reply = Reply.empty(404);
            } else {
                reply = dispatch(exchange);
            }
            send(exchange, reply.status(), reply.contentType(), reply.body());
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String authority = "127.0.0.1:" + server.getAddress().getPort();
        String supplied = exchange.getRequestHeaders().getFirst("Authorization");
        return authority.equals(exchange.getRequestHeaders().getFirst("Host"))
                && exchange.getRequestHeaders().getFirst("Origin") == null
                && supplied != null
                && MessageDigest.isEqual(("Bearer " + bearer).getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8));
    }

    private Reply dispatch(HttpExchange exchange) throws IOException {
        latestEvidence = snapshot(true);
        try {
            return forward(exchange);
        } catch (ApiExecutor.CallFailure failed) {
            failure = failed.code;
            failureDetail = new LinkedHashMap<>();
            failed.describe(failureDetail);
            return Reply.empty(502);
        } catch (RuntimeException | java.nio.charset.CharacterCodingException invalid) {
            failure = "probe_relay_protocol";
            return Reply.empty(502);
        } finally {
            latestEvidence = snapshot(false);
        }
    }

    private Reply forward(HttpExchange exchange) throws IOException, ApiExecutor.CallFailure {
        if (failure != null || turns >= maxRequests || System.nanoTime() >= deadline) {
            if (failure == null) failure = turns >= maxRequests ? "probe_turn_limit" : "probe_time_limit";
            // This dispatch cannot resume. A retryable status would make the SDK wait
            // and would obscure the first upstream failure without permitting progress.
            return new Reply(400, "application/json", Json.write(Map.of("error", Map.of(
                    "message", "Overseer coding probe stopped",
                    "type", "invalid_request_error",
                    "code", failure))));
        }
        byte[] raw = exchange.getRequestBody().readNBytes(512 * 1024 + 1);
        if (raw.length > 512 * 1024) {
            failure = "probe_request_too_large";
            return Reply.empty(413);
        }
        Map<String, Object> body =
                Json.parseObject(
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(raw))
                                .toString());
        if (!model.equals(body.get("model"))) {
            failure = "probe_requested_model_mismatch";
            return Reply.empty(400);
        }
        noteToolResults(body.get("messages"));
        body = project(body);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            failure = "probe_deadline";
            return Reply.empty(504);
        }
        turns++;
        effortReceipt.put("effort_reported", null);
        effortReceipt.put("effort_verification", "not_reported_by_provider");
        latestEvidence = snapshot(true);
        // Resent only when the request provably never left this machine. OpenCode applies
        // a tool call only from a response this relay forwarded, and nothing is forwarded
        // until one upstream attempt answered completely, so a resend can never deliver
        // the same edit twice. An unknown outcome (dropped after sending, a 5xx, a
        // deadline) is not resent: it may already have been billed.
        ApiRetry.Outcome sent =
                ApiRetry.send(
                        connection,
                        "POST",
                        URI.create(connection.endpoint()),
                        Json.write(body),
                        secret,
                        turnDeadline(remaining),
                        ApiRetry.Policy.NOT_SENT_ONLY,
                        sleeper,
                        ApiRetry.correlation(null, null, "implementer", "relay-turn-" + turns));
        upstreamAttempts.addAll(sent.attempts());
        ApiEffort.sent(effort, sent.reply() != null, sent.nothingSent(), effortReceipt);
        if (sent.failure() != null) throw sent.failure();
        ApiExecutor.HttpReply reply = sent.reply();
        if (reply.status() != 200) {
            failure = "probe_upstream_http_" + reply.status();
            return Reply.empty(502);
        }
        return checkedReply(reply.body());
    }

    private Reply checkedReply(String body) {
        // Identity is checked before OpenCode receives content or a tool request.
        Map<String, Object> parsed = Json.parseObject(body);
        if (containsCredential(parsed)) {
            failure = "probe_credential_reflected";
            return Reply.empty(502);
        }
        if (!model.equals(parsed.get("model"))) {
            failure = "probe_served_model_mismatch";
            return Reply.empty(502);
        }
        var turnEffort = new LinkedHashMap<String,Object>();
        ApiEffort.initialize(effort, turnEffort);
        if (!ApiEffort.reported(effort, parsed, turnEffort)) {
            ApiEffort.copy(turnEffort, effortReceipt);
            ApiEffort.sent(effort, true, false, effortReceipt);
            failure = "probe_served_effort_mismatch";
            return Reply.empty(502);
        }
        allEffortsReported &= turnEffort.get("effort_reported") != null;
        if (allEffortsReported) {
            effortReceipt.put("effort_reported", turnEffort.get("effort_reported"));
            effortReceipt.put("effort_verification", turnEffort.get("effort_verification"));
        } else {
            effortReceipt.put("effort_reported", null);
            effortReceipt.put("effort_verification", "not_reported_by_provider");
        }
        if (!(parsed.get("choices") instanceof List<?> choices)
                || choices.size() != 1
                || !(choices.getFirst() instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            failure = "probe_upstream_protocol";
            return Reply.empty(502);
        }
        String finish = String.valueOf(choice.get("finish_reason"));
        if (!Set.of("stop", "tool_calls").contains(finish)) {
            failure = "probe_incomplete_response";
            return Reply.empty(502);
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("model", model);
        receipt.put("finish_reason", finish);
        receipt.put("id", parsed.get("id"));
        receipt.put("usage", parsed.get("usage"));
        ApiEffort.sent(effort, true, false, turnEffort);
        ApiEffort.copy(turnEffort, receipt);
        receipts.add(receipt);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        if (message.get("content") instanceof String content) delta.put("content", content);
        if (message.get("tool_calls") instanceof List<?> tools) {
            List<Map<String, Object>> indexed = new ArrayList<>();
            for (int index = 0; index < tools.size(); index++) {
                if (!(tools.get(index) instanceof Map<?, ?> tool)
                        || !(tool.get("function") instanceof Map<?, ?> function))
                    throw new IllegalArgumentException();
                Map<String, Object> copy = new LinkedHashMap<>();
                tool.forEach((k, v) -> copy.put(String.valueOf(k), v));
                copy.put("index", index);
                indexed.add(copy);
                if (tool.get("id") instanceof String id
                        && function.get("name") instanceof String name) calls.put(id, name);
            }
            delta.put("tool_calls", indexed);
        }
        String id = parsed.get("id") instanceof String value ? value : "oseer-probe";
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", System.currentTimeMillis() / 1000);
        chunk.put("model", model);
        chunk.put("choices", List.of(Map.of("index", 0, "delta", delta)));
        String first = "data: " + Json.write(chunk) + "\n\n";
        chunk.put(
                "choices",
                List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", finish)));
        if (parsed.get("usage") != null) chunk.put("usage", parsed.get("usage"));
        String response = first + "data: " + Json.write(chunk) + "\n\ndata: [DONE]\n\n";
        if (secret != null && response.contains(secret)) {
            failure = "probe_credential_reflected";
            return Reply.empty(502);
        }
        return new Reply(200, "text/event-stream", response);
    }

    private Map<String, Object> project(Map<String, Object> body) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String field :
                List.of(
                        "messages",
                        "tools",
                        "tool_choice",
                        "parallel_tool_calls",
                        "temperature",
                        "top_p",
                        "frequency_penalty",
                        "presence_penalty",
                        "stop",
                        "seed",
                        "response_format"))
            if (body.containsKey(field)) projected.put(field, body.get(field));
        // A coding dispatch must start by using the offered tools. Metadata/title
        // requests have no tools and remain ordinary text completions.
        if (observedTools.isEmpty()
                && projected.get("tools") instanceof List<?> tools
                && !tools.isEmpty()) projected.put("tool_choice", "required");
        projected.put("model", model);
        projected.put("n", 1);
        projected.put("stream", false);
        projected.put("max_tokens", maxOutputTokens);
        // Overseer owns this setting. Harness-provided reasoning fields were not projected.
        ApiEffort.apply(effort, projected);
        if ("openrouter".equals(connection.provider()))
            projected.put("provider", Map.of("require_parameters", true, "allow_fallbacks", false));
        return projected;
    }

    private record Reply(int status, String contentType, String body) {
        static Reply empty(int status) {
            return new Reply(status, "application/json", "{}");
        }
    }

    private void noteToolResults(Object value) {
        if (!(value instanceof List<?> messages)) return;
        for (Object item : messages)
            if (item instanceof Map<?, ?> message && "tool".equals(message.get("role"))) {
                String name = calls.get(message.get("tool_call_id"));
                if (name != null) observedTools.add(name);
            }
    }

    private boolean containsCredential(Object value) {
        if (value instanceof String text)
            return secret != null && text.contains(secret) || text.contains(bearer);
        if (value instanceof Map<?, ?> map)
            return map.entrySet().stream()
                    .anyMatch(
                            entry ->
                                    containsCredential(entry.getKey())
                                            || containsCredential(entry.getValue()));
        if (value instanceof List<?> list) return list.stream().anyMatch(this::containsCredential);
        return false;
    }

    private Map<String, Object> sanitizedMap(Map<?, ?> input) {
        Map<String, Object> clean = new LinkedHashMap<>();
        input.forEach((key, value) -> clean.put(cleanText(String.valueOf(key)), sanitized(value)));
        return Collections.unmodifiableMap(clean);
    }

    private Object sanitized(Object value) {
        if (value instanceof String text) return cleanText(text);
        if (value instanceof Map<?, ?> map) return sanitizedMap(map);
        if (value instanceof List<?> list) return list.stream().map(this::sanitized).toList();
        return value;
    }

    private String cleanText(String text) {
        if (secret != null) text = text.replace(secret, "[REDACTED]");
        return text.replace(bearer, "[REDACTED]");
    }

    private static void send(HttpExchange exchange, int code, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        workers.shutdownNow();
    }
}
