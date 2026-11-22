package dev.oseer.cli;

import dev.oseer.approval.StatusCommand;
import dev.oseer.config.ConfigLoader;
import dev.oseer.config.UserConfig;
import dev.oseer.ledger.CorpusImport;
import dev.oseer.ledger.LedgerCompare;
import dev.oseer.ledger.LedgerReader;
import dev.oseer.ledger.ProjectIdentity;
import dev.oseer.ledger.RunReport;
import dev.oseer.process.ProcessRunner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.oseer.cli.CliOptions.hasFlag;

/** Readiness and evidence queries. */
final class InspectionCommands {
    private InspectionCommands() {}

    static int doctor() throws Exception {
        return CliOutput.report(DoctorReport.inspect(Path.of("."), new ProcessRunner()));
    }

    static int ledger(String[] args) throws Exception {
        LedgerOptions options = LedgerOptions.parse(args);
        if (!options.imports().isEmpty()) {
            return CliOutput.report(CorpusImport.run(UserConfig.defaultHome(), options.imports()));
        }
        Path from = Path.of(".");
        LedgerReader reader = new LedgerReader();
        if (options.global()) {
            String projectId = corpusProjectId(from, options.projectId());
            CliOutput.json(reader.summarizeCorpus(UserConfig.defaultHome(), projectId));
            return 0;
        }
        Path root = new ConfigLoader().findProjectRoot(from);
        if (options.compare()) {
            Map<String, Object> report = LedgerCompare.compare(root, options.baselineFile());
            if (options.text()) System.out.print(LedgerCompare.render(report));
            else CliOutput.json(report);
        } else {
            CliOutput.json(reader.summarize(root));
        }
        return 0;
    }

    /** Blank identity selects legacy measurements from outside a configured project. */
    private static String corpusProjectId(Path from, String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            return projectId;
        }
        return new ConfigLoader().locateProjectRoot(from).map(ProjectIdentity::recorded)
                .filter(recorded -> !recorded.isBlank()).orElse(projectId);
    }

    /** Everything one run did: stages, vendors, cost, tokens, pixels, decision. */
    static int report(String[] args) throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        String runId = args.length > 1 && !args[1].startsWith("--") ? args[1] : null;
        if (runId == null) throw new IllegalArgumentException("report requires a run id");
        Map<String, Object> report = new RunReport().of(root, runId);
        if (hasFlag(args, "--text")) System.out.print(RunReport.render(report));
        else CliOutput.json(report);
        return 0;
    }

    /** Pending and resolved human decisions are Overseer state, not terminal prose. */
    static int status(String[] args) throws Exception {
        StatusCommand.Outcome outcome = new StatusCommand(new ProcessRunner()).run(Path.of("."), args);
        CliOutput.json(outcome.report());
        return outcome.ok() ? 0 : 1;
    }

    private record LedgerOptions(boolean global, boolean compare, boolean text, String projectId,
                                 Path baselineFile, List<Path> imports) {
        static LedgerOptions parse(String[] args) {
            boolean global = false;
            boolean compare = false;
            boolean text = false;
            String projectId = null;
            Path baselineFile = null;
            List<Path> imports = new ArrayList<>();
            for (int index = 1; index < args.length; index++) {
                String arg = args[index];
                switch (arg) {
                    case "--global" -> global = true;
                    case "--compare" -> compare = true;
                    case "--text" -> text = true;
                    case "--project-id" -> projectId = requiredValue(args, ++index, "ledger --project-id needs a value");
                    case "--baseline-file" -> baselineFile = Path.of(
                            requiredValue(args, ++index, "ledger --baseline-file needs a path"));
                    case "--import" -> imports.add(Path.of(requiredValue(args, ++index, "ledger --import needs a path")));
                    default -> throw new IllegalArgumentException("ledger: unknown argument '" + arg + "'");
                }
            }
            if (compare && !imports.isEmpty()) {
                throw new IllegalArgumentException("ledger --compare cannot be combined with --import");
            }
            if (!imports.isEmpty() && (global || projectId != null)) {
                throw new IllegalArgumentException("ledger --import cannot be combined with "
                        + (global ? "--global" : "--project-id"));
            }
            if (projectId != null && !global) {
                throw new IllegalArgumentException("ledger --project-id is only valid with --global");
            }
            if (compare && (global || projectId != null)) {
                throw new IllegalArgumentException("ledger --compare cannot be combined with "
                        + (global ? "--global" : "--project-id"));
            }
            if (text && !compare) {
                throw new IllegalArgumentException("ledger --text is only valid with --compare");
            }
            if (baselineFile != null && !compare) {
                throw new IllegalArgumentException("ledger --baseline-file is only valid with --compare");
            }
            return new LedgerOptions(global, compare, text, projectId, baselineFile, List.copyOf(imports));
        }

        private static String requiredValue(String[] args, int index, String missingMessage) {
            if (index >= args.length) {
                throw new IllegalArgumentException(missingMessage);
            }
            return args[index];
        }
    }
}
