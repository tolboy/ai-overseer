package dev.oseer;

import dev.oseer.config.ModelSettingsService;
import dev.oseer.config.RoleRosterEditor;
import dev.oseer.config.UserSetup;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A browser roster edit cannot leave an independent-review role with only the writer's vendor. */
public final class RosterIndependenceTest implements Suite {
    public String name() { return "roster-independence"; }

    public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("oseer-roster-independence-");
        new UserSetup().run(home);
        Files.writeString(home.resolve("profiles/writer.yaml"), profile("writer", "implementer", "grok", false));
        Files.writeString(home.resolve("profiles/other-writer.yaml"), profile("other-writer", "implementer", "codex", false));
        Files.writeString(home.resolve("profiles/same-reader.yaml"), profile("same-reader", "reviewer", "xai", true));
        Files.writeString(home.resolve("profiles/other-reader.yaml"), profile("other-reader", "reviewer", "openai", true));
        Files.writeString(home.resolve("profiles/planner-a.yaml"), profile("planner-a", "planner", "grok", true));
        Path policy = home.resolve("policy.yaml");
        Files.writeString(policy, """
                version: 1
                roles:
                  planner:
                    profiles: [planner-a]
                    strategy: first
                    require_independent_vendor: false
                  implementer:
                    profiles: [writer, other-writer]
                    strategy: first
                    require_independent_vendor: false
                  reviewer:
                    profiles: [other-reader, same-reader]
                    strategy: first
                    require_independent_vendor: true
                """);
        var editor = new RoleRosterEditor(home);

        String before = Files.readString(policy);
        var refused = editor.apply(payload(home, "reviewer", List.of("same-reader")));
        check.eq("a reviewer list with only the writer's vendor is refused", 400, refused.status());
        check.eq("the refusal names the rule", "reviewer_not_independent", refused.report().get("code"));
        check.eq("the refusal names the role", "reviewer", refused.report().get("role"));
        check.eq("the refusal names the writer's vendor in canonical form", "x-ai", refused.report().get("writer_vendor"));
        check.eq("a refused save writes nothing", before, Files.readString(policy));

        var reordered = editor.apply(payload(home, "reviewer", List.of("same-reader", "other-reader")));
        check.eq("one independent candidate is enough", 200, reordered.status());

        var writerSwap = editor.apply(payload(home, "implementer", List.of("other-writer")));
        check.eq("an implementer change is judged against every independent role too", 200, writerSwap.status());
        Files.writeString(policy, """
                version: 1
                roles:
                  planner:
                    profiles: [planner-a]
                    strategy: first
                    require_independent_vendor: false
                  implementer:
                    profiles: [writer]
                    strategy: first
                    require_independent_vendor: false
                  reviewer:
                    profiles: [other-reader]
                    strategy: first
                    require_independent_vendor: true
                """);
        var clash = editor.apply(payload(home, "implementer", List.of("other-writer", "writer")));
        check.eq("a new primary writer of the only reviewer's vendor is refused", 400, clash.status());
        check.eq("that refusal names the reviewer role", "reviewer", clash.report().get("role"));

        // An existing clash does not block an edit of an unrelated role.
        var planner = editor.apply(payload(home, "planner", List.of("planner-a")));
        check.eq("an unrelated role still saves", 200, planner.status());
    }

    private static Map<String, Object> payload(Path home, String role, List<String> names) throws Exception {
        List<Object> slots = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("profile", name);
            slot.put("revision", ModelSettingsService.revision(home.resolve("profiles/" + name + ".yaml")));
            slot.put("model", "model-" + name);
            slots.add(slot);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revision", ModelSettingsService.revision(home.resolve("policy.yaml")));
        payload.put("role", role);
        payload.put("strategy", "first");
        payload.put("slots", slots);
        return payload;
    }

    private static String profile(String name, String role, String vendor, boolean readOnly) {
        String kind = role.equals("implementer") ? "implementer" : role.equals("planner") ? "planner" : "reviewer";
        return "version: 1\nprofile: " + name + "\nrole: " + role + "\nvendor: " + vendor + "\nservice: " + dev.oseer.config.Profile.canonicalVendor(vendor)
                + "\nmodel: model-" + name + "\ncommand: echo\nargs: ['--model','{{model}}']\nread_only: " + readOnly
                + "\nprompt_template: prompts/" + kind + ".md\njson_schema: schemas/" + kind + ".json\n"
                + "verification:\n  probe: echo {{model}} verified\n  expect: verified\n  verified_on: '2026-11-16'\n";
    }
}
