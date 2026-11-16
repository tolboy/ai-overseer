package dev.oseer.gate;

import dev.oseer.config.TaskSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The candidate running where a person can use it, for the decision they are about to make.
 *
 * Screenshots show what the harness chose to press. An operator asked, on the first all-Orca
 * run, what the page does on a second press, and had no way to find out short of starting the
 * server by hand. This starts it with the task's own `visual_qa.start` — the command the
 * harness already used on the same tree — opens its URL where the operator is, and stops it
 * when the decision page closes. It refuses to start a second server on a port somebody else
 * is answering, for the same reason the harness does.
 */
public final class CandidatePreview implements AutoCloseable {

    /** Shows a URL where the operator is; true when it was shown. */
    @FunctionalInterface
    public interface Opener {
        boolean open(String url) throws Exception;
    }

    private final Path root;
    private final String taskId;
    private final Path log;
    private final Opener opener;
    private Process server;
    private String url;

    public CandidatePreview(Path root, String taskId, Path log, Opener opener) {
        this.root = root;
        this.taskId = taskId;
        this.log = log;
        this.opener = opener;
    }

    /** Starts the preview if it is not running yet, opens it, and says what happened. */
    public synchronized String open() throws Exception {
        if (url == null) {
            TaskSpec.VisualQa visual = contract();
            if (visual == null) {
                return "{{t:preview.task_has_no_visual_qa}}";
            }
            String address = visual.url() != null && !visual.url().isBlank()
                    ? visual.url() : "http://127.0.0.1:4173/";
            boolean declared = visual.start() != null && !visual.start().isBlank();
            String start = declared ? visual.start() : VisualQaRunner.defaultStart(root);
            if (!VisualQaRunner.httpOk(address)) {
                if (start == null) {
                    return "{{t:preview.at_address}} " + address + " {{t:preview.nothing_responds_and_no_start}}";
                }
                server = VisualQaRunner.startServer(root, start, log);
                if (!VisualQaRunner.waitForHttp(address, Duration.ofSeconds(45))) {
                    close();
                    return "{{t:preview.started}} `" + start + "`{{t:preview.but}} " + address + " {{t:preview.never_responded_output_in}} " + log;
                }
            } else if (declared) {
                // The harness refuses this case; so does the preview. A stranger's app on the
                // port would be tried in place of the candidate.
                return "{{t:preview.at_address}} " + address + " {{t:preview.a_server_is_already_responding}} "
                        + "{{t:preview.stop_the_other_server_and}}";
            }
            url = address;
        }
        boolean shown = opener.open(url);
        return shown ? "{{t:preview.candidate_opened_in_an_orca}} " + url
                : "{{t:preview.candidate_started_open}} " + url;
    }

    private TaskSpec.VisualQa contract() {
        if (taskId == null || !dev.oseer.config.RepoPath.isSlug(taskId)) return null;
        Path file = root.resolve(".oseer/tasks").resolve(taskId + ".yaml");
        try {
            return TaskSpec.parse(Files.readString(file), file.toString()).visualQa();
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Stops the server this preview started, with everything it spawned. */
    @Override
    public synchronized void close() {
        if (server == null) return;
        server.descendants().forEach(ProcessHandle::destroyForcibly);
        server.destroyForcibly();
        server = null;
        url = null;
    }
}
