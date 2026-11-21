package dev.oseer;

import dev.oseer.config.ApiContextSettings;
import dev.oseer.config.Connection;
import dev.oseer.config.Profile;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

/**
 * The configuration half of reviewing a large existing branch through API readers: what the
 * reader is sent ({@code api_context}) and how long its answer may be (output ceilings).
 */
public final class ReviewContextConfigTest implements Suite {
    @Override public String name() { return "review-context-config"; }

    @Override public void run(Check check) throws Exception {
        apiContextBlock(check);
        connectionOutputCeiling(check);
    }

    private void apiContextBlock(Check check) {
        Profile plain = Profile.parse(profile(""), "plain.yaml");
        check.eq("an API profile without the block gets the defaults", ApiContextSettings.DEFAULT, plain.apiContext());
        check.eq("the default falls back to the diff rather than refusing", "auto", plain.apiContext().mode());

        Profile tuned = Profile.parse(profile("""
                api_context:
                  mode: diff
                  max_files: 200
                  max_file_bytes: 131072
                  max_bytes: 1048576
                """), "tuned.yaml");
        check.eq("mode is read", "diff", tuned.apiContext().mode());
        check.eq("max_files is read", 200, tuned.apiContext().maxFiles());
        check.eq("max_file_bytes is read", 131072, tuned.apiContext().maxFileBytes());
        check.eq("max_bytes is read", 1048576, tuned.apiContext().maxBytes());
        check.eq("context settings do not change what was verified", plain.apiFingerprint(), tuned.apiFingerprint());

        check.rejects("an unknown mode is refused at load", "api_context.mode",
                () -> Profile.parse(profile("api_context: { mode: everything }\n"), "bad.yaml"));
        check.rejects("an unknown key is refused at load", "api_context.max_lines",
                () -> Profile.parse(profile("api_context: { max_lines: 3 }\n"), "bad.yaml"));
        check.rejects("a limit past the ceiling is refused", "max_bytes",
                () -> Profile.parse(profile("api_context: { max_bytes: 999999999 }\n"), "bad.yaml"));
        check.rejects("a CLI profile reads the worktree itself and takes no api_context", "applies only to runner: api",
                () -> Profile.parse("""
                        version: 1
                        profile: cli-review
                        role: reviewer
                        vendor: grok
                        command: grok
                        read_only: true
                        api_context: { mode: diff }
                        """, "cli.yaml"));
        check.eq("a profile may now declare a large output cap", 32768,
                Profile.parse(profile("").replace("max_output_tokens: 128", "max_output_tokens: 32768"), "big.yaml")
                        .maxOutputTokens());
    }

    private void connectionOutputCeiling(Check check) {
        Connection gonka = Connection.parse(connection("gonkarouter", "https://api.gonkarouter.io/v1", ""), "g.yaml");
        check.eq("GonkaRouter keeps its measured default ceiling", 4096, gonka.outputCeiling());
        Connection router = Connection.parse(connection("openrouter", "https://openrouter.ai/api/v1", ""), "o.yaml");
        check.eq("OpenRouter imposes no connection ceiling", null, router.outputCeiling());

        Connection raised = Connection.parse(connection("gonkarouter", "https://api.gonkarouter.io/v1",
                "max_output_tokens: 16384\n"), "g.yaml");
        check.eq("a connection may declare its own ceiling", 16384, raised.outputCeiling());
        check.that("a declared ceiling changes the fingerprint", !raised.fingerprint().equals(gonka.fingerprint()));
        check.eq("an undeclared ceiling keeps the fingerprint every stamp was made against",
                new Connection("c", "gonkarouter", "openai_chat", "https://api.gonkarouter.io/v1",
                        "env:KEY", true).fingerprint(), gonka.fingerprint());
        check.contains("the ceiling survives a round trip", raised.toYaml(), "max_output_tokens: 16384");
        check.eq("and is read back", 16384, Connection.parse(raised.toYaml(), "rt.yaml").outputCeiling());
        check.eq("enable/disable keeps it", 16384, raised.withEnabled(false).outputCeiling());
        check.rejects("a ceiling out of range is refused", "max_output_tokens",
                () -> Connection.parse(connection("openrouter", "https://openrouter.ai/api/v1",
                        "max_output_tokens: 0\n"), "o.yaml"));
    }

    private static String profile(String extra) {
        return """
                version: 1
                profile: api-review
                role: reviewer
                model_vendor: deepseek
                model: deepseek/deepseek-test
                runner: api
                connection: router
                read_only: true
                limits: { wall_clock_minutes: 1, max_output_tokens: 128 }
                """ + extra;
    }

    private static String connection(String provider, String url, String extra) {
        return """
                version: 1
                connection: c
                provider: %s
                protocol: openai_chat
                base_url: '%s'
                auth:
                  type: api_key
                  credential_ref: env:KEY
                %s""".formatted(provider, url, extra);
    }
}
