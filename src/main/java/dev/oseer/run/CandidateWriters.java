package dev.oseer.run;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.config.TaskSpec;
import dev.oseer.json.Json;
import dev.oseer.role.RoleResolver;
import dev.oseer.role.RoleRunner;
import dev.oseer.run.TaskLoop.Continuation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import static dev.oseer.run.WorkflowAdmission.allowsSameVendorPeer;

/** Tracks the authors of a candidate so reviewers are resolved against everyone who may have edited it. */
final class CandidateWriters {
    private final Path root;
    private final String runId;
    private final TaskSpec.ResolvedTask task;
    private final String diffBaseCommit;
    private final Map<String, Object> summary;
    private final Set<String> writerVendors = new LinkedHashSet<>();
    private final Set<String> writerProfiles = new LinkedHashSet<>();

    // Keep the reported provenance spelling when inheriting an older receipt.
    private String writerProvenance = "recorded";

    CandidateWriters(Path root, String runId, TaskSpec.ResolvedTask task, String diffBaseCommit,
                     Map<String, Object> summary) {
        this.root = root;
        this.runId = runId;
        this.task = task;
        this.diffBaseCommit = diffBaseCommit;
        this.summary = summary;
    }

    void restore(Continuation carried, BooleanSupplier hasCandidateChanges) {
        restoreWriters(carried, hasCandidateChanges);
        publishWriters();
    }

    void recordReusedWriter(Map<String, Object> standing) {
        Object vendor = standing.get("vendor");
        if (vendor == null) return;
        writerVendors.add(String.valueOf(vendor));
        Object profile = standing.get("profile");
        if (profile != null) writerProfiles.add(String.valueOf(profile));
        publishWriters();
    }

    boolean contains(String vendor) {
        return writerVendors.contains(vendor);
    }

    List<String> vendors() {
        return List.copyOf(new TreeSet<>(writerVendors));
    }

    String vendorNames() {
        return String.join(", ", vendors());
    }

    String vendorNamesOrNull() {
        return writerVendors.isEmpty() ? null : vendorNames();
    }

    /**
     * The writer set as the resolver needs it. The task decides whether a declared
     * same-vendor peer may stand in for an independent reader; the policy alone cannot.
     */
    RoleResolver.Writers readersAvoid() {
        return new RoleResolver.Writers(writerVendors, writerProfiles,
                "recorded".equals(writerProvenance) || "derived_from_steps".equals(writerProvenance)
                        || "declared_prior_writers".equals(writerProvenance),
                allowsSameVendorPeer(task));
    }

    /**
     * Add whoever this dispatch let write. A writing role's every vendor attempt counts,
     * including one that was failed over mid-edit; a read-only role counts only when the
     * fingerprint says it wrote anyway, at which point the run stops, but the set stays
     * honest for the continuation that inherits the tree.
     */
    void recordWriterOutcome(RoleRunner.Outcome outcome) {
        if (outcome == null || outcome.details() == null) return;
        Map<String, Object> details = outcome.details();
        boolean wrote = Boolean.FALSE.equals(details.get("read_only"));
        if (details.get("read_only_check") instanceof Map<?, ?> check
                && Boolean.FALSE.equals(check.get("matched"))) {
            wrote = true;
        }
        if (!wrote) return;
        if (details.get("vendor_attempts") instanceof List<?> attempts && !attempts.isEmpty()) {
            for (Object item : attempts) {
                if (!(item instanceof Map<?, ?> attempt)) continue;
                if (attempt.get("vendor") instanceof String vendor) writerVendors.add(vendor);
                if (attempt.get("profile") instanceof String profile) writerProfiles.add(profile);
            }
        } else {
            if (outcome.vendor() != null) writerVendors.add(outcome.vendor());
            if (outcome.profile() != null) writerProfiles.add(outcome.profile());
        }
        // Deliberately no promotion here. A writer dispatched now accounts for the bytes
        // it just wrote and for nothing that was in the tree before it. Treating a known
        // new author as proof of the whole candidate is how an unexplained file becomes
        // an `independent` verdict over work nobody can name.
        publishWriters();
    }

    private void publishWriters() {
        summary.put("writer_vendors", vendors());
        summary.put("writer_profiles", List.copyOf(new TreeSet<>(writerProfiles)));
        summary.put("writer_provenance", writerProvenance);
    }

    /**
     * A new run id on a tree an earlier run of this task left, identified by fingerprint.
     *
     * Bakery-7 paid two independent vendors and was then told nobody could be called
     * independent, because the run folder had a different name. That complaint is real,
     * and the answer is not to believe whichever run was written most recently: it is to
     * find the run whose recorded closing tree IS this tree. Two facts have to agree —
     * the immutable diff base, so "changed since" means the same thing in both runs, and
     * the closing candidate fingerprint the human decision recorded, so the prior writer
     * set accounts for every byte here and not merely for some earlier state.
     *
     * Anything less and the inheritance is a guess. A prior run whose own provenance was
     * unknown proves nothing either, however confidently it listed vendors, so it is not
     * a source. When no run matches, the tree stays {@code unknown_preexisting_candidate}
     * and every reading over it is labelled {@code unproven} — which is the honest
     * answer to "who wrote this", not a failure of the loop.
     */
    private boolean inheritWritersFromLatestSameTask() {
        return inheritWritersFromLatestSameTask(false);
    }

    /**
     * @param measuredOnly take the writers a matching run measured even when its own
     *                     provenance was unknown, and leave this run's provenance alone.
     *                     A declaration names who wrote the starting tree; it must not
     *                     erase a vendor Overseer itself saw write into it.
     */
    private boolean inheritWritersFromLatestSameTask(boolean measuredOnly) {
        Path runs = root.resolve(".oseer/runs");
        if (!Files.isDirectory(runs)) return false;
        Object here = summary.get("candidate_fingerprint");
        if (!(here instanceof String fingerprint) || fingerprint.isBlank()) return false;
        ApprovalStore decisions = new ApprovalStore(root);
        Map<String, Object> match = null;
        long matchTime = Long.MIN_VALUE;
        try (var stream = Files.list(runs)) {
            for (Path dir : stream.toList()) {
                if (!Files.isDirectory(dir)) continue;
                String priorRunId = dir.getFileName().toString();
                if (priorRunId.equals(runId)) continue;
                Path file = dir.resolve("task-run.json");
                if (!Files.isRegularFile(file)) continue;
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (modified < matchTime) continue;
                Map<String, Object> prior;
                try {
                    prior = Json.parseObject(Files.readString(file));
                } catch (Exception unreadable) {
                    continue;
                }
                if (Boolean.TRUE.equals(prior.get("dry_run"))) continue;
                if (!task.id().equals(prior.get("task_id"))) continue;
                if (!diffBaseCommit.equals(prior.get("diff_base_commit"))) continue;
                if (!(prior.get("writer_vendors") instanceof List<?> vendors) || vendors.isEmpty()) {
                    continue;
                }
                if (!measuredOnly && (!(prior.get("writer_provenance") instanceof String provenance)
                        || provenance.startsWith("unknown"))) {
                    continue;
                }
                String closed;
                try {
                    closed = decisions.read(priorRunId).candidateFingerprint();
                } catch (Exception noDecision) {
                    continue;
                }
                if (closed == null || !closed.equals(fingerprint)) continue;
                match = prior;
                matchTime = modified;
            }
        } catch (Exception listing) {
            return false;
        }
        if (match == null) return false;
        // Read into locals first: a half-applied inheritance would leave profile names in
        // the writer set with no vendor to account for them, which is a worse state than
        // the unknown one this is trying to improve on.
        Set<String> vendorsFound = new LinkedHashSet<>();
        Set<String> profilesFound = new LinkedHashSet<>();
        if (match.get("writer_vendors") instanceof List<?> vendorsListed) {
            for (Object vendor : vendorsListed) vendorsFound.add(String.valueOf(vendor));
        }
        if (match.get("writer_profiles") instanceof List<?> profilesListed) {
            for (Object profile : profilesListed) profilesFound.add(String.valueOf(profile));
        }
        if (vendorsFound.isEmpty()) return false;
        writerVendors.addAll(vendorsFound);
        writerProfiles.addAll(profilesFound);
        if (measuredOnly) {
            if (match.get("run_id") != null) {
                summary.put("measured_writers_from", String.valueOf(match.get("run_id")));
            }
            publishWriters();
            return true;
        }
        // The prior run's own word for how well it knew its writers, not a better one.
        writerProvenance = String.valueOf(match.get("writer_provenance"));
        if (match.get("run_id") != null) {
            summary.put("writers_inherited_from", String.valueOf(match.get("run_id")));
        }
        publishWriters();
        return true;
    }

    /**
     * The task's own word for who wrote the tree this run starts on (#46).
     *
     * A run on an existing PR branch starts with a candidate no Overseer run wrote, so its
     * authors are unknown and every reading is `unproven` — even when the reviewer's
     * vendor wrote nothing anyone can see. A person who knows the branch's authors names
     * their vendors in `prior_writers`; they join the writer set, so a reviewer of one of
     * those vendors is refused and any other is independent. The provenance says
     * `declared_prior_writers`, so the report never passes the claim off as measured.
     */
    private boolean declarePriorWriters() {
        if (task.priorWriters().isEmpty()) return false;
        inheritWritersFromLatestSameTask(true);
        writerVendors.addAll(task.priorWriters());
        writerProvenance = "declared_prior_writers";
        summary.put("prior_writers", List.copyOf(task.priorWriters()));
        publishWriters();
        return true;
    }

    /** Union the task's declared authors into a writer set known some other way. */
    private void addDeclaredPriorWriters() {
        if (task.priorWriters().isEmpty()) return;
        writerVendors.addAll(task.priorWriters());
        summary.put("prior_writers", List.copyOf(task.priorWriters()));
        publishWriters();
    }

    /**
     * What the tree already carries before this run writes anything.
     *
     * A continued run inherits the writers of the run it continues, and of every run that
     * one inherited: a rejection keeps the candidate in the tree, a retry keeps it, a
     * switch keeps it. A summary written before the field existed is read off its step
     * rows, which is evidence rather than a guess. A tree that differs from the diff base
     * with no run to account for it, or a continued tree that is not the one the prior
     * run left, is unknown, and stays unknown rather than being called clean.
     */
    private void restoreWriters(Continuation carried, BooleanSupplier hasCandidateChanges) {
        if (task.reviewOnly()) {
            declarePriorWriters();
            return;
        }
        boolean candidatePresent;
        try {
            candidatePresent = hasCandidateChanges.getAsBoolean();
        } catch (Exception unreadable) {
            candidatePresent = true;
        }
        if (!carried.continuesRun()) {
            if (candidatePresent) {
                if (inheritWritersFromLatestSameTask()) {
                    // A declaration made since that run still counts: a vendor a person
                    // now names as an author must not read the tree as independent.
                    addDeclaredPriorWriters();
                } else if (!declarePriorWriters()) {
                    // Keep writers a matching earlier run measured even when its own
                    // provenance was unknown; do not promote assurance - stay unknown.
                    inheritWritersFromLatestSameTask(true);
                    writerProvenance = "unknown_preexisting_candidate";
                    publishWriters();
                }
            }
            return;
        }
        Path file = root.resolve(".oseer/runs").resolve(carried.fromRunId())
                .resolve("task-run.json");
        Map<String, Object> prior = null;
        try {
            if (Files.isRegularFile(file)) prior = Json.parseObject(Files.readString(file));
        } catch (Exception unreadable) {
            prior = null;
        }
        String priorFingerprint = null;
        try {
            priorFingerprint = new ApprovalStore(root).read(carried.fromRunId())
                    .candidateFingerprint();
        } catch (Exception noDecision) {
            priorFingerprint = null;
        }
        if (prior == null) {
            writerProvenance = candidatePresent ? "unknown_preexisting_candidate" : "recorded";
            return;
        }
        if (prior.get("writer_vendors") instanceof List<?> vendorsListed) {
            for (Object vendor : vendorsListed) writerVendors.add(String.valueOf(vendor));
            if (prior.get("writer_profiles") instanceof List<?> profilesListed) {
                for (Object profile : profilesListed) writerProfiles.add(String.valueOf(profile));
            }
            // An unknown prior stays unknown however many vendors it managed to name:
            // the names are of runs that wrote, not of whatever was in the tree before
            // them, and that gap is exactly what `unknown` is the word for.
            String recorded = prior.get("writer_provenance") instanceof String text ? text : "recorded";
            writerProvenance = recorded.startsWith("unknown") || "declared_prior_writers".equals(recorded)
                    ? recorded : "recorded";
        } else {
            boolean anyWriter = false;
            if (prior.get("steps") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> step)) continue;
                    if (!"implementer".equals(step.get("step")) || step.get("vendor") == null) continue;
                    anyWriter = true;
                    writerVendors.add(String.valueOf(step.get("vendor")));
                    if (step.get("profile") != null) writerProfiles.add(String.valueOf(step.get("profile")));
                    if (step.get("vendor_attempts") instanceof List<?> attempts) {
                        for (Object item : attempts) {
                            if (item instanceof Map<?, ?> attempt && attempt.get("vendor") != null) {
                                writerVendors.add(String.valueOf(attempt.get("vendor")));
                                if (attempt.get("profile") != null) {
                                    writerProfiles.add(String.valueOf(attempt.get("profile")));
                                }
                            }
                        }
                    }
                }
            }
            writerProvenance = anyWriter || !candidatePresent ? "derived_from_steps"
                    : "unknown_preexisting_candidate";
        }
        Object now = summary.get("candidate_fingerprint");
        if (candidatePresent && (priorFingerprint == null || !priorFingerprint.equals(now))) {
            writerProvenance = "unknown_tree_moved";
        }
        if (candidatePresent) addDeclaredPriorWriters();
    }
}
