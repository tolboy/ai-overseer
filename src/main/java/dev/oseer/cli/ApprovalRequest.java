package dev.oseer.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.oseer.cli.CliOptions.hasFlag;
import static dev.oseer.cli.CliOptions.option;
import static dev.oseer.cli.CliOptions.optionalMinutes;

record ApprovalRequest(String runId, boolean fromOrca, String choice, Integer waitMinutes, String actor,
                       String note, String expectedUpdatedAt, String finding, boolean acknowledgeExpired,
                       boolean startUnlessWaiting) {
    static ApprovalRequest parse(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "approve requires a run id and either --decision or --from-orca");
        }
        String runId = args[1];
        boolean fromOrca = hasFlag(args, "--from-orca");
        String choice = option(args, "--decision", null);
        Integer waitMinutes = optionalMinutes(args, "--wait-minutes");
        if (choice == null && !fromOrca) {
            throw new IllegalArgumentException("approve requires --decision <choice> or --from-orca");
        }
        if (choice != null && fromOrca) {
            throw new IllegalArgumentException("approve takes --decision or --from-orca, not both");
        }
        if (waitMinutes != null && !fromOrca) {
            throw new IllegalArgumentException("--wait-minutes is only valid with --from-orca");
        }
        String actor = option(args, "--actor", fromOrca ? null
                : System.getProperty("user.name", "human"));
        String note = option(args, "--note", "");
        String noteFile = option(args, "--note-file", null);
        if (noteFile != null) note = Files.readString(Path.of(noteFile));
        return new ApprovalRequest(runId, fromOrca, choice, waitMinutes, actor, note,
                option(args, "--expected-updated-at", null), option(args, "--finding", null),
                hasFlag(args, "--acknowledge-expired"), hasFlag(args, "--start-unless-waiting"));
    }
}
