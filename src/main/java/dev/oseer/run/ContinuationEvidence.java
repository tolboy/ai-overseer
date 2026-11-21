package dev.oseer.run;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.config.RunOverride;
import dev.oseer.config.UserConfig;
import dev.oseer.config.Workflow;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.role.RoleContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies which gate receipts and role verdicts a continuation may carry. */
final class ContinuationEvidence {
    private final Path root;
    private final Progress progress;

    ContinuationEvidence(Path root, Progress progress) {
        this.root = root;
        this.progress = progress;
    }

    /** A prior run's persisted proof that every reproduction command failed on the base. */
    record ReproductionReceipt(String runId, Path report, String sha256, Object commands) {}

    /**
     * The candidate is expected to move after red proof. Bind reuse to the acceptance and
     * immutable base instead, and verify the original on-disk receipt even across multiple
     * continuations. A missing, redirected or corrupted receipt never becomes proof.
     */
    ReproductionReceipt reproduction(String priorRunId, String taskId,
                                     String acceptanceHash, String diffBaseCommit) {
        try {
            Path evidenceRoot = root.resolve(".oseer/runs").toAbsolutePath().normalize();
            Map<String, Object> prior = Json.parseObject(Files.readString(
                    evidenceRoot.resolve(priorRunId).resolve("task-run.json")));
            if (!Boolean.TRUE.equals(prior.get("reproduction_ok"))
                    || !taskId.equals(prior.get("task_id"))
                    || !acceptanceHash.equals(prior.get("acceptance_sha256"))
                    || !diffBaseCommit.equals(prior.get("diff_base_commit"))) return null;
            Object reportText = prior.get("reproduction_report");
            Object receiptRunBody = prior.get("reproduction_receipt_run");
            String receiptRun = receiptRunBody instanceof String value && !value.isBlank()
                    ? value : priorRunId;
            if (!(reportText instanceof String text) || text.isBlank()) return null;
            Path report = Path.of(text);
            if (!report.isAbsolute()) report = root.resolve(report);
            report = report.toAbsolutePath().normalize();
            Path expected = evidenceRoot.resolve(receiptRun + "--reproduction-0")
                    .resolve("reproduction-gate.json").normalize();
            if (!expected.startsWith(evidenceRoot) || !report.equals(expected)
                    || !Files.isRegularFile(report)) return null;
            String hash = GitRepository.contentSha256(report);
            if (!hash.equals(prior.get("reproduction_report_sha256"))) return null;
            Map<String, Object> receipt = Json.parseObject(Files.readString(report));
            if (!"reproduction".equals(receipt.get("phase"))
                    || !Boolean.TRUE.equals(receipt.get("ok"))
                    || !"passed".equals(receipt.get("code"))
                    || !taskId.equals(receipt.get("task_id"))
                    || !diffBaseCommit.equals(receipt.get("merge_base"))) return null;
            return new ReproductionReceipt(receiptRun, report, hash, receipt.get("commands"));
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** A prior run's independently persisted proof that project checks started green. */
    record BaselineReceipt(String runId, Path report, String sha256) {}

    /**
     * Reuse a baseline only when this is demonstrably the same continuation.
     *
     * <p>Running the baseline again unconditionally would mislabel a partial change from the
     * previous agent as a pre-existing project failure. Blind reuse is worse: a person could
     * edit the tree between runs and make somebody else's new failure the next agent's
     * problem. The prior decision's candidate fingerprint closes that gap. Any missing field
     * or unreadable receipt declines reuse and executes the baseline now.</p>
     */
    BaselineReceipt baseline(String priorRunId, String taskId, String contractHash,
                             String diffBaseCommit, String currentFingerprint, Map<String, Object> summary) {
        try {
            Path priorSummary = root.resolve(".oseer/runs").resolve(priorRunId)
                    .resolve("task-run.json");
            if (!Files.isRegularFile(priorSummary)) {
                return declineBaselineReuse(summary, "no summary for " + priorRunId);
            }
            Map<String, Object> prior = Json.parseObject(Files.readString(priorSummary));
            if (!taskId.equals(prior.get("task_id"))) {
                return declineBaselineReuse(summary, priorRunId + " belongs to another task");
            }
            if (!Boolean.TRUE.equals(prior.get("baseline_ok"))) {
                return declineBaselineReuse(summary, priorRunId
                        + " has no successful baseline receipt");
            }
            if (!contractHash.equals(prior.get("contract_sha256"))) {
                return declineBaselineReuse(summary, "the contract changed since " + priorRunId);
            }
            if (!diffBaseCommit.equals(prior.get("diff_base_commit"))) {
                return declineBaselineReuse(summary, "the immutable diff base changed since "
                        + priorRunId);
            }
            HumanDecision decision = new ApprovalStore(root).read(priorRunId);
            if (decision.candidateFingerprint() == null
                    || !decision.candidateFingerprint().equals(currentFingerprint)) {
                return declineBaselineReuse(summary, "the worktree changed since " + priorRunId);
            }
            Object reportText = prior.get("baseline_report");
            if (!(reportText instanceof String text) || text.isBlank()) {
                return declineBaselineReuse(summary, priorRunId
                        + " names no baseline report");
            }
            Object receiptRunBody = prior.get("baseline_receipt_run");
            String receiptRun = receiptRunBody instanceof String value && !value.isBlank()
                    ? value : priorRunId;
            Path report = Path.of(text);
            if (!report.isAbsolute()) report = root.resolve(report);
            report = report.toAbsolutePath().normalize();
            Path evidenceRoot = root.resolve(".oseer/runs").toAbsolutePath().normalize();
            Path expectedReport = evidenceRoot.resolve(receiptRun + "--baseline-0")
                    .resolve("baseline-gate.json").normalize();
            if (!report.equals(expectedReport) || !Files.isRegularFile(report)) {
                return declineBaselineReuse(summary, "the baseline report from " + priorRunId
                        + " is missing or is not the named baseline receipt");
            }
            Object expectedHash = prior.get("baseline_report_sha256");
            String actualHash = GitRepository.contentSha256(report);
            if (!(expectedHash instanceof String hash) || hash.isBlank()
                    || !hash.equals(actualHash)) {
                return declineBaselineReuse(summary, "the baseline report checksum from "
                        + receiptRun + " does not match");
            }
            Map<String, Object> receipt = Json.parseObject(Files.readString(report));
            if (!"baseline".equals(receipt.get("phase"))
                    || !Boolean.TRUE.equals(receipt.get("ok"))
                    || !"passed".equals(receipt.get("code"))
                    || !taskId.equals(receipt.get("task_id"))
                    || !contractHash.equals(receipt.get("contract_sha256"))
                    || !diffBaseCommit.equals(receipt.get("merge_base"))) {
                return declineBaselineReuse(summary, "the baseline receipt from " + receiptRun
                        + " does not prove this task, contract and diff base green");
            }
            return new BaselineReceipt(receiptRun, report, actualHash);
        } catch (Exception unreadable) {
            return declineBaselineReuse(summary, "the baseline receipt from " + priorRunId
                    + " is not verifiable: " + unreadable.getClass().getSimpleName());
        }
    }

    private BaselineReceipt declineBaselineReuse(Map<String, Object> summary, String why) {
        summary.put("baseline_reuse_declined", why);
        progress.line("base  not reusing an earlier baseline: " + why);
        return null;
    }

    /**
     * Role verdicts from an earlier run that are still true of this tree.
     *
     * A verdict is about a tree judged under terms, not about a run. Paying a second vendor
     * to reach the same conclusion about the same bytes under the same rules buys nothing;
     * accepting a verdict when either half has moved reports a review that never happened.
     *
     * Every condition here is a way to say no, and any doubt at all declines:
     *
     *  - the earlier run must have stopped for something that was never about the work;
     *  - the contract must be identical, or the acceptance surface must be, which forgives a
     *    raised call ceiling and nothing else;
     *  - the source must be identical, or it judged a different candidate;
     *  - the stage's own last attempt must have passed and filed no blocking findings, because
     *    a pass with a P1 is not a pass, it is a fix round that had not happened yet;
     *  - the stage's judging contract must be unchanged — see {@link RoleContract}.
     *
     * The first two are checked for the whole run and decline everything. The last two are
     * checked per stage, because a swapped reviewer says nothing about the implementer.
     *
     * The project baseline receipt is deliberately stricter and still requires a byte-exact
     * contract: re-running it costs time and no vendor call, so there is nothing to save by
     * forgiving anything there.
     */
    Map<String, Map<String, Object>> judgements(
            String priorRunId, String contractHash, String acceptanceHash,
            String fingerprint, Workflow workflow, UserConfig user,
            Map<String, Object> summary, RunOverride overlay, Map<String, String> substitutions) throws Exception {
        Path priorSummary = root.resolve(".oseer/runs").resolve(priorRunId).resolve("task-run.json");
        if (!Files.isRegularFile(priorSummary)) return declineReuse(summary, "no summary for " + priorRunId);
        Map<String, Object> prior = Json.parseObject(Files.readString(priorSummary));
        String priorReason = RunFailurePolicy.reasonForContinuation(prior);
        if (!priorReason.equals(String.valueOf(prior.get("reason")))) {
            summary.put("prior_reason_reclassified", Map.of(
                    "recorded", String.valueOf(prior.get("reason")), "read_as", priorReason));
        }
        if (RunFailurePolicy.isAboutTheWork(priorReason)) {
            return declineReuse(summary, priorRunId + " stopped with `" + priorReason
                    + "`, which is a verdict on the work; only a failure that was never about "
                    + "the work leaves an earlier judgement standing");
        }
        // The run before this one stopped so the planner could amend the acceptance. Its
        // readers judged the old terms, so none of their verdicts stand; the writer's product
        // is the candidate being judged again, and paying the writer to reproduce it would
        // buy the same tree. Only writer stages are kept, and only on the same bytes.
        // An amendment that did not go through left the contract as it was, and a person
        // answering retry on it is continuing on the same terms: those verdicts stand.
        boolean amended = RunFailurePolicy.CONTRACT_AMENDMENT.equals(priorReason)
                && !contractHash.equals(prior.get("contract_sha256"));
        if (amended) {
            progress.line("note  " + priorRunId + " sent its acceptance to the planner; the writer's "
                    + "product is kept and every reading is taken again under the amended contract");
        }
        if (!amended && !contractHash.equals(prior.get("contract_sha256"))) {
            // The contract moved. That is usually the end of it — but there is exactly one
            // way it moves that no reviewer would care about, and it is the way an operator is
            // forced to move it in order to continue at all. A run stopped by its call ceiling
            // can only go on if the ceiling goes up, and the ceiling lives in the same file as
            // the goal. Refusing on the whole-file hash would mean the act of continuing was
            // itself the reason to throw away what the continuation was meant to save.
            Object priorAcceptance = prior.get("acceptance_sha256");
            if (!(priorAcceptance instanceof String recorded) || !recorded.equals(acceptanceHash)) {
                return declineReuse(summary, "the contract changed since " + priorRunId
                        + (priorAcceptance == null
                            ? ", and that run predates the acceptance hash, so there is no way "
                              + "to show the change was only to its budget"
                            : ", and the change reaches what the work is judged by")
                        + ", so its roles were judged against different terms");
            }
            Map<String, Object> allowed = new LinkedHashMap<>();
            allowed.put("from_run", priorRunId);
            allowed.put("prior_max_role_runs", prior.get("budget_max_role_runs"));
            allowed.put("now_max_role_runs", summary.get("budget_max_role_runs"));
            allowed.put("prior_max_cost_usd", prior.get("budget_max_cost_usd"));
            allowed.put("now_max_cost_usd", summary.get("budget_max_cost_usd"));
            allowed.put("acceptance_sha256", acceptanceHash);
            // Recorded, not merely permitted. Whoever accepts this candidate is told that a
            // verdict was carried across an edited contract, and exactly which numbers moved.
            summary.put("contract_change_budget_only", allowed);
            progress.line("note  the contract changed since " + priorRunId + ", but only its "
                    + "budget: what the work is judged by is byte-identical, so the verdicts "
                    + "already reached still stand");
        }
        String priorFingerprint;
        try {
            priorFingerprint = new ApprovalStore(root).read(priorRunId).candidateFingerprint();
        } catch (Exception noDecision) {
            // The fingerprint is the whole basis for believing an earlier verdict. Without a
            // decision file there is nothing to check it against, and an unverifiable claim
            // that the tree has not moved is worth less than re-running the roles.
            return declineReuse(summary, "no recorded decision for " + priorRunId
                    + ", so there is no fingerprint to prove the tree has not moved");
        }
        if (priorFingerprint == null || !priorFingerprint.equals(fingerprint)) {
            return declineReuse(summary, "the worktree changed since " + priorRunId
                    + ", so its roles judged a different candidate");
        }
        // Keyed by stage, because a verdict belongs to a stage and not to a role.
        //
        // It used to be keyed by the step label, which is the role name. A chain that reviews
        // twice writes `reviewer` on both rows, so the second overwrote the first and the
        // survivor was then handed to whichever review stage asked first. The failure is
        // silent and in the worst direction: `review-second` exists precisely to be a second
        // pair of eyes, and a run could satisfy it with the first pair's verdict and report
        // two independent reviews. Legacy summaries carry no stage on their rows; those are
        // admitted only for a role the current workflow dispatches exactly once, where the
        // role name is an unambiguous name for the stage.
        Map<String, Map<String, Object>> reusable = new LinkedHashMap<>();
        Map<String, String> keyToRole = new LinkedHashMap<>();
        Object steps = prior.get("steps");
        // A summary this code wrote carries a `stage` on every stage row. When it does, only
        // stage rows are reuse candidates; a role row with no stage is a fix-round dispatch, an
        // intermediate step in some other stage's repair rather than a stage verdict of its
        // own, and reusing it keyed by its role would let an implementer's mid-repair state
        // stand in for a stage that never independently passed. The role-name fallback is kept
        // only for a legacy summary, which predates stage keying and has no stage on any row.
        boolean stageKeyed = false;
        if (steps instanceof List<?> scan) {
            for (Object item : scan) {
                if (item instanceof Map<?, ?> row && row.get("profile") != null
                        && row.get("stage") != null) {
                    stageKeyed = true;
                    break;
                }
            }
        }
        if (steps instanceof List<?> rows) {
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) continue;
                if (row.get("profile") == null) continue;
                String role = String.valueOf(row.get("step"));
                String key;
                if (row.get("stage") != null) {
                    key = String.valueOf(row.get("stage"));
                } else if (row.get("fix_for") != null && writerStageNamed(workflow, role) != null) {
                    // A fix round is the writer's latest product, not an intermediate step of
                    // someone else's stage. When the repair is the last thing the writer did,
                    // the tree it left is the tree being resumed, and paying the writer again
                    // to reproduce it bought nothing: measured, a continuation re-paid thirty
                    // minutes of implementer to redo a candidate it had already written. The
                    // row is admitted under the writer stage's name, and the fingerprint check
                    // below still decides whether it describes this tree. A judging stage's
                    // fix row never reaches here: those rows carry no `fix_for` of their own
                    // role, and a reader's verdict is never derived from a repair.
                    key = writerStageNamed(workflow, role);
                } else if (!stageKeyed && workflow.stagesFor(role).size() == 1) {
                    key = role;
                } else {
                    continue;
                }
                if (!Boolean.TRUE.equals(row.get("ok"))) {
                    // A repair that failed before it could write — rate limited, timed out —
                    // says nothing about the writer's last product; if it did move the tree,
                    // the fingerprint check below declines the stage row on its own. A stage
                    // row that failed is the stage's own verdict and still retires the key.
                    if (row.get("fix_for") == null) reusable.remove(key);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) row;
                reusable.put(key, entry);
                keyToRole.put(key, role);
            }
        }
        // A pass carrying a P1 is not a pass; it is a fix round that had not happened yet. The
        // count is read from the row when the row has one, because the summary's own
        // `blocking_findings` is keyed by role and so cannot answer for a chain that reviews
        // more than once.
        for (String key : List.copyOf(reusable.keySet())) {
            Map<String, Object> row = reusable.get(key);
            Object blocking = row.get("blocking_findings");
            if (blocking == null) {
                String role = keyToRole.get(key);
                blocking = prior.get("reviewer".equals(role) ? "blocking_findings"
                        : "visual_qa".equals(role) ? "visual_blocking_findings"
                        : role + "_blocking_findings");
            }
            if (blocking instanceof Number number && number.longValue() > 0) reusable.remove(key);
        }
        Map<String, String> declined = new LinkedHashMap<>();
        // The prior run may already have marked a verdict stale — a review that passed and was
        // then overtaken by a repair the workflow did not recheck. Its step row is still
        // `ok=true`, so nothing above drops it, but the run that produced it recorded that it
        // no longer described the candidate. Believing it now would resurrect exactly the
        // judgement that run had already retired.
        Set<String> priorStale = new LinkedHashSet<>();
        if (prior.get("stages_judging_an_earlier_candidate") instanceof List<?> staleRows) {
            for (Object staleRow : staleRows) priorStale.add(String.valueOf(staleRow));
        }
        for (String key : List.copyOf(reusable.keySet())) {
            if (priorStale.contains(key)) {
                reusable.remove(key);
                declined.put(key, "that run had already retired this verdict: a later repair "
                        + "changed the candidate and the stage was not rechecked");
            }
        }
        // The bytes each verdict actually judged, per stage, not the run as a whole. The
        // run-level fingerprint above proves the prior run's *final* tree matches; it cannot
        // prove that an individual stage judged that final tree, because a stage may have
        // passed an earlier revision inside a run that later changed the tree. So a stage whose
        // recorded candidate does not match the tree we are resuming on is dropped here, before
        // it can be listed as reusable at all.
        for (String key : List.copyOf(reusable.keySet())) {
            Object judged = reusable.get(key).get("candidate_fingerprint");
            if (!(judged instanceof String stamped) || stamped.isBlank()) {
                reusable.remove(key);
                declined.put(key, "its evidence records no candidate fingerprint, so there is "
                        + "no way to show it judged the tree being resumed");
            } else if (!stamped.equals(fingerprint)) {
                reusable.remove(key);
                declined.put(key, "it judged a different revision inside " + priorRunId
                        + " than the tree being resumed");
            }
        }
        // Whether the same judge, under the same rules, would be asked again.
        //
        // The hashes above prove the bytes have not moved. They cannot prove this: they cover
        // the project's `.oseer`, and who may fill a role, whether it must differ from the
        // implementer, its prompt and its schema all live in the operator's own home. Without
        // this an operator could swap the reviewer roster to another vendor, continue, and
        // have the new reviewer's stage satisfied by the old reviewer's verdict.
        //
        // Declined per stage, never globally. A changed reviewer says nothing about whether
        // the implementer's own stage still stands, and invalidating it too would charge for a
        // dispatch nothing had called into question.
        for (String key : List.copyOf(reusable.keySet())) {
            String difference = RoleContract.differenceFrom(
                    reusable.get(key).get("role_contract"),
                    currentStageFor(workflow, key, keyToRole.get(key)), user, overlay, substitutions);
            if (difference == null) continue;
            reusable.remove(key);
            declined.put(key, difference);
        }
        if (!declined.isEmpty()) {
            summary.put("reuse_declined_by_stage", declined);
            for (Map.Entry<String, String> entry : declined.entrySet()) {
                progress.line("note  not reusing the verdict for stage '" + entry.getKey()
                        + "': " + entry.getValue());
            }
        }
        if (amended) {
            for (String key : List.copyOf(reusable.keySet())) {
                if (!"implementer".equals(keyToRole.get(key))) reusable.remove(key);
            }
        }
        // `reused_judgements` is not set here. What is carried over is decided at the moment
        // each verdict is consumed, because a stage that looks reusable now can be invalidated
        // before its turn by an ordinary dispatch earlier in this same resume. The engine
        // records the stages it actually reused as it consumes them.
        return reusable;
    }

    /**
     * The stage a writing role owns, by name, or null when the chain dispatches that role
     * only in repairs. A fix row is admitted for reuse only under a stage that exists now.
     */
    private static String writerStageNamed(Workflow workflow, String role) {
        if (!"implementer".equals(role)) return null;
        List<Workflow.Stage> owned = workflow.stagesFor(role);
        return owned.size() == 1 ? owned.get(0).name() : null;
    }

    /**
     * The stage a reusable verdict would be reused for, in the workflow as it stands now, or
     * null when this workflow no longer has such a stage.
     *
     * The key is a stage name for anything this code wrote, and a role name only for a
     * summary old enough to predate stage-keyed rows — which is admitted at all only when the
     * role has exactly one stage, so the lookup is unambiguous either way. The whole stage is
     * returned, not just its routing, because its current role is the thing the recorded
     * contract has to match: a stage that kept its name but changed its role is a different
     * job, and returning only routing hid exactly that change.
     */
    private static Workflow.Stage currentStageFor(Workflow workflow, String key, String role) {
        for (Workflow.Stage stage : workflow.stages()) {
            if (stage.name().equals(key)) return stage;
        }
        List<Workflow.Stage> sharing = workflow.stagesFor(role);
        return sharing.size() == 1 ? sharing.get(0) : null;
    }

    private Map<String, Map<String, Object>> declineReuse(Map<String, Object> summary, String why) {
        summary.put("reuse_declined", why);
        progress.line("note  not reusing any earlier verdict: " + why);
        return Map.of();
    }
}
