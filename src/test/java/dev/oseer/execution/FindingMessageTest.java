package dev.oseer.execution;

import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A complete finding that only lacks {@code message} keeps the vendor's own words. */
public final class FindingMessageTest implements Suite {
    @Override public String name() { return "finding-message"; }

    @Override public void run(Check check) {
        Map<String, Object> withActual = finding(Map.of("severity", "P1", "path", "a.kt",
                "scenario", "s", "expected", "e", "actual", "the waiter settles early"));
        Map<String, Object> scenarioOnly = finding(Map.of("severity", "P2", "path", "b.md", "scenario", "only s"));
        Map<String, Object> kept = finding(Map.of("severity", "P3", "path", "c", "message", "as filed", "actual", "x"));
        Map<String, Object> empty = finding(Map.of("severity", "P3", "path", "d"));
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("findings", new ArrayList<>(List.of(withActual, scenarioOnly, kept, empty)));

        check.eq("two messages were filled in", 2, DirectCliExecutor.deriveFindingMessages(artifact));
        check.eq("actual is preferred", "the waiter settles early", withActual.get("message"));
        check.eq("scenario when there is no actual", "only s", scenarioOnly.get("message"));
        check.eq("a filed message is never replaced", "as filed", kept.get("message"));
        check.eq("nothing is invented", null, empty.get("message"));
        check.eq("an artifact without findings is untouched", 0,
                DirectCliExecutor.deriveFindingMessages(new LinkedHashMap<>(Map.of("verdict", "pass"))));
        check.eq("a null artifact is safe", 0, DirectCliExecutor.deriveFindingMessages(null));
    }

    private static Map<String, Object> finding(Map<String, Object> fields) {
        return new LinkedHashMap<>(fields);
    }
}
