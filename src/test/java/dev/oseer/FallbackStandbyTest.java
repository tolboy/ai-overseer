package dev.oseer;

import dev.oseer.config.ModelSettingsService;
import dev.oseer.config.Policy;
import dev.oseer.config.RoleRosterEditor;
import dev.oseer.config.UserConfig;
import dev.oseer.config.UserSetup;
import dev.oseer.role.RoleResolver;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A fallback held in standby fills a stage only when the call fails, and is configured from the browser. */
public final class FallbackStandbyTest implements Suite {
    public String name() { return "fallback-standby"; }

    private static final String ROLES = """
            version: 1
            roles:
              implementer:
                profiles: [writer]
                strategy: first
                require_independent_vendor: false
              reviewer:
                profiles: [reader-a, reader-b]
                strategy: rotate
                require_independent_vendor: true
            %s
            """;

    public void run(Check check) throws Exception {
        check.rejects("standby needs a fallback list", "nothing to hold in reserve", () -> Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [a]
                    fallback_standby: true
                """, "p.yaml"));
        check.rejects("standby needs a profile that starts the stage", "no profile to start with", () -> Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [a]
                    api_fallback_profiles: [a]
                    fallback_standby: true
                """, "p.yaml"));
        Policy legacy = Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [a, b]
                    api_fallback_profiles: [b]
                """, "p.yaml");
        check.eq("without the flag a fallback still rotates, as before", false, legacy.roles().get("reviewer").standby("b"));

        Path home = Files.createTempDirectory("oseer-fallback-standby-");
        new UserSetup().run(home);
        Files.writeString(home.resolve("profiles/writer.yaml"), profile("writer", "implementer", "codex", false));
        Files.writeString(home.resolve("profiles/reader-a.yaml"), profile("reader-a", "reviewer", "z-ai", true));
        Files.writeString(home.resolve("profiles/reader-b.yaml"), profile("reader-b", "reviewer", "google", true));
        Files.writeString(home.resolve("profiles/reader-c.yaml"), profile("reader-c", "reviewer", "deepseek", true));
        Files.writeString(home.resolve("profiles/same-reader.yaml"), profile("same-reader", "reviewer", "openai", true));
        Path policy = home.resolve("policy.yaml");
        Files.writeString(policy, ROLES.formatted(""));

        UserConfig plain = UserConfig.load(home);
        var resolver = new RoleResolver();
        check.eq("rotation without standby reaches the second reader", "reader-b",
                resolver.resolve("reviewer", plain.policy(), plain.profiles(), RoleResolver.Writers.of("codex"), 1, p -> true,
                        Set.of(), Set.of(), Set.of()).selected().name());

        var editor = new RoleRosterEditor(home);
        var saved = editor.apply(fallbacks(home, "reviewer", List.of("reader-b", "reader-c"), "auto"));
        check.eq("fallbacks are saved", 200, saved.status());
        check.eq("the answer names them in order", List.of("reader-b", "reader-c"), saved.report().get("fallbacks"));
        check.eq("a fallback the role did not list joins its profiles", List.of("reader-a", "reader-b", "reader-c"), saved.report().get("profiles"));
        UserConfig standby = UserConfig.load(home);
        var spec = standby.policy().roles().get("reviewer");
        check.eq("the policy holds them in standby", true, spec.fallbackStandby());
        check.eq("and records the failure mode on the role", "auto", spec.vendorFailureMode());
        for (long position = 0; position < 3; position++) {
            check.eq("rotation position " + position + " never starts on a fallback", "reader-a",
                    resolver.resolve("reviewer", standby.policy(), standby.profiles(), RoleResolver.Writers.of("codex"), position,
                            p -> true, Set.of(), Set.of(), Set.of()).selected().name());
        }
        check.eq("a fallback fills the stage when nothing else is eligible", "reader-b",
                resolver.resolve("reviewer", standby.policy(), standby.profiles(), RoleResolver.Writers.of("codex"), 0,
                        p -> !p.name().equals("reader-a"), Set.of(), Set.of(), Set.of()).selected().name());

        String before = Files.readString(policy);
        var clash = editor.apply(fallbacks(home, "reviewer", List.of("same-reader"), "auto"));
        check.eq("a reviewer fallback from the writer's vendor is refused", 400, clash.status());
        check.eq("the refusal says why", "fallback_not_independent", clash.report().get("code"));
        check.eq("a refused save writes nothing", before, Files.readString(policy));
        var everything = editor.apply(fallbacks(home, "reviewer", List.of("reader-a", "reader-b", "reader-c"), "auto"));
        check.eq("a role made only of fallbacks is refused", "fallback_needs_primary", everything.report().get("code"));
        var bad = editor.apply(fallbacks(home, "reviewer", List.of("writer"), "auto"));
        check.eq("a profile of another role cannot be a fallback", 400, bad.status());

        var cleared = editor.apply(fallbacks(home, "reviewer", List.of(), "inherit"));
        check.eq("clearing saves", 200, cleared.status());
        UserConfig after = UserConfig.load(home);
        check.eq("no fallbacks remain", List.of(), after.policy().roles().get("reviewer").apiFallbackProfiles());
        check.eq("and standby is gone with them", false, after.policy().roles().get("reviewer").fallbackStandby());
        check.eq("dropped standby fallbacks leave the rotation too", List.of("reader-a"), after.policy().roles().get("reviewer").profiles());
        check.eq("inherit removes the role's own failure mode", null, after.policy().roles().get("reviewer").vendorFailureMode());

        // A roster save that drops a fallback keeps the policy valid.
        editor.apply(fallbacks(home, "reviewer", List.of("reader-c"), "confirm"));
        var roster = new LinkedHashMap<String, Object>();
        roster.put("revision", ModelSettingsService.revision(policy));
        roster.put("role", "reviewer"); roster.put("strategy", "rotate");
        roster.put("slots", List.of(slot(home, "reader-a"), slot(home, "reader-b")));
        var dropped = editor.apply(roster);
        check.eq("a roster save without the fallback succeeds", 200, dropped.status());
        UserConfig trimmed = UserConfig.load(home);
        check.eq("the dropped fallback leaves the fallback list", List.of(), trimmed.policy().roles().get("reviewer").apiFallbackProfiles());
        check.eq("the failure mode stays", "confirm", trimmed.policy().roles().get("reviewer").vendorFailureMode());

        // The settings answer carries what the browser needs to draw the fallback form.
        editor.apply(fallbacks(home, "reviewer", List.of("reader-b"), "auto"));
        var state = new ModelSettingsService(home, home, name -> null).list();
        @SuppressWarnings("unchecked") var routes = (List<Map<String, Object>>) state.get("routes");
        var reviewer = routes.stream().filter(r -> "reviewer".equals(r.get("role"))).findFirst().orElseThrow();
        check.eq("routes carry the fallbacks", List.of("reader-b"), reviewer.get("fallbacks"));
        check.eq("routes carry the failure mode", "auto", reviewer.get("on_vendor_failure"));
        check.that("the policy default is offered for inherit", state.containsKey("on_vendor_failure_default"));
    }

    private static Map<String, Object> fallbacks(Path home, String role, List<String> names, String mode) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "fallbacks");
        payload.put("revision", ModelSettingsService.revision(home.resolve("policy.yaml")));
        payload.put("role", role);
        payload.put("fallbacks", names);
        payload.put("on_vendor_failure", mode);
        return payload;
    }

    private static Map<String, Object> slot(Path home, String name) throws Exception {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("profile", name);
        slot.put("revision", ModelSettingsService.revision(home.resolve("profiles/" + name + ".yaml")));
        slot.put("model", "model-" + name);
        return slot;
    }

    private static String profile(String name, String role, String vendor, boolean readOnly) {
        String kind = role.equals("implementer") ? "implementer" : "reviewer";
        return "version: 1\nprofile: " + name + "\nrole: " + role + "\nvendor: " + vendor + "\nservice: " + dev.oseer.config.Profile.canonicalVendor(vendor)
                + "\nmodel: model-" + name + "\ncommand: echo\nargs: ['--model','{{model}}']\nread_only: " + readOnly
                + "\nprompt_template: prompts/" + kind + ".md\njson_schema: schemas/" + kind + ".json\n"
                + "verification:\n  probe: echo {{model}} verified\n  expect: verified\n  verified_on: '2026-11-16'\n";
    }
}
