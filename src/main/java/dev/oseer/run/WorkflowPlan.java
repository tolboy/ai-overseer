package dev.oseer.run;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.config.Workflow;

/** The admitted stage chain, including any adaptation made for the task's evidence mode. */
record WorkflowPlan(ConfigLoader.Loaded loaded, UserConfig user, Workflow workflow,
                    CallPlan callPlan, boolean dryRun) {
    TaskSpec.ResolvedTask task() {
        return loaded.resolved();
    }

    String skipReason(Workflow.Stage stage) {
        boolean reviewByRisk = user.policy() != null && user.policy().reviewRequired(task().risk());
        return WorkflowAdmission.skipReason(stage, user, task(), reviewByRisk);
    }
}
