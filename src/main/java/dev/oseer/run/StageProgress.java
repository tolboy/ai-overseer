package dev.oseer.run;

import dev.oseer.config.TaskSpec;
import dev.oseer.config.Workflow;
import dev.oseer.gate.GateRunner;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.role.RoleRunner;

import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;

/** Reports stage and repair progress to the terminal and the workspace. */
final class StageProgress {
    private final Progress progress;
    private final Workspace workspace;
    private final Workflow workflow;
    private final TaskSpec.ResolvedTask task;
    private final boolean dryRun;

    private Heartbeat heartbeat = Heartbeat.none();
    private BinaryOperator<String> heartbeatNote;
    /** The first stage a preview saw fail, with its code; null while none has. */
    private String previewStop;

    StageProgress(WorkflowPlan plan, Progress progress, Workspace workspace) {
        this.progress = progress;
        this.workspace = workspace;
        this.workflow = plan.workflow();
        this.task = plan.task();
        this.dryRun = plan.dryRun();
    }

    void line(String text) {
        progress.line(text);
    }

    void note(String text) {
        workspace.note(text);
    }

    void announceRepair(Workflow.Stage stage, int attempt, long roundsUsed) {
        line("      -> fix round " + roundsUsed + " of " + task.maxFixAttempts()
                + ": " + stage.name() + " sends the work back to " + stage.fixWith());
        note(fixNote(stage, attempt, null, null));
    }

    <T> T dispatch(Workflow.Stage stage, int attempt, Dispatch<T> action) throws Exception {
        try {
            return during(who(stage), (profile, vendor) -> runningNote(stage, attempt, profile, vendor), action);
        } catch (Exception failure) {
            if (!dryRun) workspace.stageEnded(false, position(stage) + " " + stage.name()
                    + " · stopped: " + (failure instanceof StopException stop
                        ? stop.getMessage() : failure.getClass().getSimpleName()));
            throw failure;
        }
    }

    <T> T repair(Workflow.Stage stage, int attempt, Dispatch<T> action) throws Exception {
        return during(stage.fixWith(), (profile, vendor) -> fixNote(stage, attempt, profile, vendor), action);
    }

    private <T> T during(String role, BinaryOperator<String> note, Dispatch<T> action) throws Exception {
        try (Heartbeat alive = beating(role)) {
            heartbeat = alive;
            heartbeatNote = note;
            return action.run();
        } finally {
            heartbeat = Heartbeat.none();
            heartbeatNote = null;
        }
    }

    void finished(Workflow.Stage stage, Object outcome, long millis) {
        narrate(stage, outcome, millis);
        post(stage, outcome, millis);
    }

    @FunctionalInterface
    interface Dispatch<T> {
        T run() throws Exception;
    }

    /**
     * A beat per minute while this stage runs, naming whoever is holding the loop.
     *
     * A dry run gets none: it dispatches nobody, returns in milliseconds, and a card that
     * announced a working role during a preview would be claiming work that never started.
     */
    private Heartbeat beating(String who) {
        return dryRun ? Heartbeat.none() : Heartbeat.over(who, progress, workspace);
    }

    /**
     * The name a person would use for whoever is busy. A role answers with its own name;
     * the two stages that call no vendor answer with what they are, because "machine_gates
     * still working" is the loop's vocabulary and not the operator's.
     *
     * The profile is not resolved here. Resolution lives in {@link RoleRunner} and
     * advances rotation state; asking twice could name a profile that was not dispatched.
     * The pair arrives via {@link RoleRunner.Occupied} from the resolution that happened.
     */
    private static String who(Workflow.Stage stage) {
        return switch (stage.kind()) {
            case MACHINE_GATES -> "gates";
            case VISUAL_HARNESS -> "browser harness";
            default -> stage.role();
        };
    }

    /**
     * The profile RoleRunner just chose. The beat and the card learn it here because
     * asking the resolver again would rotate twice.
     *
     * A carried-over verdict runs inside a beat but never calls RoleRunner, so nobody
     * publishes a name and the card stays the bare role. Anything dispatching outside a
     * beat leaves the builder null and this is a no-op rather than a sentence attached
     * to a stage that has already ended.
     */
    void named(String profile, String vendor) {
        heartbeat.filledBy(profile, vendor);
        BinaryOperator<String> note = heartbeatNote;
        if (dryRun || note == null) return;
        workspace.note(note.apply(profile, vendor));
    }

    /**
     * The card, after a stage. One line, replacing the last one — a board says where the
     * work is, and a person who wants the history has the terminal and the ledger.
     */
    private void post(Workflow.Stage stage, Object outcome, long millis) {
        if (dryRun) return;
        StringBuilder note = new StringBuilder(position(stage))
                .append(' ').append(stage.name()).append(" · ");
        if (outcome == null) {
            workspace.stageEnded(true, note.append("no dispatch").toString());
            return;
        }
        boolean ok = outcome instanceof RoleRunner.Outcome role ? role.ok()
                : outcome instanceof GateRunner.Outcome gate ? gate.ok()
                : !(outcome instanceof VisualQaRunner.Outcome visual) || visual.ok();
        if (outcome instanceof RoleRunner.Outcome role) {
            note.append(role.ok() ? "ok" : "failed " + role.code());
            Map<String, Object> details = role.details() == null ? Map.of() : role.details();
            if (details.get("vendor") != null) note.append(" · ").append(details.get("vendor"));
            if (details.get("cost_usd") instanceof Number) {
                note.append(" · ").append(Progress.money(details.get("cost_usd")));
            }
            if (details.get("verdict") != null) note.append(" · ").append(details.get("verdict"));
        } else if (outcome instanceof GateRunner.Outcome gate) {
            note.append(gate.ok() ? "ok" : "failed " + gate.code());
        } else if (outcome instanceof VisualQaRunner.Outcome visual) {
            note.append(visual.ok() ? "ok" : "failed " + visual.code());
        } else {
            workspace.stageEnded(ok, note.append("done").toString());
            return;
        }
        String line = note.append(" · ").append(Progress.elapsed(millis)).toString();
        workspace.note(line);
        workspace.stageEnded(ok, line);
    }

    private String position(Workflow.Stage stage) {
        return "[" + (1 + workflow.stages().indexOf(stage)) + "/" + workflow.stages().size() + "]";
    }

    /** `[3/5] review   role=reviewer` — where the loop is, in the plan it printed. */
    void announce(Workflow.Stage stage, int attempt) {
        String what = switch (stage.kind()) {
            case MACHINE_GATES -> "machine gates: " + String.join(", ", task.acceptanceCommands());
            case VISUAL_HARNESS -> "browser harness: " + task.visualQa().scenarios().size()
                    + " scenario(s) at " + task.visualQa().url();
            default -> "role " + stage.role();
        };
        progress.line(position(stage) + " "
                + RunNarration.pad(stage.name(), 10)
                + (attempt > 0 ? "(after fix " + attempt + ")  " : "") + what);
        // The card is told before the stage as well as after it, because the stage that
        // most needs a card is the one that takes sixteen minutes to answer. The profile
        // is not known yet — resolution happens inside the dispatch — so this line names
        // the role, and named() rewrites it with the pair as soon as it is.
        if (!dryRun) {
            workspace.note(runningNote(stage, attempt, null, null));
            workspace.stage(position(stage) + " " + stage.name());
        }
    }

    /**
     * `fix 1/2 · review sent the work back to implementer (grok-implement / grok)`.
     *
     * The same shape the card already used, with the bracketed pair once the resolution
     * that answers the fix has chosen one. Written twice on purpose: the first call
     * happens before the dispatch, when nobody knows who will take it.
     */
    private String fixNote(Workflow.Stage stage, int attempt, String profile, String vendor) {
        return "fix " + attempt + "/" + task.maxFixAttempts() + " · " + stage.name()
                + " sent the work back to "
                + Heartbeat.spoken(stage.fixWith(), profile, vendor);
    }

    private String runningNote(Workflow.Stage stage, int attempt, String profile, String vendor) {
        return position(stage) + " " + stage.name() + " · "
                + Heartbeat.spoken(who(stage), profile, vendor)
                + " · running"
                + (attempt > 0 ? " (after fix " + attempt + ")" : "");
    }

    /** How it went, in the same two-line shape for every kind of stage. */
    private void narrate(Workflow.Stage stage, Object outcome, long millis) {
        if (outcome == null) return;
        StringBuilder line = new StringBuilder("      ");
        if (outcome instanceof RoleRunner.Outcome role) {
            line.append(role.ok() ? "ok" : "FAILED ").append("  ").append(Progress.elapsed(millis));
            Map<String, Object> details = role.details() == null ? Map.of() : role.details();
            if (details.get("tokens") instanceof Map<?, ?> tokens) {
                line.append("   tokens ").append(tokens.get("input")).append("/").append(tokens.get("output"));
            }
            // `cost_usd` first, and `?` when it is absent. `attempts_cost_usd` is a sum,
            // and a sum of nothing is 0.0 — printing that would tell the operator a call
            // was free when what happened is that nobody priced it, which is the exact
            // confusion the report's `?` exists to prevent. The sum is only better when
            // it is actually carrying something: a role that failed over paid twice.
            Object cost = details.get("cost_usd");
            if (cost == null && details.get("attempts_cost_usd") instanceof Number summed
                    && summed.doubleValue() > 0) {
                cost = summed;
            }
            line.append("   cost ").append(Progress.money(cost));
            if (details.get("verdict") != null) line.append("   verdict ").append(details.get("verdict"));
            if (!role.ok()) line.append("   ").append(role.code());
            if (!role.ok() && details.get("message") != null) {
                line.append("\n      ").append(details.get("message"));
            }
            // An API reader that was sent the diff without whole files is reading less
            // than it was configured to; the operator hears it here, not from the artifact.
            if (details.get("api_context") instanceof Map<?, ?> context
                    && "diff".equals(context.get("mode"))) {
                line.append("\n      api context: diff only");
                if (context.get("fallback_reasons") instanceof List<?> reasons && !reasons.isEmpty()) {
                    line.append(" — whole files left out: ").append(String.join("; ",
                            reasons.stream().map(String::valueOf).toList()));
                }
            }
        } else if (outcome instanceof GateRunner.Outcome gate) {
            line.append(gate.ok() ? "ok" : "FAILED ").append("  ").append(Progress.elapsed(millis));
            if (!gate.ok()) line.append("   ").append(gate.code());
            if (!gate.ok() && gate.data() != null && gate.data().get("violations") instanceof List<?> outside
                    && !outside.isEmpty()) {
                line.append("\n      outside scope ").append(gate.data().get("scope_paths")).append(": ")
                        .append(RunNarration.namedPaths(outside.stream().map(String::valueOf).toList()));
            }
        } else if (outcome instanceof VisualQaRunner.Outcome visual) {
            line.append(visual.ok() ? "ok" : "FAILED ").append("  ").append(Progress.elapsed(millis));
            Object images = visual.data() == null ? null : visual.data().get("image_evidence");
            if (images instanceof List<?> shots) line.append("   ").append(shots.size()).append(" screenshot(s)");
            if (!visual.ok()) {
                line.append("   ").append(visual.code());
                Object message = visual.data() == null ? null : visual.data().get("message");
                if (message != null) line.append("\n      ").append(message);
            }
        } else {
            return;
        }
        // A preview keeps walking after a stage it knows would fail, to show who the later
        // stages resolve to. Those later lines are then about a chain no real run reaches,
        // and they can disagree with the cast printed above (which assumed the failing
        // writer wrote). Say so on the line instead of printing a bare "ok".
        boolean failedNow = outcome instanceof RoleRunner.Outcome r ? !r.ok()
                : outcome instanceof GateRunner.Outcome g ? !g.ok()
                : !((VisualQaRunner.Outcome) outcome).ok();
        if (dryRun && previewStop != null) {
            line.append("\n      preview only: a real run stops earlier, at ").append(previewStop);
        } else if (dryRun && failedNow) {
            previewStop = stage.name() + " (" + (outcome instanceof RoleRunner.Outcome r ? r.code()
                    : outcome instanceof GateRunner.Outcome g ? g.code()
                    : ((VisualQaRunner.Outcome) outcome).code()) + ")";
        }
        progress.line(line.toString());
    }
}
