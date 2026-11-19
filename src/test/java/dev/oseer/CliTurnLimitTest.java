package dev.oseer;

import dev.oseer.config.Profile;
import dev.oseer.config.UserSetup;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A direct CLI reviewer gets the model it was configured with and a turn ceiling set in
 * limits. Measured: a grok-review profile declared a model its args never passed, so every
 * review ran the CLI's default model, and a bare `--max-turns 12` stopped pull-request
 * reviews before they finished reading.
 */
public final class CliTurnLimitTest implements Suite {
    @Override public String name() { return "cli-turn-limit"; }

    @Override public void run(Check check) throws Exception {
        turnCeiling(check);
        modelMustReachTheCli(check);
        starterGrokReview(check);
    }

    private void turnCeiling(Check check) {
        Profile tuned = Profile.parse(grok("""
                  - "--max-turns"
                  - "{{max_turns}}"
                """, "  max_turns: 80\n"), "tuned.yaml");
        check.eq("limits.max_turns reaches the CLI flag", "80", after(tuned.args(), "--max-turns"));
        check.that("no placeholder is left for the vendor to read",
                tuned.args().stream().noneMatch(a -> a.contains("{{max_turns}}")));

        Profile defaulted = Profile.parse(grok("""
                  - "--max-turns"
                  - "{{max_turns}}"
                """, ""), "default.yaml");
        check.eq("an undeclared ceiling falls back to the review default",
                String.valueOf(Profile.DEFAULT_MAX_TURNS), after(defaulted.args(), "--max-turns"));
        check.that("the review default is above the 12 turns that could not finish a review",
                Profile.DEFAULT_MAX_TURNS > 12);

        Profile literal = Profile.parse(grok("""
                  - "--max-turns"
                  - "12"
                """, ""), "literal.yaml");
        check.eq("a literal ceiling is still passed exactly as written", "12", after(literal.args(), "--max-turns"));

        check.rejects("a declared ceiling the args never pass is refused", "{{max_turns}}",
                () -> Profile.parse(grok("", "  max_turns: 80\n"), "unused.yaml"));
        check.rejects("a ceiling past the limit is refused", "max_turns",
                () -> Profile.parse(grok("""
                          - "--max-turns"
                          - "{{max_turns}}"
                        """, "  max_turns: 100000\n"), "huge.yaml"));
        check.rejects("an API profile has no turns to cap", "limits.max_turns applies only",
                () -> Profile.parse("""
                        version: 1
                        profile: api-review
                        role: reviewer
                        runner: api
                        connection: openrouter-personal
                        model_vendor: deepseek
                        model: deepseek/deepseek-v4-flash-0731
                        read_only: true
                        limits:
                          max_turns: 30
                        """, "api.yaml"));
    }

    private void modelMustReachTheCli(Check check) {
        check.rejects("a model the args never pass is refused instead of silently running the CLI default",
                "no args entry passes {{model}}",
                () -> Profile.parse("""
                        version: 1
                        profile: grok-review
                        role: reviewer
                        vendor: grok
                        model: grok-4.7
                        effort: high
                        command: grok
                        read_only: true
                        args: ["--prompt-file", "{{prompt_file}}", "--reasoning-effort", "{{effort}}"]
                        """, "label.yaml"));
        Profile passed = Profile.parse(grok("", ""), "passed.yaml");
        check.eq("the configured model reaches -m/--model", "{{model}}", after(passed.args(), "--model"));
        check.eq("the model value is the configured one", "grok-4.7", passed.model());
        check.eq("effort reaches --reasoning-effort", "{{effort}}", after(passed.args(), "--reasoning-effort"));
        check.eq("effort value", "high", passed.effort());
        Profile unlabelled = Profile.parse("""
                version: 1
                profile: claude-review
                role: reviewer
                vendor: claude
                command: claude
                read_only: true
                args: ["-p", "--max-turns", "12"]
                """, "nolabel.yaml");
        check.eq("a profile that declares no model still loads", null, unlabelled.model());
    }

    private void starterGrokReview(Check check) throws Exception {
        Path home = Files.createTempDirectory("oseer-turns");
        new UserSetup().run(home);
        Profile starter = Profile.parse(Files.readString(home.resolve("profiles/grok-review.yaml")),
                "grok-review.yaml");
        check.eq("the starter passes its model", "{{model}}", after(starter.args(), "--model"));
        check.eq("the starter pins effort", "{{effort}}", after(starter.args(), "--reasoning-effort"));
        check.eq("the starter reads its turn ceiling from limits", "40", after(starter.args(), "--max-turns"));
    }

    private static String grok(String extraArgs, String extraLimits) {
        return """
                version: 1
                profile: grok-review
                role: reviewer
                vendor: grok
                model: grok-4.7
                effort: high
                command: grok
                read_only: true
                args:
                  - "--prompt-file"
                  - "{{prompt_file}}"
                  - "--model"
                  - "{{model}}"
                  - "--reasoning-effort"
                  - "{{effort}}"
                """ + extraArgs + "limits:\n  wall_clock_minutes: 40\n" + extraLimits;
    }

    private static String after(List<String> args, String flag) {
        int at = args.indexOf(flag);
        return at < 0 || at + 1 >= args.size() ? null : args.get(at + 1);
    }
}
