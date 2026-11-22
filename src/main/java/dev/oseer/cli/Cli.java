package dev.oseer.cli;

import dev.oseer.Main;
import dev.oseer.execution.WorktreeLease;

import java.util.Map;

import static java.util.Map.entry;

/** Dispatches commands and translates uncaught failures into the CLI's exit and output contract. */
public final class Cli {
    @FunctionalInterface
    private interface Command {
        int run(String[] args) throws Exception;
    }

    private final Map<String, Command> commands;

    public Cli() {
        this(RunObservation.live());
    }

    public Cli(RunObservation observation) {
        RunCommand runs = new RunCommand(observation);
        commands = Map.ofEntries(
                entry("validate", ProjectCommands::validate),
                entry("restore-contract", ProjectCommands::restoreContract),
                entry("recover", ProjectCommands::recover),
                entry("init", ProjectCommands::init),
                entry("gates", ProjectCommands::gates),
                entry("visual-qa", ProjectCommands::visualQa),
                entry("setup", args -> ProfileCommands.setup()),
                entry("profiles", ProfileCommands::profiles),
                entry("connections", ProfileCommands::connections),
                entry("check", ProfileCommands::check),
                entry("ping", ProfileCommands::check),
                entry("classify", RoleCommands::classify),
                entry("settings", args -> DashboardCommand.run(args, observation)),
                entry("roster", ProfileCommands::roster),
                entry("role", RoleCommands::role),
                entry("run", runs::runLoop),
                entry("do", runs::doIntent),
                entry("doctor", args -> InspectionCommands.doctor()),
                entry("dashboard", args -> DashboardCommand.run(args, observation)),
                entry("ledger", InspectionCommands::ledger),
                entry("report", InspectionCommands::report),
                entry("status", InspectionCommands::status),
                entry("approve", args -> ApprovalCommand.approve(args, observation)),
                entry("decide", args -> DecideCommand.decide(args, observation)),
                entry("land", ProjectCommands::land),
                entry("pr", ProjectCommands::pullRequest),
                entry("publish", ProjectCommands::pullRequest),
                entry("pilot", ProjectCommands::pilot),
                entry("probe", ProfileCommands::probe));
    }

    public int run(String[] args) {
        try {
            if (args.length == 0) {
                CliHelp.print();
                return 2;
            }
            String name = args[0];
            if (name.equals("--help") || name.equals("-h")) {
                CliHelp.print();
                return 0;
            }
            if (name.equals("--version")) {
                System.out.println("oseer " + Main.VERSION);
                return 0;
            }
            Command command = commands.get(name);
            if (command == null) {
                System.err.println("oseer: unknown command '" + name + "'");
                CliHelp.print();
                return 2;
            }
            return command.run(args);
        } catch (WorktreeLease.Refused refused) {
            CliOutput.json(refused.report());
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return CliOutput.unexpectedFailure(interrupted);
        } catch (Exception failure) {
            return CliOutput.unexpectedFailure(failure);
        }
    }
}
