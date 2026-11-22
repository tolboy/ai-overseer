package dev.oseer.cli;

import dev.oseer.execution.orca.OrcaClient;
import dev.oseer.execution.orca.OrcaDesktop;
import dev.oseer.execution.orca.OrcaWatchedDesktop;
import dev.oseer.execution.orca.OrcaWorkspace;
import dev.oseer.process.ProcessRunner;
import dev.oseer.run.Progress;
import dev.oseer.run.Workspace;

import java.nio.file.Path;

import static dev.oseer.cli.CliOptions.hasFlag;

/** Resolves presentation adapters after the execution worktree is known. */
public record RunObservation(Workspace.Source boards, DesktopSource desktops) {
    @FunctionalInterface
    public interface DesktopSource {
        OrcaDesktop attach(Path worktree, OrcaDesktop.Policy policy, Progress progress);
    }

    public static RunObservation live() {
        ProcessRunner processes = new ProcessRunner();
        OrcaClient orca = new OrcaClient(processes);
        return new RunObservation(worktree -> OrcaWorkspace.attach(processes, worktree),
                (worktree, policy, progress) -> OrcaDesktop.attach(orca, worktree, policy, progress));
    }

    public RunObservation withBoards(Workspace.Source source) {
        return new RunObservation(source, desktops);
    }

    Workspace.Source forStatus(String[] args) {
        return hasFlag(args, "--no-workspace-status") ? Workspace.Source.NONE : boards;
    }

    public Workspace.Source forRun(String[] args) {
        if (hasFlag(args, "--no-workspace-status") || hasFlag(args, "--dry-run")) {
            return Workspace.Source.NONE;
        }
        var policy = OrcaDesktop.Policy.current(hasFlag(args, "--quiet"));
        boolean watch = hasFlag(args, "--watch");
        return worktree -> {
            OrcaDesktop desktop = desktops.attach(worktree, policy, RunPresentation.narration(args));
            return watch ? OrcaWatchedDesktop.compose(desktop, boards.at(worktree)) : desktop;
        };
    }
}
