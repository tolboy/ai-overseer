package dev.oseer.gate;

import dev.oseer.config.RepoPath;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Diagnostic evidence about check output. Gate decisions never use these labels. */
public final class CheckDiagnostics {
    private CheckDiagnostics() {}

    private static final int MAX_FAILURES = 64;
    private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern PYTEST = Pattern.compile("^FAILED (.+?\\.py)::(.+)$");
    private static final Pattern GRADLE = Pattern.compile("^([\\w.$]+) > (.+?) FAILED$");
    private static final Pattern SUREFIRE = Pattern.compile("^(?:\\[ERROR] )?([\\w.$]+\\.[\\w$]+(?:\\[[^\\]]+])?)"
            + " -- Time elapsed: [^<>]+ <<< (?:FAILURE|ERROR)!$");

    /**
     * Resolve names from recognized test failure records only. Counts, a shell exit code,
     * a stack trace, and incomplete output cannot identify a test.
     */
    public static List<String> failedTests(Map<?, ?> command) {
        if (!failed(command) || Boolean.TRUE.equals(command.get("stdout_truncated"))
                || Boolean.TRUE.equals(command.get("stderr_truncated"))) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (String stream : List.of("stdout", "stderr")) {
            if (!(command.get(stream) instanceof String output)) continue;
            for (String raw : output.split("\\R")) {
                if (raw.length() > 1_024) continue;
                String id = testId(ANSI.matcher(raw).replaceAll("").strip());
                if (id == null || id.length() > 256 || id.codePoints().anyMatch(Character::isISOControl)) continue;
                ids.add(id);
                if (ids.size() > MAX_FAILURES) return List.of();
            }
        }
        return ids.stream().sorted().toList();
    }

    private static String testId(String line) {
        Matcher pytest = PYTEST.matcher(line);
        if (pytest.matches()) return pytestId(pytest);
        Matcher gradle = GRADLE.matcher(line);
        if (gradle.matches()) return gradle.group(1) + "#" + gradle.group(2);
        Matcher surefire = SUREFIRE.matcher(line);
        if (!surefire.matches()) return null;
        String method = surefire.group(1);
        int parameter = method.indexOf('[');
        int boundary = method.lastIndexOf('.', parameter < 0 ? method.length() : parameter);
        return method.substring(0, boundary) + "#" + method.substring(boundary + 1);
    }

    private static String pytestId(Matcher summary) {
        String path = RepoPath.normalize(summary.group(1));
        if (path == null) return null;
        String node = summary.group(2);
        int depth = 0;
        for (int index = 0; index < node.length(); index++) {
            // Pytest appends assertion text after this separator. A parameter ID can contain it too.
            if (depth == 0 && node.startsWith(" - ", index)) {
                node = node.substring(0, index);
                break;
            }
            char character = node.charAt(index);
            if (character == '[') depth++;
            if (character == ']' && --depth < 0) return null;
        }
        return depth == 0 && !node.isBlank() ? path + "::" + node : null;
    }

    private static boolean failed(Map<?, ?> command) {
        return command.get("exit_code") instanceof Number exit && exit.longValue() > 0
                && !Boolean.TRUE.equals(command.get("timed_out"))
                && !Boolean.TRUE.equals(command.get("stalled"))
                && !Boolean.TRUE.equals(command.get("output_limit_exceeded"))
                && !Boolean.TRUE.equals(command.get("environment_error"));
    }

    private static boolean passed(Map<?, ?> command) {
        return command.get("exit_code") instanceof Number exit && exit.longValue() == 0
                && !Boolean.TRUE.equals(command.get("timed_out"))
                && !Boolean.TRUE.equals(command.get("stalled"))
                && !Boolean.TRUE.equals(command.get("output_limit_exceeded"));
    }

    /** Select only attribution inputs, leaving stdout and stderr on the original evidence. */
    public static Map<String, Object> observation(Map<?, ?> command, Map<?, ?> report) {
        Map<String, Object> observed = new LinkedHashMap<>();
        for (String key : List.of("command", "exit_code", "timed_out", "duration_ms", "stalled", "output_limit_exceeded")) {
            if (command.get(key) instanceof Boolean || command.get(key) instanceof Number
                    || command.get(key) instanceof String) observed.put(key, command.get(key));
        }
        observed.put("ok", passed(command));
        boolean environment = "gate_environment_error".equals(report.get("code"))
                && command.get("command") instanceof String name && name.equals(report.get("environment_command"));
        boolean mutated = Set.of("contract_mutated", "baseline_mutated_source", "blast_radius_after_command")
                .contains(String.valueOf(report.get("code")));
        if (environment) observed.put("environment_error", true);
        if (mutated) observed.put("observation_invalid", true);
        if (!environment && !mutated) {
            List<String> tests = failedTests(command);
            if (!tests.isEmpty()) observed.put("failed_tests", tests);
        }
        if (command.get("diagnostic_retry") instanceof Map<?, ?> retry) {
            if (Boolean.TRUE.equals(retry.get("comparable")) && passed(retry)) observed.put("flaky_observed", true);
            if (retry.get("duration_ms") instanceof Number duration) observed.put("diagnostic_duration_ms", duration.longValue());
        }
        return observed;
    }

    public static void attribute(Map<String, Object> observed, List<?> baseline, boolean baselineStage) {
        if (Boolean.TRUE.equals(observed.get("ok"))) return;
        if (!failed(observed) || Boolean.TRUE.equals(observed.get("observation_invalid"))) {
            observed.put("attribution", "unknown");
            return;
        }
        Object command = observed.get("command");
        Map<?, ?> before = null;
        if (!baselineStage && command instanceof String) {
            for (Object item : baseline) if (item instanceof Map<?, ?> attempt && command.equals(attempt.get("command"))) {
                if (before != null) {
                    before = null; // Ambiguous receipts cannot identify a before state.
                    break;
                }
                before = attempt;
            }
        }
        String origin = attribution(observed, before, baselineStage);
        observed.put("attribution", Boolean.TRUE.equals(observed.get("flaky_observed")) ? "flaky" : origin);
    }

    private static String attribution(Map<String, Object> current, Map<?, ?> before, boolean baselineStage) {
        if (baselineStage) return "pre_existing";
        if (before == null) return "no_baseline";
        if (Boolean.TRUE.equals(before.get("flaky_observed"))) return "unknown";
        if (passed(before)) return "regression";
        if (!failed(before) || !(before.get("failed_tests") instanceof List<?> old) || old.isEmpty()
                || !(current.get("failed_tests") instanceof List<?> now) || now.isEmpty()) return "unknown";
        var newFailures = now.stream().filter(id -> !old.contains(id)).toList();
        var existing = now.stream().filter(old::contains).toList();
        if (!existing.isEmpty()) current.put("pre_existing_failed_tests", existing);
        if (newFailures.isEmpty()) return "pre_existing";
        current.put("new_failed_tests", newFailures);
        return "regression";
    }
}
