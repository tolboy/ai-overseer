package dev.oseer.cli;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.config.PlannerDraft;
import dev.oseer.config.Profile;
import dev.oseer.config.ProjectConfig;
import dev.oseer.config.RunOverride;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.dashboard.DecisionPage;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleRunner;
import dev.oseer.run.Preparation;
import dev.oseer.run.Progress;
import dev.oseer.run.RunReadiness;
import dev.oseer.run.TaskLoop;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.oseer.cli.ApprovalCommand.ApproveOutcome;
import static dev.oseer.cli.ApprovalCommand.approveDecision;
import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.GateWait.skippedGateWait;
import static dev.oseer.cli.RunPresentation.narration;
import static dev.oseer.cli.RunPresentation.narrationFile;

public final class ContractAmendment {
    private ContractAmendment() {}

    /**
     * Hand a passing run's contract gaps to the planner, and continue on the amended terms.
     *
     * The planner is given the contract and the readers' `contract_gap` findings and may only
     * add acceptance; the plan reviewer reads the amendment and may send it back once. When it
     * is written, the pending decision is answered `retry` by Overseer itself, naming the gaps,
     * and the supervisor starts the continuation: the writer's product is kept and every
     * reading is taken again under the amended contract. When it is not, the decision stays
     * pending and the page shows the gaps to a person.
     */
    public static Map<String, Object> amendContract(Path root, String runId, Map<String, Object> summary,
                                             String[] args, ApproveEnv env) {
        Map<String, Object> report = new LinkedHashMap<>();
        Progress narration = Progress.tee(narration(args),
                Progress.toFile(narrationFile(root, runId)));
        try {
            HumanDecision pending = new ApprovalStore(root).read(runId);
            if (pending.state() != HumanDecision.State.PENDING) {
                report.put("contract_amendment", skippedGateWait("already_decided"));
                return report;
            }
            // A supervisor that is not the first one for this run — `oseer decide`, or the
            // Dashboard's button after the original wait ended — finds the receipt of the
            // amendment the first one already paid for, and leaves the gaps to a person. The
            // reservation below is what decides it; this only saves reading the contract.
            if (Files.exists(root.resolve(".oseer/runs").resolve(runId)
                    .resolve(TaskLoop.AMENDMENT_RECEIPT))) {
                report.put("contract_amendment", skippedGateWait("amendment_already_attempted"));
                return report;
            }
            String taskId = String.valueOf(summary.get("task_id"));
            Path taskFile = root.resolve(".oseer/tasks").resolve(taskId + ".yaml");
            String before = Files.readString(taskFile);
            Path projectFile = root.resolve(".oseer/project.yaml");
            var project = ProjectConfig.parse(
                    Files.readString(projectFile), projectFile.toString());
            var spec = TaskSpec.parse(before, taskFile.toString());
            if (env.readiness != null) {
                UserConfig user = env.user();
                List<Profile> models = new ArrayList<>();
                for (String role : Preparation.payingRoles(user)) {
                    var roster = user.policy() == null ? null : user.policy().roles().get(role);
                    if (roster != null) for (String name : roster.profiles()) {
                        Profile profile = user.profiles().get(name);
                        if (profile != null && profile.verified()) models.add(profile);
                    }
                }
                Map<String, Object> checked = env.readiness.check(user, root, models,
                        spec.resolve(project, taskFile.toString()).budget().strictCostCap(), narration::line);
                if (!RunReadiness.permitsLaunch(checked, user, new ProcessRunner(), RunOverride.NONE,
                        Set.copyOf(Preparation.payingRoles(user)), narration::line)) {
                    report.put("contract_amendment", Map.of("ok", false, "code", "model_check_failed", "model_check", checked));
                    return report;
                }
            }
            List<String> scopes = spec.scope().entries();
            List<Map<String, Object>> gaps = new ArrayList<>();
            List<Object> ids = new ArrayList<>();
            for (Map<String, Object> finding : DecisionPage.openFindings(summary)) {
                if (!"contract_gap".equals(finding.get("category"))) continue;
                gaps.add(finding);
                ids.add(finding.get("id"));
            }
            narration.blank();
            narration.line("prep  the readers found the acceptance too weak (" + ids
                    + "); the planner amends it, the plan reviewer reads the amendment");
            // The chain's ceilings bound every call the amendment makes, not only the decision
            // to route there: read before the reservation below, which it would count as spent.
            RoleRunner.DispatchGate chainGate = TaskLoop.amendmentGate(root, runId,
                    spec.resolve(project, taskFile.toString()).budget(),
                    () -> env.clock.nowMillis() * 1_000_000L);
            // The chain pays for this, so the chain is told before the first call: the receipt
            // reserves the most an amendment can spend, and a crash leaves that reservation
            // standing rather than an amendment nobody counted.
            String amendRunId = runId + "-amend";
            Path receipt = root.resolve(".oseer/runs").resolve(runId).resolve(TaskLoop.AMENDMENT_RECEIPT);
            long started = env.clock.nowMillis();
            Map<String, Object> reserved = new LinkedHashMap<>();
            reserved.put("run_id", amendRunId);
            reserved.put("state", "reserved");
            reserved.put("role_runs", (long) Preparation.mostAmendmentCalls(env.user()));
            reserved.put("cost_usd", 0.0);
            reserved.put("unpriced_calls", (long) Preparation.mostAmendmentCalls(env.user()));
            reserved.put("gaps", ids);
            // Created, never replaced: one amendment per run is paid, whoever else gets here.
            try {
                Files.writeString(receipt, Json.write(reserved), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException another) {
                report.put("contract_amendment", skippedGateWait("amendment_already_attempted"));
                return report;
            }
            Preparation.Outcome amended = new Preparation(new ProcessRunner(), narration)
                    .amending(new Preparation.Amendment(before, gaps))
                    .within(chainGate)
                    .run(root, project, env.user(), taskId, amendRunId, spec.goal(),
                            scopes.size() == 1 ? scopes.get(0) : String.valueOf(scopes),
                            spec.risk(), PlannerDraft.Access.DO_DEFAULT, false);
            Map<String, Object> settled = new LinkedHashMap<>();
            settled.put("run_id", amendRunId);
            settled.put("state", "settled");
            settled.put("ok", amended.ok());
            settled.put("code", amended.code());
            settled.put("role_runs", (long) amended.roleRuns());
            settled.put("cost_usd", amended.costUsd());
            settled.put("unpriced_calls", (long) amended.unpriced());
            settled.put("elapsed_seconds", Math.max(0L, (env.clock.nowMillis() - started) / 1000L));
            settled.put("gaps", ids);
            Files.writeString(receipt, Json.write(settled));
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("ok", amended.ok());
            outcome.put("code", amended.code());
            if (amended.message() != null) outcome.put("message", amended.message());
            outcome.put("gaps", ids);
            outcome.put("role_runs", (long) amended.roleRuns());
            outcome.put("cost_usd", amended.costUsd());
            report.put("contract_amendment", outcome);
            if (!amended.ok()) {
                narration.line("      the amendment did not go through (" + amended.code()
                        + "); the gaps go to a person with the contract as it was");
                return report;
            }
            List<String> approve = new ArrayList<>(List.of("approve", runId,
                    "--decision", "retry", "--note", "acceptance amended by the planner for " + ids,
                    "--actor", "oseer:contract-amendment", "--no-start"));
            if (hasFlag(args, "--no-workspace-status")) approve.add("--no-workspace-status");
            ApproveOutcome retried = approveDecision(root, approve.toArray(String[]::new), env);
            outcome.put("decision", retried.report());
            narration.line(retried.ok()
                    ? "      contract amended; continuing with the writer's product and every reading taken again"
                    : "      contract amended, but the continuation could not be recorded: "
                            + retried.report().get("message"));
        } catch (Exception failed) {
            report.put("contract_amendment", Map.of("ok", false, "code", "amendment_failed",
                    "message", String.valueOf(failed.getMessage())));
            narration.line("      the amendment failed (" + failed.getMessage() + "); the gaps go to a person");
        }
        return report;
    }
}
