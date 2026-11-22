package dev.oseer.cli;

import dev.oseer.config.ConfigLoader;
import dev.oseer.config.ProjectInitializer;
import dev.oseer.config.RestoreContractCommand;
import dev.oseer.config.TaskSpec;
import dev.oseer.config.UserConfig;
import dev.oseer.execution.WorktreeLease;
import dev.oseer.gate.GateRunner;
import dev.oseer.gate.VisualQaRunner;
import dev.oseer.ledger.EvidenceLedger;
import dev.oseer.process.ProcessRunner;
import dev.oseer.run.LandCommand;
import dev.oseer.run.PilotPrepareCommand;
import dev.oseer.run.PullRequestCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.oseer.cli.CliOptions.defaultRunId;
import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.CliOptions.option;

/** Project configuration, verification, and release commands. */
public final class ProjectCommands {
    private static final String DEFAULT_BASE_REF = "origin/main";
    private static final String INVALID_SCENARIO = "visual_qa_contract_invalid";

    private ProjectCommands() {}

    static int init(String[] args) throws Exception {
        Path directory = Path.of(".");
        ProjectInitializer initializer = new ProjectInitializer();
        if (hasFlag(args, "--scan")) {
            CliOutput.json(scanReport(initializer.scan(directory)));
            return 0;
        }
        String baseRef = option(args, "--base-ref", DEFAULT_BASE_REF);
        ProjectInitializer.Result initialized = initializer.initialize(directory, baseRef);
        CliOutput.json(initializationReport(initialized));
        return 0;
    }

    private static Map<String, Object> scanReport(ProjectInitializer.Scan scan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("writes", false);
        result.put("detected_build", scan.detectedBuild());
        result.put("checks", scan.checks());
        result.put("scopes", scan.scopes());
        result.put("greenfield", scan.greenfield());
        result.put("detected_from", scan.evidence());
        result.put("next", "oseer init writes these into .oseer/project.yaml; review them first");
        return result;
    }

    private static Map<String, Object> initializationReport(ProjectInitializer.Result initialized) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("detected_build", initialized.detectedBuild());
        result.put("project_file", initialized.projectFile().toString());
        result.put("example_task", initialized.exampleTask().toString());
        result.put("checks", initialized.checks());
        result.put("next", "review files, then run oseer validate example");
        return result;
    }

    static int validate(String[] args) throws Exception {
        return validate(Path.of("."), taskArgument(args));
    }

    /**
     * Load the task and refuse a browser scenario the harness cannot run.
     *
     * {@code oseer run} and {@code --dry-run} already ask the adapter before the first
     * vendor is paid. Validate used to print {@code ok} for a step the grammar does not
     * have, so a typo was discovered only when the browser stage ran. The grammar stays
     * in the adapter: a missing node is not a failure here, the same as preflight.
     */
    public static int validate(Path root, String task) throws IOException {
        ConfigLoader.Loaded loaded = new ConfigLoader().load(root, task);
        String scenarioProblem = new VisualQaRunner(new ProcessRunner()).contractProblem(loaded);
        return CliOutput.report(validationReport(loaded, scenarioProblem));
    }

    private static Map<String, Object> validationReport(ConfigLoader.Loaded loaded, String scenarioProblem) {
        TaskSpec.ResolvedTask task = loaded.resolved();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", scenarioProblem == null);
        if (scenarioProblem != null) {
            result.put("code", INVALID_SCENARIO);
            result.put("message", scenarioProblem);
        }
        result.put("project", loaded.project().project());
        result.put("task_id", task.id());
        if (scenarioProblem == null) {
            result.put("risk", task.risk());
            result.put("scope_paths", task.scopePaths());
            result.put("baseline_commands", task.baselineCommands());
            result.put("acceptance_commands", task.acceptanceCommands());
        }
        return result;
    }

    static int gates(String[] args) throws Exception {
        String task = taskArgument(args);
        String runId = option(args, "--run-id", defaultRunId());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), task);
        UserConfig user = UserConfig.load();
        GateRunner.Outcome outcome = new GateRunner(new ProcessRunner()).withHome(user.home())
                .run(loaded, runId);
        return CliOutput.report(gateReport(outcome.ok(), outcome.code(), outcome.report(),
                new EvidenceLedger(loaded.root(), runId, user.home())));
    }

    static int visualQa(String[] args) throws Exception {
        String task = taskArgument(args);
        String runId = option(args, "--run-id", defaultRunId());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), task);
        UserConfig user = UserConfig.load();
        VisualQaRunner.Outcome outcome = new VisualQaRunner(new ProcessRunner()).withHome(user.home())
                .run(loaded, runId);
        return CliOutput.report(gateReport(outcome.ok(), outcome.code(), outcome.report(),
                new EvidenceLedger(loaded.root(), runId, user.home())));
    }

    private static Map<String, Object> gateReport(boolean ok, String code, Path report, EvidenceLedger ledger) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", ok);
        result.put("code", code);
        result.put("report", report.toString());
        result.putAll(ledger.corpusVisibility());
        return result;
    }

    private static String taskArgument(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(args[0] + " requires a task id or YAML path");
        }
        return args[1];
    }

    static int restoreContract(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("restore-contract requires a run id");
        Map<String, Object> report = new RestoreContractCommand()
                .run(Path.of("."), args[1]);
        return CliOutput.report(report);
    }

    static int recover(String[] args) throws Exception {
        if (args.length > 2 || (args.length == 2 && !"--confirm-stopped".equals(args[1]))) {
            throw new IllegalArgumentException("recover accepts only --confirm-stopped");
        }
        Map<String, Object> report = WorktreeLease.recover(Path.of("."), args.length == 2);
        return CliOutput.report(report);
    }

    /**
     * Offline external-pilot preparation. Writes a reviewable bundle; never runs the target,
     * never calls a vendor, never edits the target checkout or the global home.
     */
    static int pilot(String[] args) {
        if (args.length < 2 || args[1].equals("--help") || args[1].equals("-h")) {
            CliHelp.print();
            return args.length < 2 ? 2 : 0;
        }
        PilotPrepareCommand.Outcome outcome = new PilotPrepareCommand().run(args);
        CliOutput.json(outcome.report());
        return outcome.exitCode();
    }

    /**
     * Carry an accepted candidate to a commit, a branch and a request. Merges nothing: the
     * request is a request, and whoever merges it is a person looking at it.
     */
    static int land(String[] args) throws Exception {
        LandCommand.Outcome outcome =
                new LandCommand(new ProcessRunner())
                        .run(LandCommand.parse(args));
        CliOutput.json(outcome.report());
        return outcome.ok() ? 0 : 1;
    }

    static int pullRequest(String[] args) throws Exception {
        PullRequestCommand.Outcome outcome = new PullRequestCommand(new ProcessRunner()).run(args);
        CliOutput.json(outcome.report());
        return outcome.ok() ? 0 : 1;
    }
}
