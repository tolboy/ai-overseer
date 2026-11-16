package dev.oseer.execution;

import dev.oseer.config.Connection;
import dev.oseer.config.Profile;
import dev.oseer.config.UserConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which credential references the profiles a run can reach cannot resolve in this process.
 *
 * A run started from the browser inherits the server's environment, so a server started
 * without the launcher that exports the API keys starts runs that fail at the first API
 * call (`model_check_failed`). This names the references — `env:NAME` or the OS-store entry —
 * and the connections and profiles that need them. It never returns, logs or compares a
 * credential value: {@link CredentialResolver#status} reads presence only.
 */
public final class CredentialAudit {
    private CredentialAudit() {}

    public static List<Map<String, Object>> unresolved(UserConfig user, CredentialResolver resolver) {
        Set<String> reachable = new LinkedHashSet<>();
        if (user.policy() != null) user.policy().roles().values().forEach(spec -> reachable.addAll(spec.profiles()));
        Map<String, List<String>> usersByConnection = new LinkedHashMap<>();
        for (String name : reachable) {
            Profile profile = user.profiles().get(name);
            if (profile == null || profile.connectionRef() == null) continue;
            usersByConnection.computeIfAbsent(profile.connectionRef(), id -> new ArrayList<>()).add(name);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var entry : usersByConnection.entrySet()) {
            Connection connection = user.connections().get(entry.getKey());
            if (connection == null || !connection.enabled() || connection.credentialRef() == null) continue;
            String state = String.valueOf(resolver.status(connection).get("state"));
            if ("ready".equals(state)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("connection", connection.id());
            row.put("credential_ref", connection.credentialRef());
            row.put("state", state);
            row.put("profiles", List.copyOf(entry.getValue()));
            rows.add(row);
        }
        return rows;
    }
}
