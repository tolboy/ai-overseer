package dev.oseer.config;

import dev.oseer.BundledResources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Installs starter profiles, prompts, schemas, and policy in the operator's home. */
public final class UserSetup {
    private static final String TEMPLATE_DIRECTORY = "/dev/oseer/config/setup/";
    private static final String POLICY = "policy.yaml";
    private static final String PLANNER_SCHEMA = "schemas/planner.json";
    private static final String PLAN_REVIEWER_SCHEMA = "schemas/plan-reviewer.json";
    private static final List<String> TEMPLATES = List.of(
            POLICY,
            "profiles/grok-review.yaml",
            "profiles/claude-review.yaml",
            "profiles/codex-implement.yaml",
            "profiles/codex-visual-qa.yaml",
            "profiles/agy-plan-review.yaml",
            "profiles/claude-visual-qa-mcp.yaml",
            "prompts/reviewer.md",
            "prompts/implementer.md",
            "prompts/visual-qa.md",
            "prompts/planner.md",
            "prompts/plan-reviewer.md",
            "schemas/reviewer.json",
            "schemas/implementer.json",
            "schemas/visual-qa.json",
            PLANNER_SCHEMA,
            PLAN_REVIEWER_SCHEMA);

    public record Result(Path home, List<String> created, List<String> skipped) {}

    public Result run(Path home) throws IOException {
        Files.createDirectories(home.resolve("profiles"));
        Files.createDirectories(home.resolve("prompts"));
        Files.createDirectories(home.resolve("schemas"));

        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String filename : TEMPLATES) {
            if (install(home.resolve(filename), filename)) {
                created.add(filename);
            } else {
                skipped.add(filename);
            }
        }
        return new Result(home, created, skipped);
    }

    private static boolean install(Path target, String filename) throws IOException {
        if (Files.exists(target)) {
            return false;
        }
        try {
            Files.writeString(target, template(filename), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException existing) {
            return false;
        }
    }

    /** The starter policy validated by the project configuration suite. */
    public static String policyTemplate() {
        return template(POLICY);
    }

    /** The schema used to validate planner drafts before Overseer compiles them. */
    public static String plannerSchema() {
        return template(PLANNER_SCHEMA);
    }

    /** The schema used to validate the plan reviewer's findings. */
    public static String planReviewerSchema() {
        return template(PLAN_REVIEWER_SCHEMA);
    }

    private static String template(String filename) {
        return BundledResources.readText(TEMPLATE_DIRECTORY + filename);
    }
}
