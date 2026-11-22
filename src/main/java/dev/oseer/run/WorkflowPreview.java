package dev.oseer.run;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.Profile;
import dev.oseer.config.RoutingSettings;
import dev.oseer.config.RunOverride;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.config.Workflow;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.oseer.run.WorkflowAdmission.initialPreviewWriters;
import static dev.oseer.run.WorkflowAdmission.judgeGap;
import static dev.oseer.run.WorkflowAdmission.judgesCandidate;
import static dev.oseer.run.WorkflowAdmission.overlayGap;
import static dev.oseer.run.WorkflowAdmission.planFor;
import static dev.oseer.run.WorkflowAdmission.readerGap;
import static dev.oseer.run.WorkflowAdmission.selectableProfiles;
import static dev.oseer.run.WorkflowAdmission.skipReason;
import static dev.oseer.run.WorkflowAdmission.strictCapGap;
import static dev.oseer.run.WorkflowAdmission.toolingGap;
import static dev.oseer.run.WorkflowAdmission.withPreviewWriter;

/** Projects the role roster, settings checks, and expected call cost without dispatching. */
final class WorkflowPreview {
    private WorkflowPreview() {}

    static Map<String, Object> budgetPreview(CallPlan plan, UserConfig user, TaskSpec.ResolvedTask task) {
        Map<String, Object> result = new LinkedHashMap<>(plan.toMap(task.budget().maxRoleRuns(),
                user.policy() == null ? "full" : user.policy().repairReserve()));
        if (task.budget().maxElapsedMinutes() != null) {
            result.put("time_reserve", "not reserved: budgets.max_elapsed_minutes ("
                    + task.budget().maxElapsedMinutes() + ") bounds the execution time of this "
                    + "run and every continuation of it. No vendor call starts with less than "
                    + "a minute left, and each call's wall clock is lowered to what is left. A "
                    + "call's duration is not predicted, so a repair is never refused in advance "
                    + "for time; gates and the baseline are bounded by timeout_minutes instead");
        }
        return result;
    }

    /**
     * Who would fill each stage, resolved from the roster before anything is paid.
     *
     * The header used to print the chain as stage names — `implement -> review -> look` —
     * which answers what will happen and not who will do it. Everything an operator actually
     * changes lives in the second question: which vendor, which model, at what effort, on
     * which runner, and whether a `--use` they typed landed on the stage they meant. Finding
     * that out required reading the summary of a run that had already been paid for.
     *
     * A preview, and honestly labelled as one. Rotation is read without advancing, preceding
     * writable profiles join the declared prior writers, and a mid-run failover can still
     * put a different vendor in a chair — the row says who the roster names now, which is the
     * question being asked now.
     */
    static List<Map<String, Object>> cast(Path root, Workflow workflow, UserConfig user,
                                           TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                           RoleRunner roles, RunOverride overlay) {
        List<Map<String, Object>> rows = new ArrayList<>();
        RoleResolver.Writers written = initialPreviewWriters(task);
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("role", stage.role());
            Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    judgesCandidate(stage, task) ? written : RoleResolver.Writers.NONE,
                    workflow.rotationPositionOf(stage));
            if (chosen == null) {
                row.put("profile", null);
                Map<String, String> why = roles.explainFill(user, stage.name(), stage.role(),
                        judgesCandidate(stage, task) ? written : RoleResolver.Writers.NONE,
                        workflow.rotationPositionOf(stage));
                row.put("unresolved", why == null ? Map.of() : why);
            } else {
                projectProfile(row, chosen);
                putFallbacks(row, user, stage.role(), chosen.name());
                // Named by the overlay is not the same as changed by it. A refused overlay
                // is about to stop the run, and a row that still said "this run only" would
                // be describing a dispatch that is not going to happen.
                if (overlay.stages().contains(stage.name())) {
                    row.put("from_overlay",
                            overlay.problem(stage.name(), chosen, user.profiles(), user) == null);
                }
                written = withPreviewWriter(written, chosen);
            }
            if (stage.fixWith() != null && !task.reviewOnly()
                    && ("fix".equals(stage.onFail()) || "fix".equals(stage.onFindings()))) {
                Profile fixer = roles.peek(root, user, stage.name() + "/fix",
                        stage.fixWith(), RoleResolver.Writers.NONE, RoleRunner.UNSTAGED);
                if (fixer != null) row.put("fix_profile", fixer.name());
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * What a run of {@code loaded} would dispatch at each role stage, and what would stop it,
     * without dispatching or advancing anything: the settings panel's answer for one task.
     *
     * Built from the preflight's own calls, in its order. The task's {@code use:} is pinned
     * and overlaid on the runner as the loop does it, the workflow is the one the task runs
     * (agent evidence swaps the browser stage), writable roles accumulate in order and every reader
     * that judges findings is peeked against it with the task's {@code review_assurance}, and
     * an overlay the chosen profile cannot deliver is refused with {@link RunOverride#problem},
     * the check {@code overlayGap} stops a run on. A table built from the policy alone named
     * the policy's writer for a task that pins another, and called a same-vendor peer the task
     * allows {@code peer_not_allowed_by_task}.
     *
     * The overlay is the task's own: flags given to one run and handovers are not on disk yet.
     */
    static List<Map<String, Object>> dispatchPreview(Path root, ConfigLoader.Loaded loaded,
                                                            UserConfig user,
                                                            RoleResolver.Availability availability) {
        return dispatchPreview(root, loaded, user, availability, RunOverride.NONE);
    }

    static List<Map<String, Object>> dispatchPreview(Path root, ConfigLoader.Loaded loaded,
                                                            UserConfig user,
                                                            RoleResolver.Availability availability,
                                                            RunOverride invocation) {
        return dispatchPreview(root, user, roster(loaded, user, invocation, availability));
    }

    private static List<Map<String, Object>> dispatchPreview(Path root, UserConfig user, Roster roster) {
        var task = roster.task();
        var workflow = roster.workflow();
        var overlay = roster.overlay();
        var roles = roster.roles();
        RoleResolver.Writers written = initialPreviewWriters(task);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage.name());
            row.put("role", stage.role());
            rows.add(row);
            String skipped = skipReason(stage, user, task, roster.reviewByRisk());
            if (skipped != null) {
                row.put("skipped", skipped);
                continue;
            }
            RoleResolver.Writers judgedBy = judgesCandidate(stage, task) ? written : RoleResolver.Writers.NONE;
            int position = workflow.rotationPositionOf(stage);
            Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    judgedBy, position);
            if (chosen == null) {
                row.put("profile", null);
                Map<String, String> why = roles.explainFill(user, stage.name(), stage.role(), judgedBy, position);
                row.put("unresolved", why == null ? Map.of() : why);
                continue;
            }
            projectProfile(row, chosen);
            putFallbacks(row, user, stage.role(), chosen.name());
            if (judgedBy != RoleResolver.Writers.NONE) {
                row.put("judged_against", judgedBy.profiles().stream().sorted().toList());
            }
            if (overlay.stages().contains(stage.name())) {
                String problem = overlay.problem(stage.name(), chosen, user.profiles(), user);
                row.put("from_overlay", problem == null);
                if (problem != null) {
                    row.put("refused", "run_override_undeliverable");
                    row.put("message", problem);
                }
            }
            written = withPreviewWriter(written, chosen);
        }
        return rows;
    }

    /**
     * Settings-only preview for a fresh invocation. Uses the loop's own resolver, skip
     * predicate, call arithmetic and settings preflights, without a run reservation,
     * prompts, probes, rotation advances or dispatch. Workspace and live adapter checks
     * still belong to the CLI dry run; this is not an authorization to execute.
     */
    static Map<String, Object> settingsPreview(Path root, ConfigLoader.Loaded loaded,
                                                      UserConfig user, RunOverride invocation,
                                                      RoleResolver.Availability availability) {
        Roster roster = roster(loaded, user, invocation, availability);
        var task = roster.task();
        var workflow = roster.workflow();
        var reviewByRisk = roster.reviewByRisk();
        var overlay = roster.overlay();
        var roles = roster.roles();
        List<Map<String, Object>> dispatch = dispatchPreview(root, user, roster);
        CallPlan plan = planFor(workflow, user, task, List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("would_run", dispatch);
        result.put("budget_plan", budgetPreview(plan, user, task));
        List<Map<String, Object>> problems = new ArrayList<>();
        if (user.policyPresent() && user.policy() == null) {
            problems.add(Map.of("code", "policy_invalid", "message", "policy.yaml does not parse"));
        }
        Map<String, Object> gap = overlayGap(root, workflow, user, task, reviewByRisk, roles, overlay);
        if (gap != null) problems.add(gap);
        gap = judgeGap(workflow, user, task, reviewByRisk, roles);
        if (gap != null) problems.add(gap);
        gap = toolingGap(root, workflow, user, task, reviewByRisk, roles);
        if (gap != null) problems.add(gap);
        gap = readerGap(root, workflow, user, task, reviewByRisk, roles);
        if (gap != null) {
            gap = new LinkedHashMap<>(gap);
            gap.put("code", "independent_review_unavailable");
            problems.add(gap);
        }
        if (task.budget().strictCostCap()) {
            gap = strictCapGap(workflow, user, task, reviewByRisk, overlay, Map.of());
            if (gap != null) {
                gap = new LinkedHashMap<>(gap);
                gap.put("code", "cost_cap_unenforceable");
                problems.add(gap);
            } else {
                // Prove only an impossible cap here: even the cheapest declared option at
                // every owed stage cannot fit. Actual dispatch still reserves selected and
                // outstanding worst-case bounds, including later failovers and repairs.
                double minimum = 0;
                for (Workflow.Stage stage : workflow.stages()) {
                    if (stage.kind() != Workflow.Kind.ROLE || skipReason(stage, user, task, reviewByRisk) != null) continue;
                    minimum += selectableProfiles(stage, user, overlay, Map.of()).stream()
                            .filter(p -> p.maxCostUsd() != null).mapToDouble(p -> p.maxCostUsd()).min().orElse(0);
                }
                result.put("strict_minimum_bound_usd", minimum);
                if (task.budget().maxCostUsd() > 0 && minimum > task.budget().maxCostUsd())
                    problems.add(Map.of("code", "budget_insufficient", "message",
                            "Strict cost cap is below even the minimum declared bounds of required calls: " + minimum));
            }
        }
        for (Map<String, Object> row : dispatch) {
            if (row.containsKey("skipped") || row.get("profile") != null) continue;
            problems.add(Map.of("code", "no_eligible_profile", "stage", row.get("stage"),
                    "message", "No eligible profile: " + row.get("unresolved")));
        }
        if (task.budget().maxRoleRuns() > 0 && task.budget().maxRoleRuns() < plan.minimumToFinish()) {
            problems.add(Map.of("code", "budget_insufficient", "message",
                    "Call cap is below the minimum needed to finish"));
        }
        result.put("problems", problems);
        result.put("settings_ok", problems.isEmpty());
        return result;
    }

    /** The role's declared fallbacks other than the chosen profile, for the cast a person approves. */
    private static void putFallbacks(Map<String, Object> row, UserConfig user, String role, String chosen) {
        var spec = user.policy() == null || role == null ? null : user.policy().roles().get(role);
        if (spec == null || spec.apiFallbackProfiles().isEmpty()) return;
        List<String> standby = spec.apiFallbackProfiles().stream().filter(name -> !name.equals(chosen)).toList();
        if (standby.isEmpty()) return;
        row.put("fallbacks", standby);
        row.put("on_vendor_failure", user.policy().vendorFailureModeFor(role));
    }


    private static void projectProfile(Map<String, Object> row, Profile chosen) {
        row.put("profile", chosen.name());
        row.put("vendor", chosen.vendor());
        row.put("model", chosen.model());
        row.put("effort", chosen.effort());
        row.put("runner", chosen.runner());
    }

    private record Roster(TaskSpec.ResolvedTask task, Workflow workflow, boolean reviewByRisk,
                          RunOverride overlay, RoleRunner roles) {}

    private static Roster roster(ConfigLoader.Loaded loaded, UserConfig user, RunOverride invocation,
                                 RoleResolver.Availability availability) {
        TaskSpec.ResolvedTask task = loaded.resolved();
        Workflow workflow = user.policy() == null ? Workflow.builtIn() : user.policy().workflow();
        if (task.visualQa().agentEvidence()) workflow = workflow.forAgentEvidence();
        boolean reviewByRisk = user.policy() != null && user.policy().reviewRequired(task.risk());
        RunOverride overlay = loaded.task().use().merged(invocation).withWriterFixes(workflow);
        RoleRunner roles = new RoleRunner(new ProcessRunner()).availability(availability);
        overlay.pin(roles);
        roles.overlay(overlay);
        return new Roster(task, workflow, reviewByRisk, overlay, roles);
    }

    static boolean modelSelectionEnabled(UserConfig user) {
        try {
            return RoutingSettings.load(user.home()).modelSelectionEnabled();
        } catch (Exception invalid) {
            return false;
        }
    }

    /** Manual classification uses the admitted roster before dispatch or model checks. */
    static List<TaskRouting.Target> classificationTargets(Path root, ConfigLoader.Loaded loaded,
                                                           UserConfig user, RoleResolver.Availability availability)
            throws Exception {
        if (!modelSelectionEnabled(user)) {
            List<TaskRouting.Target> targets = new ArrayList<>();
            for (var row : dispatchPreview(root, loaded, user, availability)) {
                if (!(row.get("profile") instanceof String name)) continue;
                Profile profile = user.profiles().get(name);
                if (profile == null) continue;
                if (row.get("effort") instanceof String effort) profile = profile.withEffort(effort);
                targets.add(new TaskRouting.Target(String.valueOf(row.get("stage")),
                        String.valueOf(row.get("role")), profile));
            }
            return targets;
        }

        TaskSpec.ResolvedTask task = loaded.resolved();
        boolean reviewByRisk = user.policy().reviewRequired(task.risk());
        Roster roster = roster(loaded, user, RunOverride.NONE, availability);
        List<Workflow.Stage> active = roster.workflow().stages().stream()
                .filter(stage -> stage.kind() == Workflow.Kind.ROLE)
                .filter(stage -> skipReason(stage, user, task, reviewByRisk) == null)
                .toList();
        var prepared = ModelRoutingPlan.prepare(root, user, task, active, roster.workflow(),
                roster.roles(), roster.overlay(), null, text -> {}, null);
        if (prepared.gap() != null) {
            throw new IllegalArgumentException("classification has no admitted candidates: " + prepared.gap());
        }
        return prepared.targets();
    }
}
