package dev.oseer.run;

import dev.oseer.config.Policy;
import dev.oseer.config.Profile;
import dev.oseer.config.RunOverride;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.config.Workflow;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Applies the same stage and profile checks to previews and live dispatch plans. */
final class WorkflowAdmission {
    private WorkflowAdmission() {}

    /**
     * The first judging stage no reader could fill against its preceding writers, as the chain would
     * dispatch, or null when every one can be. Asked once, before the first paid call.
     *
     * The writer is the profile the roster would actually choose, so a `first` strategy is
     * answered exactly and a rotating one is answered for the profile whose turn it is. A
     * chain with no writing stage has nothing to differ from here; its provenance question is
     * answered by the writer set instead.
     */
    static Map<String, Object> readerGap(Path root, Workflow workflow, UserConfig user,
                                                 TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                 RoleRunner roles) {
        RoleResolver.Writers wouldWrite = initialPreviewWriters(task);
        boolean hasReader = false;
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            int position = workflow.rotationPositionOf(stage);
            boolean reader = judgesCandidate(stage, task);
            hasReader |= reader;
            RoleResolver.Writers judgedBy = reader
                    ? wouldWrite : RoleResolver.Writers.NONE;
            Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    judgedBy, position);
            if (chosen != null) {
                wouldWrite = withPreviewWriter(wouldWrite, chosen);
                continue;
            }
            // A missing writer is a routing failure, not proof that the following readers
            // are impossible. Neither preflight nor preview advances rotation or dispatches.
            if (!reader || (!task.reviewOnly() && wouldWrite.vendors().isEmpty())) continue;
            Map<String, String> refused = roles.explainFill(user, stage.name(), stage.role(),
                    wouldWrite, position);
            if (refused == null && !task.reviewOnly()) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("role", stage.role());
            gap.put("needed_for", stage.name());
            gap.put("blocked_at", "preflight");
            gap.put("rejected_profiles", refused == null ? Map.of() : refused);
            gap.put("writer_vendors", wouldWrite.vendors().stream().sorted().toList());
            gap.put("writer_profiles", wouldWrite.profiles().stream().sorted().toList());
            // Keep the single-writer fields for existing consumers.
            if (wouldWrite.vendors().size() == 1)
                gap.put("must_differ_from_vendor", wouldWrite.vendors().iterator().next());
            if (wouldWrite.profiles().size() == 1)
                gap.put("writer_profile", wouldWrite.profiles().iterator().next());
            gap.put("exhausted_profiles", List.copyOf(roles.exhaustedProfiles()));
            gap.put("message", "no profile can fill role '" + stage.role() + "' for stage '"
                    + stage.name() + "' after the preceding writers " + wouldWrite.profiles()
                    + " (vendors " + wouldWrite.vendors() + ") have written the candidate ("
                    + (refused == null ? Map.of() : refused) + "). Add a profile from another vendor"
                    + (task.reviewOnly() ? "" : ", or declare a same_vendor_peer pair in policy.yaml and set "
                    + "review_assurance: same_vendor_peer on the task if that weaker check is acceptable")
                    + "; nothing was dispatched.");
            return gap;
        }
        if (task.reviewOnly() && !hasReader) {
            return Map.of("role", "reviewer", "blocked_at", "preflight",
                    "message", "mode: review requires an active review stage. Configure a reviewer in "
                            + "policy.yaml and a workflow stage whose conditions match this task.");
        }
        return null;
    }

    static boolean allowsSameVendorPeer(TaskSpec.ResolvedTask task) {
        return !task.reviewOnly() && "same_vendor_peer".equals(task.reviewAssurance());
    }

    static RoleResolver.Writers initialPreviewWriters(TaskSpec.ResolvedTask task) {
        return new RoleResolver.Writers(new HashSet<>(task.priorWriters()),
                Set.of(), true, allowsSameVendorPeer(task));
    }

    static RoleResolver.Writers withPreviewWriter(RoleResolver.Writers written,
                                                          Profile profile) {
        if (profile.readOnly()) return written;
        Set<String> vendors = new HashSet<>(written.vendors());
        Set<String> profiles = new HashSet<>(written.profiles());
        vendors.add(profile.vendor());
        profiles.add(profile.name());
        return new RoleResolver.Writers(vendors, profiles, written.known(), written.peerAllowed());
    }

    /**
     * The paying profile with no declared per-call bound, when the task asked for a strict
     * money cap, or null when every candidate declares one.
     *
     * Routing does not only consult {@link Policy.RoleSpec#profiles()}: a {@code --use} pin,
     * a {@code --host} twin, an escalation rung and an authorised substitution can all select
     * a verified profile from the operator's roster. A cap that ignored those spent under a
     * number it had never reserved.
     */
    static Map<String, Object> strictCapGap(Workflow workflow, UserConfig user,
                                                    TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                    RunOverride overlay,
                                                    Map<String, String> substitutions) {
        if (user.policy() == null) return null;
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            for (Profile profile : selectableProfiles(stage, user, overlay,
                    substitutions)) {
                if (profile.maxCostUsd() != null) continue;
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("stage", stage.name());
                gap.put("role", stage.role());
                gap.put("profile", profile.name());
                gap.put("message", "budgets.cost_cap: strict needs a proven upper bound for every "
                        + "call it may admit, and profile '" + profile.name() + "' (stage '"
                        + stage.name()
                        + "') declares no limits.max_cost_usd. Declare one from that vendor's "
                        + "measured worst case, or set cost_cap: threshold to keep max_cost_usd as "
                        + "a threshold on reported spend; nothing was dispatched.");
                return gap;
            }
        }
        return null;
    }

    /**
     * The worst-case price of each paying stage under a strict cap: the largest declared
     * bound among the profiles routing can actually select. Preparation stages are already
     * spent when the loop starts and do not need reserving. The dispatch gate then overwrites
     * the stage with the selected profile's own bound.
     */
    static Map<String, Double> strictBounds(Workflow workflow, UserConfig user,
                                                    TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                    RunOverride overlay,
                                                    Map<String, String> substitutions) {
        Map<String, Double> bounds = new LinkedHashMap<>();
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            double worst = 0;
            for (Profile profile : selectableProfiles(stage, user, overlay,
                    substitutions)) {
                if (profile.maxCostUsd() != null) {
                    worst = Math.max(worst, profile.maxCostUsd());
                }
            }
            bounds.put(stage.name(), worst);
        }
        return bounds;
    }

    /**
     * Every profile this stage might dispatch: the policy list, a pin, a host twin of those,
     * an escalation rung of the same role, and a substitution a person already authorised.
     */
    static List<Profile> selectableProfiles(Workflow.Stage stage,
            UserConfig user, RunOverride overlay, Map<String, String> substitutions) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Policy.RoleSpec spec = user.policy() == null ? null : user.policy().roles().get(stage.role());
        if (spec != null) names.addAll(spec.profiles());
        if (overlay != null) {
            String pin = overlay.pinnedProfile(stage.name());
            if (pin != null) names.add(pin);
        }
        if (substitutions != null) {
            String named = substitutions.get(stage.name());
            if (named == null) named = substitutions.get(stage.role());
            if (named != null) names.add(named);
        }
        Policy.Escalation ladder = user.policy() == null ? null : user.policy().escalation();
        if (ladder != null) {
            for (Policy.Escalation.Rung rung : ladder.rungs()) {
                if ("implementer".equals(stage.role())) names.add(rung.implementer());
                else if ("reviewer".equals(stage.role())) names.add(rung.reviewer());
            }
        }
        List<Profile> profiles = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            Profile profile = user.profiles().get(name);
            if (profile == null) continue;
            if (overlay != null) profile = overlay.adapt(stage.name(), profile, user.profiles());
            if (profile == null || !seen.add(profile.name())) continue;
            profiles.add(profile);
        }
        return profiles;
    }

    /**
     * The budget plan this loop records, from the same skip predicate it routes by.
     *
     * The preparation reservation calls this before the planner is paid so {@code run.json}
     * and the summary written a moment later cannot disagree about which stages count or
     * whether the cap can finish them.
     */
    static CallPlan planFor(Workflow workflow, UserConfig user, TaskSpec.ResolvedTask task,
                            List<String> preparationStages) {
        boolean reviewByRisk = user.policy() != null && user.policy().reviewRequired(task.risk());
        return new CallPlan(workflow,
                stage -> skipReason(stage, user, task, reviewByRisk) != null,
                preparationStages);
    }

    /**
     * Why a stage will not run at all, or null when it will.
     *
     * Shared by the loop, previews, and the preparation reservation so they agree on which
     * stages the run owes.
     */
    static String skipReason(Workflow.Stage stage, UserConfig user,
                                     TaskSpec.ResolvedTask task, boolean reviewByRisk) {
        if (stage.kind() == Workflow.Kind.ROLE) {
            // A review-only task judges a candidate somebody else already wrote. A writing
            // stage would add an author the readers then have to differ from — and a planned
            // implementer used to do exactly that in the preview, refusing a reader whose
            // vendor nobody was ever going to dispatch as a writer.
            if (task.reviewOnly() && !RoleResolver.judges(stage.role())) return "review_only";
            boolean configured = user.policy() != null
                    && user.policy().roles().containsKey(stage.role());
            if (!configured) return "role_not_configured";
        }
        for (String condition : stage.when()) {
            if (!holds(condition, task, reviewByRisk)) return "condition_not_met:" + condition;
        }
        return null;
    }

    static boolean judgesCandidate(Workflow.Stage stage, TaskSpec.ResolvedTask task) {
        return stage.kind() == Workflow.Kind.ROLE && (stage.onFindings() != null
                || (task.reviewOnly() && RoleResolver.judges(stage.role())));
    }

    /**
     * The operator's overlay, checked against the roster before anyone is paid.
     *
     * Three faults are knowable from configuration alone and all three used to surface as
     * something other than themselves. A stage name nobody in this workflow answers to
     * simply did nothing, silently — the commonest way to type one of these flags wrong.
     * An effort a runner cannot deliver was written into the evidence and never sent. A
     * {@code host: orca} on a profile with tool grants dropped them and kept the stamp. So
     * this runs first among the preflights: it is the cheapest of them, and the one whose
     * mistakes are a person's typing rather than a roster that has thinned.
     */
    static Map<String, Object> overlayGap(Path root, Workflow workflow, UserConfig user,
                                           TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                           RoleRunner roles, RunOverride overlay) {
        if (overlay == null || overlay.isEmpty()) return null;
        Set<String> named = new LinkedHashSet<>();
        for (Workflow.Stage stage : workflow.stages()) named.add(stage.name());
        for (String stage : overlay.stages()) {
            if (named.contains(stage)) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage);
            gap.put("code", "run_override_unknown_stage");
            gap.put("stop_reason", "run_override_invalid");
            gap.put("known_stages", List.copyOf(named));
            gap.put("message", "no stage called '" + stage + "' in this workflow, so the overlay "
                    + "naming it would do nothing. The stages that run are " + named
                    + ". Overlays are keyed by stage, not by role: two reviewer stages share a "
                    + "role and only one of them is the one you meant.");
            return gap;
        }
        // Machine gates and the browser harness have no profile, effort or host to change.
        // An overlay naming one was accepted and then skipped by every loop below, so
        // `--host browser=orca` ran exactly what it would have run without it.
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() == Workflow.Kind.ROLE || !overlay.stages().contains(stage.name())) continue;
            List<String> roleStages = workflow.stages().stream()
                    .filter(candidate -> candidate.kind() == Workflow.Kind.ROLE)
                    .map(Workflow.Stage::name).toList();
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage.name());
            gap.put("kind", stage.kind().name().toLowerCase(Locale.ROOT));
            gap.put("code", "run_override_not_a_role_stage");
            gap.put("stop_reason", "run_override_invalid");
            gap.put("role_stages", roleStages);
            gap.put("message", "stage '" + stage.name() + "' is "
                    + (stage.kind() == Workflow.Kind.MACHINE_GATES ? "the machine gates"
                        : "the browser harness")
                    + ", which no profile fills, so --use, --effort and --host have nothing to "
                    + "change there and would be ignored. The stages an overlay can change are "
                    + roleStages + "; nothing was dispatched.");
            return gap;
        }
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            // `peek` has already applied whatever the overlay could honour, so what comes
            // back is either the adapted profile — asked again, it reports no problem — or
            // the untouched one, which is exactly the case with something to say.
            Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
            if (chosen == null) continue;
            String problem = overlay.problem(stage.name(), chosen, user.profiles(), user);
            if (problem == null) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage.name());
            gap.put("role", stage.role());
            gap.put("profile", chosen.name());
            gap.put("code", "run_override_undeliverable");
            gap.put("stop_reason", "run_override_invalid");
            gap.put("message", problem);
            return gap;
        }
        return null;
    }

    /**
     * The first judging stage nobody can fill because its candidates may write, or null.
     *
     * The resolver refuses such a profile outright. Checked here, before anyone is paid and
     * ahead of the reader and tooling checks, because both would describe the same roster
     * wrongly: as a missing independent vendor, or as a visual profile to verify.
     */
    static Map<String, Object> judgeGap(Workflow workflow, UserConfig user,
                                                TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                                RoleRunner roles) {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE || !RoleResolver.judges(stage.role())) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            String message = Preparation.writableJudge(roles, user, stage.name(),
                    stage.role());
            if (message == null) continue;
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage.name());
            gap.put("role", stage.role());
            gap.put("code", RoleResolver.JUDGE_NOT_READ_ONLY);
            gap.put("blocked_at", "preflight");
            gap.put("message", message);
            return gap;
        }
        return null;
    }

    /**
     * The tools a stage's profile would need, checked before anyone is paid.
     *
     * Two faults are visible from the roster alone. A stage that declares `evidence: agent`
     * needs a visual profile that declared it takes its own screenshots; a profile that
     * names an MCP configuration needs the file to exist. Either one found at dispatch has
     * already spent the implementer and every reader before it — measured on the harness's
     * missing browser, where the same lesson cost an implementer and a passed review.
     */
    static Map<String, Object> toolingGap(Path root, Workflow workflow, UserConfig user,
                                           TaskSpec.ResolvedTask task, boolean reviewByRisk,
                                           RoleRunner roles) {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.kind() != Workflow.Kind.ROLE) continue;
            if (skipReason(stage, user, task, reviewByRisk) != null) continue;
            Profile chosen = roles.peek(root, user, stage.name(), stage.role(),
                    RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
            // Nobody to fill a required visual role used to fall through to dispatch, so the
            // writer and both readers ran and then the look stage died unverified. Bakery
            // would have paid that bill. Surface it here, with the resolver's reasons.
            if (chosen == null) {
                if (!"visual_qa".equals(stage.role())) continue;
                Map<String, Object> gap = new LinkedHashMap<>();
                gap.put("stage", stage.name());
                gap.put("role", stage.role());
                gap.put("code", "visual_qa_unavailable");
                gap.put("stop_reason", "visual_qa_unavailable");
                Map<String, String> rejected = roles.explainFill(user, stage.name(), stage.role(),
                        RoleResolver.Writers.NONE, workflow.rotationPositionOf(stage));
                gap.put("rejected_profiles", rejected == null ? Map.of() : rejected);
                gap.put("message", "visual QA is required and no eligible profile can fill stage '"
                        + stage.name() + "'"
                        + (rejected == null || rejected.isEmpty() ? "." : ": " + rejected)
                        + " For an MCP camera, run the profile's probe against your server, then "
                        + "`oseer profiles --verify <profile> --confirm`. Do not start the loop "
                        + "until that stamp exists.");
                return gap;
            }
            Map<String, Object> gap = new LinkedHashMap<>();
            gap.put("stage", stage.name());
            gap.put("role", stage.role());
            gap.put("profile", chosen.name());
            if (chosen.mcpConfig() != null && !Files.isRegularFile(user.resolve(chosen.mcpConfig()))) {
                gap.put("code", "mcp_config_missing");
                gap.put("stop_reason", "mcp_config_missing");
                gap.put("message", "profile '" + chosen.name() + "' names mcp.config "
                        + chosen.mcpConfig() + ", which does not exist under the config home. "
                        + "Write the vendor's MCP configuration there, or remove mcp.config and "
                        + "the {{mcp_config}} argument from the profile.");
                return gap;
            }
            if (stage.acquiresEvidence() && (chosen.vision() == null || !chosen.vision().acquires())) {
                gap.put("code", "visual_qa_no_acquiring_profile");
                gap.put("stop_reason", "visual_qa_unavailable");
                gap.put("message", "stage '" + stage.name() + "' declares evidence: agent, so the "
                        + "visual role has to take its own screenshots, and profile '" + chosen.name()
                        + "' does not declare capabilities.vision.acquires: true. Declare it on a "
                        + "profile whose tools can drive the application, or give the stage a "
                        + "harness with sees:.");
                return gap;
            }
        }
        return null;
    }

    private static boolean holds(String condition, TaskSpec.ResolvedTask task, boolean reviewByRisk) {
        return switch (condition) {
            case "review_required" -> task.reviewOnly() || reviewByRisk;
            case "visual_qa_required" -> task.visualQa().required();
            case "risk_low" -> "low".equals(task.risk());
            case "risk_medium" -> "medium".equals(task.risk());
            case "risk_high" -> "high".equals(task.risk());
            default -> true;
        };
    }
}
