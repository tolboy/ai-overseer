package dev.oseer;

import dev.oseer.cli.ApproveEnv;
import dev.oseer.cli.Continuation;
import dev.oseer.cli.ContractAmendment;
import dev.oseer.cli.DecisionSupervisor;
import dev.oseer.approval.*;
import dev.oseer.execution.orca.*;
import dev.oseer.json.Json;
import dev.oseer.run.ContractProposal;
import dev.oseer.testing.*;
import java.nio.file.*;
import java.util.*;

/** Operator decisions must perform the displayed action exactly once, across process restarts. */
public final class OperatorContinuationTest implements Suite {
    public String name() { return "operator-continuation"; }
    public void run(Check check) throws Exception {
        proposals(check);
        aliasedProject(check);
        supervisor(check);
        supervisionLimitIsVisible(check);
        amendmentReadiness(check);
        Path fake = Files.createTempFile("oseer-fake-image", ".png");
        Files.write(fake, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13});
        check.that("a PNG header is not visual evidence", !dev.oseer.run.AgentImageEvidence.isImage(fake));
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", fake.toFile());
        check.that("a decodable frame is image evidence", dev.oseer.run.AgentImageEvidence.isImage(fake));
        Files.delete(fake);
    }

    private void supervisionLimitIsVisible(Check check) throws Exception {
        Path root = project();
        ApprovalStore decisions = new ApprovalStore(root);
        Map<String, Object> summary = Map.of("decision_kind", "failure");
        for (int i = 1; i <= 101; i++) {
            String id = "bounded-" + i;
            HumanDecision pending = decisions.createFailure(id, "task", "baseline_failed",
                    root.resolve(".oseer/runs/" + id + "/task-run.json"), null);
            if (i <= 100) decisions.resolve(id, pending.updatedAt().toString(), "retry", "test", "");
        }
        var env = new ApproveEnv(() -> 0L, ignored -> {}, (path, gate) -> null, null);
        Map<String, Object> report = DecisionSupervisor.superviseGates(root, "bounded-1", summary,
                new String[] {"--no-workspace-status"}, env,
                (path, resolved, args, context) -> {
                    int index = Integer.parseInt(resolved.runId().substring("bounded-".length()));
                    return Map.of("started", true, "run_id", "bounded-" + (index + 1),
                            "summary_report", summary);
                },
                (path, id, shown) -> Map.of());
        List<?> chain = (List<?>) report.get("gate_chain");
        Map<?, ?> stop = (Map<?, ?>) chain.getLast();
        check.eq("the supervisor reports why it stopped", "supervision_limit_reached", stop.get("reason"));
        check.eq("the operator can identify the pending run", "bounded-101", stop.get("run_id"));
        check.eq("the next decision is still pending", HumanDecision.State.PENDING,
                decisions.read("bounded-101").state());
    }

    private Path project() throws Exception {
        Path root = Files.createTempDirectory("oseer-operator-");
        Files.createDirectories(root.resolve(".oseer/tasks"));
        Files.writeString(root.resolve(".oseer/project.yaml"),
                "version: 1\nproject: fixture\nchecks: { compile: [check] }\n");
        Files.writeString(root.resolve(".oseer/tasks/task.yaml"), """
                version: 1
                id: task
                goal: check content
                risk: low
                scope: [src]
                acceptance: [old-check]
                """);
        return root;
    }

    private void amendmentReadiness(Check check) throws Exception {
        Path root = project(), home = Files.createTempDirectory("oseer-amend-check-");
        new dev.oseer.config.UserSetup().run(home);
        String runId = "amend-check";
        Path file = root.resolve(".oseer/runs/" + runId + "/task-run.json");
        Files.createDirectories(file.getParent());
        Map<String, Object> summary = new LinkedHashMap<>(Map.of("run_id", runId, "task_id", "task",
                "reason", dev.oseer.run.TaskLoop.CONTRACT_AMENDMENT));
        Files.writeString(file, Json.write(summary));
        new ApprovalStore(root).createFailure(runId, "task", "contract_gap", file, null);
        int[] calls = {0};
        var env = new ApproveEnv(() -> 0L, ignored -> {}, (path, gate) -> null, home,
                (user, project, profiles, strict, progress) -> {
                    calls[0]++; return Map.of("ok", false, "code", "model_check_failed");
                });
        Map<String, Object> result = ContractAmendment.amendContract(root, runId, summary, new String[]{"--quiet"}, env);
        check.eq("automatic contract amendment checks access before planner dispatch", 1, calls[0]);
        check.eq("failed model check is visible in amendment result", "model_check_failed",
                ((Map<?, ?>) result.get("contract_amendment")).get("code"));
        check.that("failed check does not consume the one paid amendment attempt",
                !Files.exists(file.getParent().resolve(dev.oseer.run.TaskLoop.AMENDMENT_RECEIPT)));
    }

    private void aliasedProject(Check check) throws Exception {
        Path root = project();
        Path alias = root.resolveSibling(root.getFileName() + "-alias");
        linkDirectory(alias, root);
        try {
            Map<String, Object> proposal = ContractProposal.prepare(alias, summary());
            check.that("project directory aliases allow a validated proposal", !proposal.isEmpty());
            check.eq("proposal stays relative to the project", ".oseer/tasks/task.yaml", proposal.get("path"));
            Path outside = project();
            Path redirected = root.resolve(".oseer/tasks/redirected");
            linkDirectory(redirected, outside.resolve(".oseer/tasks"));
            try {
                Map<String, Object> request = summary();
                request.put("task_id", ".oseer/tasks/redirected/task.yaml");
                check.that("a redirect inside tasks is still refused",
                        ContractProposal.prepare(alias, request).isEmpty());
            } finally { Files.delete(redirected); }
        } finally { Files.delete(alias); }
    }

    private void linkDirectory(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) throw new java.io.IOException(output);
        } else Files.createSymbolicLink(link, target);
    }

    private Map<String, Object> summary() {
        return new LinkedHashMap<>(Map.of("task_id", "task", "next_step", Map.of(
                "kind", "fix_contract", "findings", List.of(Map.of("category", "contract_gap",
                        "proposed_acceptance", List.of("new-check --scene sample"))))));
    }

    private void proposals(Check check) throws Exception {
        Path root = project();
        Path task = root.resolve(".oseer/tasks/task.yaml");
        String before = Files.readString(task);
        Map<String, Object> summary = summary();
        Map<String, Object> proposal = ContractProposal.prepare(root, summary);
        check.that("a validated exact edit is prepared", !proposal.isEmpty());
        check.eq("preparing does not edit the task", before, Files.readString(task));
        summary.put("contract_proposal", proposal);
        Path file = root.resolve(".oseer/runs/run-1/task-run.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(summary));
        ApprovalStore store = new ApprovalStore(root);
        HumanDecision pending = store.createPending("run-1", "task", HumanDecision.Kind.CONTRACT_CHANGE,
                ContractProposal.question(proposal), file, null);
        check.that("phone question names executable commands", pending.reason().contains("new-check --scene sample"));
        Files.writeString(task, before + "# edited while waiting\n");
        try {
            store.resolve("run-1", pending.updatedAt().toString(), "apply", "tester", "",
                    current -> ContractProposal.apply(root, current));
            check.that("stale proposal is rejected", false);
        } catch (ApprovalException expected) { check.eq("stale edit code", "contract_changed", expected.code()); }
        check.eq("stale refusal leaves gate pending", HumanDecision.State.PENDING, store.read("run-1").state());
        Files.writeString(task, before);
        Map<String, Object> changed = new LinkedHashMap<>(proposal);
        changed.put("after", before + "# tampered\n");
        summary.put("contract_proposal", changed);
        Files.writeString(file, Json.write(summary));
        try { ContractProposal.apply(root, pending); check.that("tampered proposal rejected", false); }
        catch (ApprovalException expected) { check.eq("tamper code", "proposal_changed", expected.code()); }
        summary.put("contract_proposal", proposal);
        Files.writeString(file, Json.write(summary));
        store.resolve("run-1", pending.updatedAt().toString(), "apply", "tester", "",
                current -> ContractProposal.apply(root, current));
        check.eq("approved edit is exact", proposal.get("after"), Files.readString(task));
        check.eq("apply starts a fresh review chain", false, Continuation.continuation(root, "run-1").continuation().reuseJudgements());
        check.eq("duplicate cannot apply again", HumanDecision.State.RESOLVED, store.read("run-1").state());
        ContractProposal.apply(root, pending);
        check.eq("crash recovery is idempotent", proposal.get("after"), Files.readString(task));
    }

    private void supervisor(Check check) throws Exception {
        Path root = project();
        Path home = Files.createDirectories(root.resolve("home"));
        Map<String, Object> first = new LinkedHashMap<>(Map.of("run_id", "quota-1", "task_id", "task",
                "decision_kind", "failover", "orca_gate", Map.of("published", true),
                "authorized_failover", Map.of("review-first", "earlier"),
                "failover_pending", Map.of("role", "reviewer", "stage", "review-second", "to_profile", "gpt-oss")));
        Path file = root.resolve(".oseer/runs/quota-1/task-run.json");
        Files.createDirectories(file.getParent()); Files.writeString(file, Json.write(first));
        HumanDecision pending = new ApprovalStore(root).createFailover("quota-1", "task", "switch to gpt-oss", file, null);
        var gate = new OrcaLifecycle.Gate("gate1", "task_gate", "orca_run", "term1", "failover",
                pending.options(), pending.updatedAt().toString(), null, pending.createdAt());
        new OrcaLifecycle(root, "quota-1").mutate(OrcaLifecycle.of(snapshot -> snapshot.withOrcaRunId("orca_run").withGate(gate)));
        ApproveEnv env = new ApproveEnv(() -> 0L, millis -> {},
                (path, shown) -> new OrcaDecisionGate.Answer("gate1", "resolved", "switch", "switch?"), home);
        int[] starts = {0};
        Map<String, Object> result = DecisionSupervisor.superviseGates(root, "quota-1", first,
                new String[]{"--no-workspace-status"}, 1, env, (path, decision, args, environment) -> {
                    starts[0]++;
                    try {
                        var carried = Continuation.continuation(path, decision.runId());
                        check.eq("switch stays scoped to second reader", "gpt-oss", carried.failover().get("review-second"));
                        check.eq("previous approved substitution survives", "earlier", carried.failover().get("review-first"));
                        check.that("switch reuses paid judgements", carried.continuation().reuseJudgements());
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                    return Map.of("started", true, "run_id", "quota-2", "ok", true,
                            "summary_report", Map.of("decision_kind", "success"));
                });
        check.eq("confirmation automatically starts exactly one continuation", 1, starts[0]);
        check.eq("controller reports final run", "quota-2", result.get("continued_run_id"));
        check.eq("controller stops at human acceptance", true, result.get("continued_ok"));

        Path later = root.resolve(".oseer/runs/quota-2/task-run.json");
        Files.createDirectories(later.getParent());
        Files.writeString(later, Json.write(Map.of("run_id", "quota-2", "task_id", "task",
                "authorized_failover", Map.of("review-second", "gpt-oss", "review-first", "earlier"))));
        HumanDecision advanced = new ApprovalStore(root).createFailure("quota-2", "task",
                "contract_gap", later, null);
        new ApprovalStore(root).resolve("quota-2", advanced.updatedAt().toString(), "advance",
                "tester", "fixed checks");
        var carriedOn = Continuation.continuation(root, "quota-2");
        check.eq("advance keeps the confirmed second reader", "gpt-oss",
                carriedOn.failover().get("review-second"));
        check.eq("and the earlier confirmed substitution", "earlier",
                carriedOn.failover().get("review-first"));
        check.eq("and does not reuse verdicts after a contract edit", false,
                carriedOn.continuation().reuseJudgements());
    }
}
