package dev.oseer.run;

import dev.oseer.config.TaskSpec;
import dev.oseer.config.Workflow;
import dev.oseer.ledger.Findings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a person sees while a run is going, and when it stops.
 *
 * The terminal hears every line. The board hears what a person would act on.
 * The loop decides; this only says it.
 */
final class RunNarration {
    private RunNarration() {}

    /** Enough to judge by, few enough that the board still reads as a board. */
    private static final int MAX_REVEALED_IMAGES = 4;

    /** Up to ten paths, then how many more: enough to act on without flooding a line. */
    static String namedPaths(List<String> paths) {
        if (paths.size() <= 10) return String.join(", ", paths);
        return String.join(", ", paths.subList(0, 10)) + " and " + (paths.size() - 10) + " more";
    }

    static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    /** What is about to happen, before the first vendor is paid to do any of it. */
    static void header(Progress progress, Workspace workspace, TaskSpec.ResolvedTask task, String runId, Workflow workflow, boolean dryRun,
                        CallPlan plan, RunBudget budget, List<Map<String, Object>> cast) {
        progress.blank();
        progress.line("run   " + runId + (dryRun ? "   (dry run: nothing is dispatched)" : ""));
        progress.line("task  " + task.id() + "   risk=" + task.risk()
                + "   scope=" + String.join(", ", task.scopePaths()));
        List<String> names = new ArrayList<>();
        if (!task.baselineCommands().isEmpty()) names.add("baseline");
        for (Workflow.Stage stage : workflow.stages()) names.add(stage.name());
        progress.line("plan  " + String.join(" -> ", names));
        printCast(progress, cast);
        progress.line("bound " + task.budget().maxRoleRuns() + " vendor call(s), "
                + ("off".equals(task.budget().costCap()) ? "cost tracking only"
                : Progress.money(task.budget().maxCostUsd())
                + (task.budget().maxCostUsd() == 0 ? " (money limit disabled)"
                : task.budget().strictCostCap() ? " strict cost cap" : " of reported spend")) + ", fix rounds <= "
                + task.maxFixAttempts()
                + (task.budget().maxElapsedMinutes() == null ? ""
                        : ", " + task.budget().maxElapsedMinutes() + " min of execution"));
        RunChain earlier = budget.inherited();
        if (!earlier.runs().isEmpty()) {
            // The limits above are the chain's, and a continuation is told how much of them is
            // gone before it spends any more.
            progress.line("      continuing " + String.join(", ", earlier.runs()) + ": "
                    + earlier.roleRuns() + " call(s), " + Progress.money(earlier.costUsd())
                    + ", " + earlier.fixAttempts() + " fix round(s)"
                    + (earlier.elapsedKnown() ? ", " + earlier.elapsedSeconds() + " s"
                            : ", execution time not recorded")
                    + " already spent");
        }
        // The arithmetic, before anything is spent rather than after. An operator who is going
        // to be told at the end that the chain never finished is entitled to be told at the
        // start that it could not have.
        long cap = task.budget().maxRoleRuns();
        int minimum = plan.minimumToFinish();
        progress.line("cost  " + minimum + " vendor call(s)"
                + (task.reviewOnly() ? " for this review: " : " if nothing has to be repaired: ")
                + String.join(", ", plan.payingStages()));
        if (cap > 0 && cap < minimum) {
            progress.line("      WARNING: the cap of " + cap + " cannot reach the end of this "
                    + "chain" + (task.reviewOnly() ? "" : " even with no repairs")
                    + "; the run will stop with stages outstanding");
        } else if (cap > 0 && !task.reviewOnly()) {
            progress.line("      the cap of " + cap + " leaves " + (cap - minimum)
                    + " call(s) for repairs; each repair round costs its fix plus every "
                    + "judgement it invalidates");
        }
        progress.blank();
        if (!dryRun) {
            workspace.state(Workspace.State.RUNNING);
            // The chain with its cast, not the chain alone. "implement -> review -> look" is
            // the same sentence on every card an operator owns; "implement(grok) ->
            // review(openai) -> look(claude)" is the one that tells them which run to stop.
            workspace.note(task.id() + " · " + String.join(" -> ", castNames(names, cast))
                    + " · starting");
        }
    }

    /** Stage names with the vendor in brackets where the roster named one. */
    private static List<String> castNames(List<String> names, List<Map<String, Object>> cast) {
        Map<String, Object> vendors = new LinkedHashMap<>();
        for (Map<String, Object> row : cast) {
            if (row.get("vendor") != null) vendors.put(String.valueOf(row.get("stage")), row.get("vendor"));
        }
        List<String> labelled = new ArrayList<>();
        for (String name : names) {
            labelled.add(vendors.containsKey(name) ? name + "(" + vendors.get(name) + ")" : name);
        }
        return labelled;
    }

    /** The cast as a person reads it: one stage per line, aligned, with the overlay marked. */
    private static void printCast(Progress progress, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        int width = rows.stream().mapToInt(row -> String.valueOf(row.get("stage")).length())
                .max().orElse(0);
        String label = "cast  ";
        for (Map<String, Object> row : rows) {
            StringBuilder line = new StringBuilder(label);
            label = "      ";
            line.append(String.format("%-" + width + "s  ", row.get("stage")));
            if (row.get("profile") == null) {
                line.append("nobody can fill it");
                Object why = row.get("unresolved");
                if (why instanceof Map<?, ?> reasons && !reasons.isEmpty()) {
                    line.append(" — ").append(reasons);
                }
                progress.line(line.toString());
                continue;
            }
            line.append(row.get("profile")).append("  ").append(dev.oseer.config.Profile.modelLabel(
                    row.get("vendor") == null ? null : String.valueOf(row.get("vendor")),
                    row.get("model") == null ? null : String.valueOf(row.get("model"))));
            if (row.get("effort") != null) line.append(' ').append(row.get("effort"));
            if (!"direct".equals(row.get("runner"))) line.append(" · ").append(row.get("runner"));
            if (Boolean.TRUE.equals(row.get("from_overlay"))) line.append("   (this run only)");
            if (Boolean.FALSE.equals(row.get("from_overlay"))) line.append("   (overlay refused)");
            progress.line(line.toString());
            if (row.get("fallbacks") instanceof List<?> standby && !standby.isEmpty()) {
                progress.line(" ".repeat(6 + width + 2) + "standby " + String.join(" -> ",
                        standby.stream().map(String::valueOf).toList()) + "   (on failure: "
                        + row.get("on_vendor_failure") + ")");
            }
        }
    }

    /** One field of the gate this run published, or null if it published none. */
    private static String publishedGate(Map<String, Object> summary, String field) {
        Object published = summary.get("orca_gate");
        if (!(published instanceof Map<?, ?> map)) return null;
        if (!Boolean.TRUE.equals(map.get("published"))) return null;
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }

    /** Where the run ended and what the person watching is now expected to do about it. */
    static void footer(Progress progress, Workspace workspace, Path root, String runId, String reason, String nextAction, RunBudget budget,
                        Map<String, Object> summary) {
        progress.blank();
        progress.line("done  " + reason + "   " + budget.runs() + " vendor call(s), "
                + Progress.money(budget.spent()) + " charged");
        Object unpriced = summary.get("unpriced_calls");
        if (unpriced instanceof Number number && number.longValue() > 0) {
            progress.line("      " + number + " of those reported no price at all, so the "
                    + "$ ceiling did not measure them"
                    + (budget.unpricedCharge() > 0 ? "; the strict cap counted them at their "
                            + "declared bounds (" + Progress.money(budget.unpricedCharge()) + ")" : ""));
        }
        if ("human_gate".equals(nextAction)) {
            progress.line("      the candidate is ready and nothing has been landed");
        } else if ("human_escalation".equals(nextAction)) {
            progress.line("      stopped for a person; nothing has been landed");
        }
        // Said separately, because they are separate facts and a run can be a yes on one and
        // a no on the other. A stop that buried a clean review under one word is the reason
        // this exists: the reviewer's verdict was the most expensive thing the run produced.
        if (Boolean.TRUE.equals(summary.get("candidate_review_passed"))) {
            progress.line("      the candidate passed every review that ran");
        }
        // Only P1 stops the loop, so a reader's P2 or P3 used to end here as a clean pass. The
        // first all-Orca run hid two: the toggle button moves 30 px when the text hides, and the
        // acceptance never checks the text. The person deciding is owed what the readers said.
        List<Map<String, Object>> openFindings = Findings.openByStage(summary);
        if (!openFindings.isEmpty()) {
            progress.line("      " + openFindings.size() + " finding(s) the readers left open "
                    + "(they did not stop the loop; read them before deciding):");
            for (Map<String, Object> finding : openFindings.subList(0, Math.min(5, openFindings.size()))) {
                String message = String.valueOf(finding.get("message"));
                progress.line("        " + finding.get("severity") + " " + finding.get("category") + " · "
                        + finding.get("_stage") + ": "
                        + (message.length() > 160 ? message.substring(0, 157) + "..." : message));
            }
        }
        if (summary.get("pending_stages") instanceof List<?> pending && !pending.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Object row : pending) {
                if (row instanceof Map<?, ?> stage) {
                    names.add(stage.get("stage") + " (" + stage.get("reason") + ")");
                }
            }
            progress.line("      the workflow did not finish; outstanding: "
                    + String.join(", ", names));
        }
        if (summary.get("safe_next_step") instanceof String step) {
            progress.line("      next: " + step);
        }
        progress.line("      oseer report " + runId + " --text");
        Object options = summary.get("decision_options");
        if (options instanceof List<?> list && !list.isEmpty()) {
            progress.line("      oseer approve " + runId + " --decision <"
                    + list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|"))
                    + ">");
        }
        // An Orca gate renders nowhere in Orca's own UI — checked in 1.4.196 — so the way to
        // answer one has to be told, not discovered. From any Orca terminal, including a
        // phone's: bind the Run, then resolve the gate.
        String gate = publishedGate(summary, "gate_id");
        if (gate != null) {
            progress.line("      oseer approve " + runId + " --from-orca      (Orca gate " + gate + ")");
            progress.line("      to answer that gate from any Orca terminal, phone included:");
            progress.line("        orca orchestration run-use --id " + publishedGate(summary, "orca_run_id"));
            progress.line("        orca orchestration gate-resolve --id " + gate + " --resolution <"
                    + (options instanceof List<?> chosen && !chosen.isEmpty()
                        ? chosen.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining("|"))
                        : "choice") + ">");
        }
        progress.blank();

        // The card the whole channel exists for. A run that ends waiting for a person is the
        // one worth seeing from another room, so it carries what it cost and the command that
        // answers it — a phone can read the first and paste the second.
        workspace.state(Workspace.State.WAITING_FOR_HUMAN);
        StringBuilder note = new StringBuilder(
                "human_gate".equals(nextAction) ? "ready for you" : "stopped: " + reason);
        note.append(" · ").append(budget.runs()).append(" call(s) · ")
                .append(Progress.money(budget.spent()));
        if (options instanceof List<?> list && !list.isEmpty()) {
            note.append(" · oseer approve ").append(runId).append(" --decision ")
                    .append(list.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining("|")));
        }
        if (gate != null) note.append(" · or orca gate ").append(gate);
        workspace.note(note.toString());
        revealVisualEvidence(workspace, root, runId, summary);
    }

    /**
     * The pictures the visual stage was judged on, opened on the board at the gate.
     *
     * The verdict a person is about to accept or reject is a verdict about pixels, and the
     * only trace of those on the board was a digest in the ledger — so answering from a
     * phone meant answering on the role's word. The verified set comes first, because that
     * is what the verdict was measured against; a harness run that wrote its shots straight
     * into the evidence directory falls back to the directory.
     *
     * A few are opened, not all: a stage that photographed eleven states would bury the card
     * it was meant to support, and {@code oseer report} has the rest. Failing to open one
     * changes nothing — the run has already stopped and already decided.
     */
    private static void revealVisualEvidence(Workspace workspace, Path root, String runId, Map<String, Object> summary) {
        List<Path> pictures = new ArrayList<>();
        if (summary.get("steps") instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> step)) continue;
                if (!(step.get("image_evidence") instanceof List<?> images)) continue;
                for (Object item : images) {
                    if (item instanceof Map<?, ?> image && image.get("path") instanceof String path) {
                        pictures.add(Path.of(path));
                    }
                }
            }
        }
        if (pictures.isEmpty()) {
            // The harness does not write into the run's own directory: each stage gets a
            // sibling, `tally-3--visual-qa-0`, and the PNGs are under that. Found by
            // running this against a live browser stage and revealing nothing at all.
            Path runs = root.resolve(".oseer/runs");
            try (var siblings = Files.list(runs)) {
                for (Path directory : siblings.sorted().toList()) {
                    String name = directory.getFileName().toString();
                    if (!Files.isDirectory(directory)) continue;
                    if (!name.equals(runId) && !name.startsWith(runId + "--")) continue;
                    try (var found = Files.walk(directory, 3)) {
                        found.filter(Files::isRegularFile)
                                .filter(file -> file.getFileName().toString().toLowerCase()
                                        .endsWith(".png"))
                                .sorted()
                                .forEach(pictures::add);
                    }
                }
            } catch (Exception nothingToShow) {
                return;
            }
        }
        pictures.stream().limit(MAX_REVEALED_IMAGES).forEach(workspace::reveal);
    }

}
