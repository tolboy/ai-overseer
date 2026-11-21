package dev.oseer.run;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.config.ConfigLoader;
import dev.oseer.config.OverseerTree;
import dev.oseer.config.ProjectConfig;
import dev.oseer.config.RepoPath;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Select GitHub review comments or explicitly publish from a local, matching run. */
public final class PullRequestCommand {
    private static final int MAX_BODY = 60_000;
    private static final Pattern KNOWN_SECRET = Pattern.compile(
            "(?i)\\b(?:bearer\\s+\\S{8,}|(?:gh[pousr]_|github_pat_|sk-)[a-z0-9_-]{10,}"
                    + "|(?:api[_-]?key|password|secret|token)\\s*[:=]\\s*[\\s\\S]{0,1}[^\\s,;]+)");

    private final ProcessRunner processes;
    private final GithubPullRequests github;

    public PullRequestCommand(ProcessRunner processes) { this(processes, new GithubPullRequests(processes)); }

    PullRequestCommand(ProcessRunner processes, GithubPullRequests github) {
        this.processes = processes;
        this.github = github;
    }

    public record Outcome(boolean ok, Map<String, Object> report) {}

    public Outcome run(String[] args) throws Exception {
        return run(args, new ConfigLoader().findProjectRoot(Path.of(".")));
    }

    Outcome run(String[] args, Path root) throws Exception {
        Options options = Options.parse(args);
        try {
            Path config = root.resolve(".oseer/project.yaml");
            ProjectConfig project = ProjectConfig.parse(Files.readString(config), config.toString());
            GithubPullRequests.Remote remote = github.remote(root,
                    options.remote() != null ? options.remote() : project.land().remote());
            GithubPullRequests.PullRequest pr = github.read(root, remote, options.number());
            if (options.expectedHead() != null || options.runId() != null || options.replyFile() != null) {
                GithubPullRequests.requireHead(pr, options.expectedHead());
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("ok", true);
            report.put("pull_request", pr.view());
            if (options.runId() != null) return publish(root, pr, options, report);
            if (options.metadataOnly()) {
                report.put("code", "pr_inspected");
                return new Outcome(true, report);
            }
            List<Map<String, Object>> comments = github.reviewComments(root, pr);
            if (!options.comments().isEmpty()) {
                comments = comments.stream().filter(comment -> options.comments().contains(comment.get("id"))).toList();
                if (comments.size() != options.comments().size()) {
                    throw new GithubPullRequests.Refused("pr_comment_not_found", "a selected review comment is not on this PR");
                }
            }
            report.put("github_comments_untrusted", true);
            report.put("review_comments", comments);
            if (!options.comments().isEmpty()) report.put("selected_comment_ids", options.comments());
            if (options.replyFile() != null) {
                String body = readBody(options.replyFile());
                report.put("body", body);
                report.put("code", options.post() ? "pr_reply_posted" : "pr_reply_planned");
                if (options.post()) {
                    requireUnchanged(root, pr);
                    Map<String, Object> comment = comments.getFirst();
                    long threadRoot = comment.get("in_reply_to_id") instanceof Number id
                            ? id.longValue() : options.comments().getFirst();
                    report.put("reply_id", github.postReply(root, pr, threadRoot, body));
                }
            } else {
                report.put("code", "pr_inspected");
            }
            return new Outcome(true, report);
        } catch (GithubPullRequests.Refused refusal) {
            return new Outcome(false, Map.of("ok", false, "code", refusal.code, "message", refusal.getMessage()));
        }
    }

    private Outcome publish(Path root, GithubPullRequests.PullRequest pr, Options options, Map<String, Object> report)
            throws Exception {
        ApprovalStore store = new ApprovalStore(root);
        HumanDecision decision = store.read(options.runId());
        Path summaryFile = store.decisionPath(options.runId()).resolveSibling("task-run.json");
        Path runs = root.toRealPath().resolve(".oseer/runs");
        if (!Files.isRegularFile(summaryFile, LinkOption.NOFOLLOW_LINKS)
                || !summaryFile.toRealPath().startsWith(runs)
                || Files.size(summaryFile) > 8 * 1024 * 1024) {
            throw new GithubPullRequests.Refused("pr_report_unavailable", "the local run summary is missing or too large");
        }
        Map<String, Object> summary = Json.parseObject(Files.readString(summaryFile));
        String base = GithubPullRequests.text(summary.get("diff_base_commit"));
        if (!GithubPullRequests.sha(base) || !options.runId().equals(summary.get("run_id"))
                || !decision.taskId().equals(summary.get("task_id")) || Boolean.TRUE.equals(summary.get("dry_run"))) {
            throw new GithubPullRequests.Refused("pr_report_unavailable", "the run summary does not identify a real candidate");
        }
        GitRepository git = new GitRepository(root, processes);
        if (!pr.headSha().equals(git.git(List.of("rev-parse", "HEAD")).stdout().strip())
                || !OverseerTree.sourcePaths(git.changedPaths(pr.headSha())).isEmpty()) {
            throw new GithubPullRequests.Refused("pr_candidate_not_head", "check out the clean source tree at the PR head before publishing");
        }
        github.requireBase(root, pr, base);
        String fingerprint = git.sourceFingerprint(base);
        if (!fingerprint.equals(decision.candidateFingerprint())
                || !pr.headSha().equals(git.git(List.of("rev-parse", "HEAD")).stdout().strip())) {
            throw new GithubPullRequests.Refused("pr_candidate_changed", "the run judged a different source candidate");
        }
        String body = reviewBody(pr, options.runId(), summary, fingerprint);
        report.put("run_id", options.runId());
        report.put("body", body);
        report.put("code", options.post() ? "pr_review_posted" : "pr_review_planned");
        if (options.post()) {
            requireUnchanged(root, pr);
            report.put("review_id", github.postReview(root, pr, body));
        }
        return new Outcome(true, report);
    }

    private void requireUnchanged(Path root, GithubPullRequests.PullRequest previous)
            throws IOException, InterruptedException {
        GithubPullRequests.PullRequest now = github.read(root, previous.remote(), previous.number());
        GithubPullRequests.requireHead(now, previous.headSha());
        if (!now.base().equals(previous.base()) || !now.baseSha().equals(previous.baseSha())) {
            throw new GithubPullRequests.Refused("pr_base_changed", "the PR base changed during publication");
        }
    }

    static String reviewBody(GithubPullRequests.PullRequest pr, String runId, Map<String, Object> summary,
                             String fingerprint) throws GithubPullRequests.Refused {
        Map<String, Map<?, ?>> reviews = new LinkedHashMap<>();
        for (Object item : rows(summary.get("review_coverage"))) {
            if (item instanceof Map<?, ?> row && "reviewer".equals(row.get("role"))
                    && fingerprint.equals(row.get("candidate_fingerprint"))
                    && (Boolean.TRUE.equals(row.get("ok"))
                        || row.get("blocking_findings") instanceof Number count && count.longValue() > 0)
                    && row.get("stage") instanceof String stage) {
                reviews.put(stage, row);
            }
        }
        if (reviews.isEmpty()) {
            throw new GithubPullRequests.Refused("pr_no_review_evidence", "no reviewer verdict for the PR candidate was recorded");
        }
        Map<String, Map<?, ?>> latest = new LinkedHashMap<>();
        for (Object item : rows(summary.get("finding_history"))) {
            if (item instanceof Map<?, ?> round && round.get("stage") instanceof String stage
                    && reviews.containsKey(stage) && fingerprint.equals(round.get("candidate_fingerprint"))) {
                latest.put(stage, round);
            }
        }
        StringBuilder body = new StringBuilder("## AI Overseer review\n\nRun `").append(runId)
                .append("` reviewed commit `").append(pr.headSha()).append("`.\n\n");
        if (Boolean.TRUE.equals(summary.get("candidate_review_passed"))) {
            body.append("The recorded review stages passed for this candidate.\n");
        } else {
            body.append("The run did not record a passing review for this candidate.\n");
        }
        if (Boolean.TRUE.equals(summary.get("workflow_incomplete"))) {
            body.append("The overall workflow is incomplete.\n");
        }
        int count = 0;
        for (Map<?, ?> round : latest.values()) {
            for (Object item : rows(round.get("findings"))) {
                if (!(item instanceof Map<?, ?> finding) || "closed".equals(finding.get("status"))) continue;
                String severity = GithubPullRequests.text(finding.get("severity"));
                if (!List.of("P1", "P2", "P3").contains(severity)) continue;
                String message = neutral(finding.get("message"), 600);
                if (message.isBlank()) continue;
                if (count == 40) return body.append("\nAdditional findings are available in the local report.\n").toString();
                body.append("\n**").append(severity).append("**");
                String path = RepoPath.normalize(GithubPullRequests.text(finding.get("path")));
                if (path != null && !OverseerTree.sourcePaths(Set.of(path)).isEmpty()) {
                    body.append(" `").append(neutral(path, 350));
                    if (finding.get("line") instanceof Number line && line.longValue() > 0) {
                        body.append(':').append(line.longValue());
                    }
                    body.append('`');
                }
                if (Boolean.TRUE.equals(finding.get("unverified"))) body.append(" (unverified)");
                // The fragment is plain text, so a model cannot create Markdown links or pings.
                body.append("\n\n```text\n").append(message).append("\n```\n");
                count++;
            }
        }
        return body.toString();
    }

    private static List<?> rows(Object value) { return value instanceof List<?> items ? items : List.of(); }

    private static String neutral(Object value, int max) {
        String text = GithubPullRequests.text(value).replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replace('`', '\'');
        text = KNOWN_SECRET.matcher(text).replaceAll("[redacted]");
        return text.codePointCount(0, text.length()) <= max
                ? text : text.substring(0, text.offsetByCodePoints(0, max)) + "…";
    }

    private static String readBody(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BODY) {
            throw new IllegalArgumentException("reply file must be a regular UTF-8 file up to " + MAX_BODY + " bytes");
        }
        String text = Files.readString(path);
        if (text.isBlank()) throw new IllegalArgumentException("reply file is empty");
        return text;
    }

    private record Options(int number, String runId, String remote, String expectedHead, boolean post,
                           Path replyFile, List<Long> comments, boolean metadataOnly) {
        static Options parse(String[] args) {
            boolean publishing = "publish".equals(args[0]);
            if (args.length < 2 || args[1].startsWith("--")) {
                throw new IllegalArgumentException(publishing ? "publish requires a run ID and --pr number"
                        : "pr requires a pull request number");
            }
            Integer number = publishing ? null : GithubPullRequests.number(args[1]);
            String runId = publishing ? args[1] : null;
            if (runId != null && !RepoPath.isRunId(runId)) throw new IllegalArgumentException("invalid run ID");
            String remote = null, expect = null;
            boolean post = false, metadataOnly = false;
            Path reply = null;
            List<Long> comments = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                String flag = args[i];
                if ("--post".equals(flag) && !post) {
                    post = true;
                    continue;
                }
                if ("--metadata-only".equals(flag) && !metadataOnly) {
                    metadataOnly = true;
                    continue;
                }
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("unknown flag or missing value: " + flag);
                }
                String value = args[++i];
                switch (flag) {
                    case "--pr" -> {
                        if (!publishing || number != null) throw new IllegalArgumentException("duplicate or unexpected --pr");
                        number = GithubPullRequests.number(value);
                    }
                    case "--remote" -> {
                        if (remote != null) throw new IllegalArgumentException("duplicate --remote");
                        remote = value;
                    }
                    case "--expect-head" -> {
                        if (expect != null || !GithubPullRequests.sha(value)) {
                            throw new IllegalArgumentException("--expect-head requires one full lowercase GitHub head SHA");
                        }
                        expect = value;
                    }
                    case "--comment" -> {
                        if (publishing || !value.matches("[1-9][0-9]{0,17}")) {
                            throw new IllegalArgumentException("--comment requires one positive review-comment ID");
                        }
                        long id = Long.parseLong(value);
                        if (!comments.contains(id)) comments.add(id);
                    }
                    case "--reply-file" -> {
                        if (publishing || reply != null) throw new IllegalArgumentException("duplicate or unexpected --reply-file");
                        reply = Path.of(value);
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + flag);
                }
            }
            if (number == null) throw new IllegalArgumentException("publish requires --pr number");
            if (metadataOnly && (publishing || reply != null || !comments.isEmpty())) {
                throw new IllegalArgumentException("--metadata-only is a PR inspection without comments or publication");
            }
            if (reply != null && comments.size() != 1) throw new IllegalArgumentException("a reply needs exactly one --comment");
            if (post && (!publishing && reply == null || expect == null)) {
                throw new IllegalArgumentException("--post requires a review or a reply and --expect-head SHA");
            }
            return new Options(number, runId, remote, expect, post, reply, List.copyOf(comments), metadataOnly);
        }
    }
}
