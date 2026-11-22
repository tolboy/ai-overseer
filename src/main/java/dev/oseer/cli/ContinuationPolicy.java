package dev.oseer.cli;

import dev.oseer.approval.HumanDecision;
import dev.oseer.config.OverseerTree;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;
import dev.oseer.role.RoleResolver;
import dev.oseer.run.NextStep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContinuationPolicy {
    private ContinuationPolicy() {}

    /**
     * Why starting the next run now would stop at the same wall, or null when it might not.
     *
     * {@code advance} means "I dealt with the blocker, go again". The blockers it is offered
     * for — a contract that does not permit the change, a scope that excludes the file, a
     * baseline that was already failing — are all things outside the loop, and none of them
     * is cleared by running the loop a second time. Measured: an advance answered on a
     * {@code preflight_outside_scope} stop started a second run that stopped on
     * {@code preflight_outside_scope}, with the contract byte-identical and no writer called.
     *
     * So the two things that would have to have changed are compared before a run id is
     * spent: the `.oseer` tree the contract is digested from, and the working tree the stop
     * was about. If neither moved, the answer is the refusal below rather than a second run
     * folder proving it. A tree Overseer cannot fingerprint is not an accusation — the run
     * starts, and the loop's own preflight has the last word as it always did.
     */
    public static String advanceWouldRepeat(Path root, HumanDecision resolved) {
        Map<String, Object> prior;
        try {
            Path file = root.resolve(".oseer/runs").resolve(resolved.runId()).resolve("task-run.json");
            if (!Files.isRegularFile(file)) return null;
            prior = Json.parseObject(Files.readString(file));
        } catch (Exception unreadable) {
            return null;
        }
        Object reason = prior.get("reason");
        if (!(reason instanceof String stopped)) return null;
        if (!REPEATS_WITHOUT_AN_EDIT.contains(stopped)) {
            return contractGapWouldRepeat(root, resolved, prior, stopped);
        }
        String contractNow;
        String treeNow;
        try {
            contractNow = OverseerTree.digest(
                    OverseerTree.snapshot(root));
            Object base = prior.get("diff_base_commit");
            if (!(base instanceof String commit)) return null;
            treeNow = new GitRepository(root, new ProcessRunner())
                    .sourceFingerprint(commit);
        } catch (Exception cannotTell) {
            return null;
        }
        if (!contractNow.equals(prior.get("contract_sha256"))) return null;
        if (resolved.candidateFingerprint() != null
                && !resolved.candidateFingerprint().equals(treeNow)) {
            return null;
        }
        return "advance would start a run that stops on the same thing: " + stopped
                + ". Neither the contract under .oseer nor the working tree has changed since "
                + resolved.runId() + " stopped, and that stop is not about anything the loop can "
                + "do differently on a second pass. Edit the contract (or the tree), then answer "
                + "advance again — `oseer report " + resolved.runId() + " --text` names what the "
                + "preflight objected to. To start one anyway, use the printed `oseer run` "
                + "command.";
    }

    /**
     * A stop whose remaining blockers are contract gaps, answered `advance` with the contract
     * untouched.
     *
     * Advance starts the next run from its writers, so it re-implements a candidate the readers
     * had no product defect against and then stops on the same gap: only an edit under
     * `.oseer` can close "the acceptance does not prove the goal". Measured 2026-10-01 on
     * Living-Horizon: a candidate whose four product findings were all fixed was advanced past
     * one `contract_gap` (browser scenarios too weak), and the next run dispatched the
     * implementer again from the top of the plan. A tree edit does not help here either, so
     * unlike {@link #REPEATS_WITHOUT_AN_EDIT} only the contract is compared.
     */
    private static String contractGapWouldRepeat(Path root, HumanDecision resolved,
                                                 Map<String, Object> prior, String stopped) {
        String kind = null;
        if (prior.get("next_step") instanceof Map<?, ?> step && step.get("kind") != null) {
            kind = String.valueOf(step.get("kind"));
        } else {
            try {
                kind = String.valueOf(NextStep.of(stopped, prior, root).get("kind"));
            } catch (Exception unclassifiable) {
                return null;
            }
        }
        if (!"fix_contract".equals(kind) || !CONTRACT_GAP_STOPS.contains(stopped)) return null;
        String contractNow;
        try {
            contractNow = OverseerTree.digest(
                    OverseerTree.snapshot(root));
        } catch (Exception cannotTell) {
            return null;
        }
        Object pinned = prior.get("contract_sha256");
        if (!(pinned instanceof String before) || !contractNow.equals(before)) return null;
        // An acceptance command can name a script in the tree, so strengthening it there closes
        // a gap without touching `.oseer`. A moved tree is given its run.
        try {
            Object base = prior.get("diff_base_commit");
            if (base instanceof String commit && resolved.candidateFingerprint() != null) {
                String treeNow = new GitRepository(root, new ProcessRunner())
                        .sourceFingerprint(commit);
                if (!resolved.candidateFingerprint().equals(treeNow)) return null;
            }
        } catch (Exception cannotTell) {
            return null;
        }
        String taskFile = resolved.taskId() == null || resolved.taskId().isBlank()
                ? ".oseer/tasks/<task>.yaml" : ".oseer/tasks/" + resolved.taskId() + ".yaml";
        return "advance would re-run the writers and stop on the same contract gap: " + stopped
                + " left only findings that say the acceptance does not prove the goal, and "
                + "the contract under .oseer has not changed since " + resolved.runId()
                + " stopped. No implementer can close a contract gap. Add the missing checks or "
                + "browser scenarios to " + taskFile + " (`oseer report " + resolved.runId()
                + " --text` lists the gaps and any proposed amendment), then answer advance "
                + "again; or abort and judge the candidate yourself.";
    }

    /**
     * Stops whose `fix_contract` comes from readers' `contract_gap` findings. A reproduction
     * that was inconclusive also maps to `fix_contract`, but its resolution is to clear a
     * timeout or gate error and run again on the same contract, so it is not refused here.
     */
    static final Set<String> CONTRACT_GAP_STOPS = Set.of(
            "blocking_findings_remain", "quality_exhausted");

    /**
     * Stops whose cause is outside the loop, so a fresh run over an unchanged contract and
     * an unchanged tree reaches the same line of code.
     */
    private static final Set<String> REPEATS_WITHOUT_AN_EDIT = Set.of(
            "preflight_outside_scope", "preflight_dirty_tree", "contract_invalid",
            "policy_invalid", "baseline_failed", "run_override_invalid",
            "independent_review_unavailable", "visual_qa_unavailable", "mcp_config_missing",
            RoleResolver.JUDGE_NOT_READ_ONLY);

    /** Decisions whose answer is "go on": the supervisor starts the continuation itself. */
    private static final List<String> CONTINUING_DECISIONS =
            List.of("switch", "advance", "apply", "retry", "replan", "proceed");

    /**
     * Whether an answer asks the supervisor to start the next run: a continuing decision, or
     * a rejection that says why. The note is what the next implementer is handed; a rejection
     * without one has nothing to carry and closes the work, as `--continue` always required.
     */
    static boolean continues(HumanDecision resolved) {
        if (CONTINUING_DECISIONS.contains(resolved.decision())) return true;
        return rejectedWithNote(resolved.kind(), resolved.decision(), resolved.note());
    }

    static boolean rejectedWithNote(HumanDecision.Kind kind, String decision, String note) {
        return kind == HumanDecision.Kind.SUCCESS && "reject".equals(decision)
                && note != null && !note.isBlank();
    }
}
