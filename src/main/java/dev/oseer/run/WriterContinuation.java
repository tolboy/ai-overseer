package dev.oseer.run;

import dev.oseer.json.Json;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.ledger.Findings;
import dev.oseer.run.TaskLoop.Continuation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Writes the previous run's context for the first implementer that prepares to dispatch. */
final class WriterContinuation {
    private static final int MAX_REPORTED_CHANGED_FILES = 40;

    private final EvidenceLedger ledger;
    private final Continuation carried;
    private final CandidateBaseline baseline;
    private boolean delivered;

    WriterContinuation(WorkflowEvidence evidence, CandidateBaseline baseline) {
        this.ledger = evidence.ledger();
        this.carried = evidence.continuation();
        this.baseline = baseline;
    }

    /**
     * A human rejection, handed to the implementer as the context of its first attempt.
     *
     * Delivered once, to the implementer, and only at the start: after that the ordinary
     * fix-round context is what a stage failure has to say, and stacking a stale rejection
     * on top of a specific machine failure would bury the specific one.
     */
    Path contextFor(String role) throws Exception {
        if (delivered || !"implementer".equals(role)) return null;
        if (!carried.carriesNote()) return interruptedWriterContext();
        delivered = true;
        String quoted = carried.rejectionNote().replace("\n", "\n> ");
        String text = carried.carriesRejection() ? """
                # A person rejected the previous candidate

                Run `%s` reached the human gate and was **rejected**. This is not a failed
                check and not a reviewer's finding: it is the reason a person gave for not
                accepting the work, and it is the only feedback in this loop that cost
                somebody's attention rather than a vendor call.

                > %s

                The worktree still holds that candidate. Read what is there before writing
                anything, address this specific objection, and do not restart the task from
                scratch. If you believe the objection is wrong, say so in your summary with
                evidence rather than leaving it unaddressed.
                """.formatted(carried.fromRunId(), quoted) : """
                # A person advanced the previous run

                Run `%s` stopped, and a person answered **advance** with this note. This is
                not a rejection of a candidate that reached the gate: the previous run did
                not finish, and the note is what they want done on this one.

                > %s

                Read what is already in the worktree before writing anything, and address
                this note. Do not restart the task from scratch.
                """.formatted(carried.fromRunId(), quoted);
        // The person decided with the readers' open findings in front of them — the
        // decision page lists every one — so the writer is handed the same list.
        StringBuilder open = new StringBuilder();
        try {
            Path prior = ledger.projectRoot().resolve(".oseer/runs").resolve(carried.fromRunId())
                    .resolve("task-run.json");
            if (Files.isRegularFile(prior)) {
                for (Map<String, Object> finding : Findings.openByStage(
                        Json.parseObject(Files.readString(prior)))) {
                    open.append("- ").append(finding.get("severity")).append(" [")
                            .append(finding.get("category")).append("] ")
                            .append(finding.get("message")).append('\n');
                    for (String key : List.of("expected", "actual", "suggestion")) {
                        if (finding.get(key) != null) {
                            open.append("  - ").append(key).append(": ").append(finding.get(key)).append('\n');
                        }
                    }
                }
            }
        } catch (Exception unreadable) {
            open.setLength(0);
        }
        if (!open.isEmpty()) {
            String instructions = carried.carriesRejection()
                    ? "The person saw these when they rejected it. Close the ones the objection is "
                        + "about, and any product defect among them you can."
                    : "These findings were open when that run stopped. Address the ones relevant "
                        + "to the note and any product defect among them you can.";
            text += "\n## What the readers left open on that candidate\n\n"
                    + instructions + "\n\n" + open;
        }
        return RepairBrief.writeContext(ledger, 0, "rejection", text);
    }

    /**
     * The implementer the previous run cut off, handed to the one that picks up its tree.
     *
     * A writer stopped by its turn ceiling, its timeout or a stall leaves its edits in the
     * worktree, and `retry` or `advance` without a note dispatched the next implementer
     * with an empty `## Previous attempt`. Measured 2026-10-01 on Living-Horizon: the
     * continuation re-read the repository from the top and spent its whole 120-turn ceiling
     * the way the first attempt had spent its 40, on a tree that already held the change.
     * The next writer is told what stopped the last one and which files it left, and to
     * finish rather than rediscover.
     */
    private Path interruptedWriterContext() throws Exception {
        if (!carried.continuesRun()) return null;
        Map<String, Object> prior;
        try {
            Path file = ledger.projectRoot().resolve(".oseer/runs").resolve(carried.fromRunId())
                    .resolve("task-run.json");
            if (!Files.isRegularFile(file)) return null;
            prior = Json.parseObject(Files.readString(file));
        } catch (Exception unreadable) {
            return null;
        }
        String reason = String.valueOf(prior.get("reason"));
        if (!RunFailurePolicy.interruptedWriter(reason)) return null;
        if (!(prior.get("infrastructure_failure") instanceof Map<?, ?> failure)
                || !"implementer".equals(String.valueOf(failure.get("step")))) {
            return null;
        }
        delivered = true;
        List<String> changed;
        try {
            changed = baseline.changedSourcePaths();
        } catch (Exception unreadable) {
            changed = null;
        }
        StringBuilder text = new StringBuilder("""
                # The previous implementer was cut off

                Run `%s` stopped on `%s` while the implementer was still working. That is
                not a verdict on the work: no reader judged it. The worktree still holds
                what that implementer wrote.
                """.formatted(carried.fromRunId(), reason));
        if (changed == null) {
            text.append("\nCould not determine which source files differ from the base commit.\n");
        } else if (changed.isEmpty()) {
            text.append("\nNo source file differs from the base commit yet.\n");
        } else {
            text.append("\nFiles it already changed:\n\n");
            int shown = 0;
            for (String path : changed) {
                if (shown++ == MAX_REPORTED_CHANGED_FILES) {
                    text.append("- … and ").append(changed.size() - MAX_REPORTED_CHANGED_FILES).append(" more\n");
                    break;
                }
                text.append("- `").append(path).append("`\n");
            }
        }
        String next = changed == null
                ? "Inspect the worktree diff, finish what is missing, and run the"
                : "Start from those files: read them, finish what is missing, and run the";
        text.append("""

                %s
                acceptance checks once at the end. Do not restart the task, do not re-read
                the repository from the top, and do not spend turns checking the page by
                eye — the readers and the browser stage judge the result after you. The
                previous attempt used its whole budget; this one has the same limit.
                """.formatted(next));
        return RepairBrief.writeContext(ledger, 0, "interrupted", text.toString());
    }
}
