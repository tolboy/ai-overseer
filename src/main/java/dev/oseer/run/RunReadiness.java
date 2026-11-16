package dev.oseer.run;

import dev.oseer.config.CheckSettings;
import dev.oseer.config.Profile;
import dev.oseer.config.RunOverride;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.ModelCheck;
import dev.oseer.execution.VendorSettingsService;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Launch boundary shared by run/do/continuations. Embedders can supply their own transport. */
@FunctionalInterface
public interface RunReadiness {
    Map<String, Object> check(UserConfig user, Path project, List<Profile> profiles, boolean strictCost,
                              Consumer<String> progress) throws Exception;

    static RunReadiness live(ProcessRunner processes) {
        return using(new ModelCheck(processes));
    }

    /** A launch check before preparation has a roster and pins, but no compiled stage cast yet. */
    static boolean permitsLaunch(Map<String, Object> checked, UserConfig user, ProcessRunner processes,
                                 RunOverride override, Set<String> singleCallRoles, Consumer<String> progress) {
        if (Boolean.TRUE.equals(checked.get("ok"))) return true;
        if (user.policy() == null) return false;
        Set<String> selected = new LinkedHashSet<>();
        for (String stage : override.stages()) {
            String pinned = override.pinnedProfile(stage);
            if (pinned != null) selected.add(pinned);
        }
        RoleRunner roles = new RoleRunner(processes);
        for (var spec : user.policy().roles().values()) {
            if (!spec.fallbackStandby()) continue;
            if (roles.routingCandidates(user, spec.role(), RoleResolver.Writers.NONE).stream()
                    .noneMatch(candidate -> candidate.eligible() && !spec.standby(candidate.profile())))
                selected.addAll(spec.apiFallbackProfiles());
        }
        var standby = ModelCheck.standbyReadiness(checked, user.policy(), selected, singleCallRoles);
        if (standby == null) return false;
        narrateStandbys(standby, progress);
        return true;
    }

    static void narrateStandbys(ModelCheck.StandbyReadiness standby, Consumer<String> progress) {
        for (var row : standby.covered()) progress.accept("warn  check " + row.get("profile") + " · " + row.get("code")
                + "; standby fallback " + row.get("fallback") + " answered");
        for (var row : standby.unavailable()) progress.accept("warn  standby check " + row.get("profile") + " · " + row.get("code"));
    }

    /** Shared by production and offline transport tests; only the selected representatives infer. */
    static RunReadiness using(ModelCheck checks) {
        return (user, project, profiles, strictCost, progress) -> {
            if (!CheckSettings.load(user.home()).beforeRun())
                return Map.of("ok", true, "code", "check_disabled_by_user", "results", List.of());
            if (profiles.isEmpty()) return Map.of("ok", true, "code", "check_no_models", "results", List.of());
            // A token ceiling is not a monetary reservation. Never spend ahead of a strict cap.
            if (strictCost) return ModelCheck.failure("check_strict_cost_unproven");
            Map<String, Profile> representatives = new LinkedHashMap<>();
            Map<String, Set<String>> represented = new LinkedHashMap<>();
            for (Profile profile : profiles) {
                String group = profile.connectionRef() != null
                        ? VendorSettingsService.vendorId(profile) + "|" + profile.model()
                        : "local".equals(profile.runner()) ? profile.name() : VendorSettingsService.vendorId(profile);
                Profile first = representatives.putIfAbsent(group, profile);
                represented.computeIfAbsent(checkId(first == null ? profile : first), id -> new LinkedHashSet<>())
                        .add(profile.name());
            }
            Map<String, Profile> scoped = new LinkedHashMap<>();
            for (Profile profile : representatives.values()) scoped.putIfAbsent(checkId(profile), profile);
            UserConfig snapshot = new UserConfig(user.home(), user.policy(), scoped, user.problems(),
                    user.policyPresent(), user.connections());
            Map<String,Object> report = new LinkedHashMap<>(checks.run(snapshot, project, new LinkedHashSet<>(scoped.keySet()), progress));
            report.put("scope", "cli_vendor_representatives_and_selected_api_models");
            report.put("selected_profiles", profiles.size());
            report.put("checked_representatives", scoped.size());
            Map<String, List<String>> representedProfiles = new LinkedHashMap<>();
            represented.forEach((id, names) -> representedProfiles.put(id, List.copyOf(names)));
            report.put("represented_profiles", representedProfiles);
            report.put("assumption", "A CLI pong confirms its vendor/auth/channel using one selected model. Other CLI models share that availability assumption; each selected API model is checked separately.");
            return report;
        };
    }

    private static String checkId(Profile profile) {
        return profile.name() + "@" + profile.effort();
    }
}
