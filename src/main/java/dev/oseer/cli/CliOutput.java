package dev.oseer.cli;

import dev.oseer.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

final class CliOutput {
    private CliOutput() {}

    static void json(Object value) {
        System.out.println(Json.write(value));
    }

    static int report(Map<String, Object> report) {
        json(report);
        return Boolean.TRUE.equals(report.get("ok")) ? 0 : 1;
    }

    static int unexpectedFailure(Exception failure) {
        Failure error = new Failure("oseer_error", String.valueOf(failure.getMessage()));
        System.err.println(Json.write(error.toJson()));
        return 1;
    }

    record Failure(String code, String message) {
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("ok", false);
            json.put("code", code);
            json.put("message", message);
            return json;
        }
    }
}
