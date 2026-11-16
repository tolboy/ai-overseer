package dev.oseer;

import dev.oseer.config.UserConfig;
import dev.oseer.config.UserSetup;
import dev.oseer.execution.CredentialAudit;
import dev.oseer.execution.CredentialResolver;
import dev.oseer.execution.OsCredentialStores;
import dev.oseer.json.Json;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** A process that cannot see the keys its roster needs says which references, never what they hold. */
public final class CredentialAuditTest implements Suite {
    public String name() { return "credential-audit"; }

    public void run(Check check) throws Exception {
        Path home = Files.createTempDirectory("oseer-credential-audit-");
        new UserSetup().run(home);
        Files.createDirectories(home.resolve("connections"));
        Files.writeString(home.resolve("connections/gateway.yaml"), """
                version: 1
                connection: gateway
                provider: openrouter
                protocol: openai_chat
                base_url: 'https://openrouter.ai/api/v1'
                auth:
                  type: api_key
                  credential_ref: env:AUDIT_TEST_GATEWAY_KEY
                enabled: true
                """);
        Files.writeString(home.resolve("profiles/gateway-review.yaml"), """
                version: 1
                profile: gateway-review
                role: reviewer
                runner: api
                connection: gateway
                model_vendor: deepseek
                model: deepseek/deepseek-v4-flash
                read_only: true
                prompt_template: prompts/reviewer.md
                json_schema: schemas/reviewer.json
                enforce_schema: true
                limits:
                  wall_clock_minutes: 5
                  max_output_tokens: 2048
                """);
        String policy = Files.readString(home.resolve("policy.yaml"));
        Files.writeString(home.resolve("policy.yaml"), policy.replaceFirst(
                "(?m)^(  reviewer:\\n    profiles: \\[)", "$1gateway-review, "));
        UserConfig user = UserConfig.load(home);
        check.that("the fixture puts the API reviewer in the roster",
                user.policy().roles().get("reviewer").profiles().contains("gateway-review"));

        List<Map<String, Object>> missing = CredentialAudit.unresolved(user,
                new CredentialResolver(name -> null, OsCredentialStores.unavailable()));
        check.eq("one reference is unresolved", 1, missing.size());
        check.eq("it is named by reference", "env:AUDIT_TEST_GATEWAY_KEY", missing.get(0).get("credential_ref"));
        check.eq("with its connection", "gateway", missing.get(0).get("connection"));
        check.eq("and who needs it", List.of("gateway-review"), missing.get(0).get("profiles"));
        check.eq("the state says missing", "missing", missing.get(0).get("state"));

        String secret = "sk-or-v1-" + "a".repeat(40);
        List<Map<String, Object>> present = CredentialAudit.unresolved(user,
                new CredentialResolver(name -> name.equals("AUDIT_TEST_GATEWAY_KEY") ? secret : null,
                        OsCredentialStores.unavailable()));
        check.eq("a resolved reference is not reported", List.of(), present);
        check.that("no value ever appears in the report", !Json.write(missing).contains("sk-or"));
    }
}
