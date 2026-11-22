package dev.oseer.run;

import dev.oseer.config.Workflow;
import dev.oseer.gate.GateRunner;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dispatches acceptance checks and supplies each visual reviewer with its declared harness output. */
final class WorkflowChecks {
    private final WorkflowPlan plan;
    private final CandidateBaseline baseline;
    private final WorkflowEvidence evidence;
    private final GateRunner gates;
    private final VisualStage visuals;
    private final Map<String, VisualQaRunner.Outcome> harness = new LinkedHashMap<>();

    WorkflowChecks(WorkflowPlan plan, CandidateBaseline baseline, WorkflowEvidence evidence,
                   GateRunner gates, VisualStage visuals) {
        this.plan = plan;
        this.baseline = baseline;
        this.evidence = evidence;
        this.gates = gates;
        this.visuals = visuals;
    }

    GateRunner.Outcome machineGates(Workflow.Stage stage, int attempt) throws Exception {
        String id = StageIds.stageRunId(evidence.runId(), plan.workflow(), stage, attempt);
        return GateStage.runGates(gates, plan.loaded(), id, attempt, plan.dryRun(), evidence.steps(),
                baseline.contractSnapshot(), baseline.diffBaseCommit());
    }

    VisualQaRunner.Outcome browserHarness(Workflow.Stage stage, int attempt) throws Exception {
        String id = StageIds.stageRunId(evidence.runId(), plan.workflow(), stage, attempt);
        VisualQaRunner.Outcome outcome = visuals.runVisual(plan.loaded(), id, attempt, plan.dryRun(),
                evidence.steps(), plan.user().home());
        harness.put(stage.name(), outcome);
        return outcome;
    }

    RoleRunner.Outcome visualReview(Workflow.Stage stage, int attempt, RoleRunner roles,
                                    RoleResolver.Writers avoid, RunBudget budget) throws Exception {
        VisualQaRunner.Outcome source = stage.acquiresEvidence() ? null : harness.get(stage.sees());
        return visuals.runVisualRole(plan.loaded(), plan.user(), roles, evidence.runId(),
                plan.workflow(), stage, attempt, avoid, source, plan.dryRun(), evidence.steps(), budget);
    }
}
