package dev.oseer.cli;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.dashboard.DecisionPage;
import dev.oseer.gate.CandidatePreview;
import dev.oseer.run.Progress;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.oseer.cli.ApprovalCommand.ApproveOutcome;
import static dev.oseer.cli.ApprovalCommand.approveDecision;
import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.GateWait.skippedGateWait;
import static dev.oseer.cli.RunPresentation.narration;
import static dev.oseer.cli.RunPresentation.narrationFile;

public final class DecisionSession {
    private static final Duration ORCA_GATE_POLL_INTERVAL = Duration.ofSeconds(10);
    private static final Duration DECISION_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration PAGE_RESPONSE_GRACE_PERIOD = Duration.ofMillis(1500);

    private DecisionSession() {}

    /** Opens a URL where the operator is; true when it was shown. */
    @FunctionalInterface
    public interface PageOpener {
        boolean open(String url) throws Exception;
    }

    public static Map<String, Object> awaitDecision(Path root, String runId, String[] args, int minutes,
                                             ApproveEnv env, PageOpener opener) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        ApprovalStore store = new ApprovalStore(root);
        HumanDecision pending;
        try {
            pending = store.read(runId);
        } catch (Exception none) {
            result.put("decision_page", skippedGateWait("no_pending_decision"));
            return result;
        }
        if (pending.state() != HumanDecision.State.PENDING) {
            result.put("decision_page", skippedGateWait("already_decided"));
            return result;
        }
        List<String> passThrough = new ArrayList<>();
        if (hasFlag(args, "--no-workspace-status")) passThrough.add("--no-workspace-status");
        Progress narration = Progress.tee(narration(args),
                Progress.toFile(narrationFile(root, runId)));
        boolean previewable = DecisionPage.offersPreview(root, pending.taskId());
        try (var preview = new CandidatePreview(root, pending.taskId(),
                narrationFile(root, runId).resolveSibling("decision-preview.log"), opener::open);
             var page = new DecisionPage(root, runId,
                     (choice, note, expected, finding) -> {
            List<String> approve = new ArrayList<>(List.of("approve", runId,
                    "--decision", choice, "--note", note, "--expected-updated-at", expected,
                    "--actor", "orca-decision-page", "--no-start"));
            if (finding != null && !finding.isBlank()) {
                approve.add("--finding");
                approve.add(finding);
            }
            approve.addAll(passThrough);
            return approveDecision(root, approve.toArray(String[]::new), env).report();
        }, previewable ? preview::open : null)) {
            page.start();
            try {
                DecisionPage.lease(root, runId, page.url());
            } catch (Exception unrecorded) {
                // The page still works, and no second supervisor can start: the supervisor
                // lock, not this file, keeps it out. Only the Dashboard cannot find the page.
            }
            try {
                boolean shown;
                try {
                    shown = opener.open(page.url());
                } catch (Exception failed) {
                    shown = false;
                }
                Map<String, Object> described = new LinkedHashMap<>();
                described.put("url", page.url());
                described.put("orca_tab_opened", shown);
                result.put("decision_page", described);
                narration.line(shown
                        ? "      decide in Orca: the Overseer decision tab in this worktree has a button "
                                + "for each option (or open " + page.url() + ")"
                        : "      decide here: " + page.url());

                long deadline = env.clock.nowMillis() + Duration.ofMinutes(Math.max(1, minutes)).toMillis();
                // The first read is at once. A "last read" sentinel of Long.MIN_VALUE overflowed
                // `clock - last` to a negative number, so the gate was never read at all.
                long nextGateRead = env.clock.nowMillis();
                while (true) {
                    HumanDecision now = store.read(runId);
                    if (now.state() != HumanDecision.State.PENDING) {
                        described.put("decided", now.decision());
                        described.put("actor", now.actor());
                        break;
                    }
                    long clock = env.clock.nowMillis();
                    // The Orca gate is still an answer, from a phone for instance. Read once per
                    // ten seconds; a pending or unreadable gate is simply not an answer yet.
                    if (clock >= nextGateRead) {
                        nextGateRead = clock + ORCA_GATE_POLL_INTERVAL.toMillis();
                        List<String> fromOrca = new ArrayList<>(
                                List.of("approve", runId, "--from-orca", "--no-start"));
                        fromOrca.addAll(passThrough);
                        ApproveOutcome imported = approveDecision(root, fromOrca.toArray(String[]::new), env);
                        if (imported.ok()) {
                            result.put("gate_import", imported.report());
                            continue;
                        }
                    }
                    if (clock >= deadline) {
                        described.put("timed_out", true);
                        narration.line("      the decision page closed after " + minutes + " min without an "
                                + "answer; the decision is still open: reopen its page from `oseer dashboard` "
                                + "or with oseer decide " + runId + ", or answer with oseer approve "
                                + runId + " --decision <option>");
                        break;
                    }
                    env.sleeper.sleep(DECISION_POLL_INTERVAL.toMillis());
                }
                // The page's own POST is answered after the store records it; give that response a
                // moment to reach the tab before the server goes away.
                env.sleeper.sleep(PAGE_RESPONSE_GRACE_PERIOD.toMillis());
            } finally {
                DecisionPage.release(root, runId, page.url());
            }
        }
        return result;
    }
}
