package dev.oseer.run;

import dev.oseer.json.Json;
import dev.oseer.process.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub access for commands that explicitly name an existing pull request. */
final class GithubPullRequests {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final Pattern GITHUB_REMOTE = Pattern.compile(
            "(?i)(?:https://github\\.com/|git@github\\.com:|ssh://git@github\\.com/)"
                    + "([a-z0-9-]{1,39})/([a-z0-9_.-]{1,100}?)(?:\\.git)?/?");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    record Remote(String name, String repository, String pushUrl) {}

    record PullRequest(Remote remote, int number, String state, String title, String base,
                       String baseSha, String head, String headSha, String headRepository,
                       String defaultBranch) {
        String endpoint() { return "repos/" + remote.repository() + "/pulls/" + number; }
        String url() { return "https://github.com/" + remote.repository() + "/pull/" + number; }
        boolean open() { return "open".equals(state); }
        boolean sameRepository() { return remote.repository().equalsIgnoreCase(headRepository); }

        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("number", number);
            view.put("url", url());
            view.put("repository", remote.repository());
            view.put("default_branch", defaultBranch);
            view.put("title", title);
            view.put("state", state);
            view.put("base", base);
            view.put("base_sha", baseSha);
            view.put("head", head);
            view.put("head_sha", headSha);
            view.put("head_repository", headRepository);
            return view;
        }
    }

    @FunctionalInterface
    interface Api {
        Object request(Path root, String endpoint, boolean paginate, Map<String, Object> payload)
                throws IOException, InterruptedException;
    }

    static final class Refused extends IOException {
        final String code;

        Refused(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final ProcessRunner processes;
    private final Api api;

    GithubPullRequests(ProcessRunner processes) {
        this.processes = processes;
        this.api = this::request;
    }

    GithubPullRequests(ProcessRunner processes, Api api) {
        this.processes = processes;
        this.api = api;
    }

    static int number(String value) {
        if (value != null && value.matches("[1-9][0-9]{0,9}")) {
            try { return Integer.parseInt(value); } catch (NumberFormatException tooLarge) { }
        }
        throw new IllegalArgumentException("pull request number must be a positive integer");
    }

    static boolean sha(String value) { return value != null && SHA.matcher(value).matches(); }

    Remote remote(Path root, String selected) throws IOException, InterruptedException {
        ProcessRunner.Result names = processes.run(List.of("git", "remote"), root, TIMEOUT);
        if (!names.ok()) throw new Refused("github_remote_unavailable", "could not read the repository's remotes");
        List<String> remotes = names.stdout().lines().filter(line -> !line.isBlank()).toList();
        if (selected == null) {
            if (remotes.size() != 1) {
                throw new Refused("github_remote_ambiguous", "name a GitHub remote with --remote or land.remote");
            }
            selected = remotes.getFirst();
        }
        if (!remotes.contains(selected) || !selected.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,127}")) {
            throw new Refused("github_remote_unavailable", "the selected Git remote is unavailable");
        }
        ProcessRunner.Result result = processes.run(
                List.of("git", "remote", "get-url", "--push", "--all", selected), root, TIMEOUT);
        List<String> urls = result.stdout().lines().filter(line -> !line.isBlank()).toList();
        if (!result.ok() || urls.size() != 1) {
            throw new Refused("github_remote_unavailable", "the selected remote must have exactly one push URL");
        }
        return githubRemote(selected, urls.getFirst());
    }

    static Remote githubRemote(String remote, String url) throws Refused {
        Matcher matcher = GITHUB_REMOTE.matcher(url);
        if (!matcher.matches() || matcher.group(2).equals(".") || matcher.group(2).equals("..")) {
            throw new Refused("unsupported_forge", "the selected push URL must name a repository on github.com");
        }
        return new Remote(remote, (matcher.group(1) + "/" + matcher.group(2)).toLowerCase(Locale.ROOT), url);
    }

    PullRequest read(Path root, Remote remote, int number) throws IOException, InterruptedException {
        Map<?, ?> pr = object(api.request(root, "repos/" + remote.repository() + "/pulls/" + number, false, null));
        Map<?, ?> base = object(pr.get("base"));
        Map<?, ?> head = object(pr.get("head"));
        Map<?, ?> baseRepo = object(base.get("repo"));
        PullRequest metadata = new PullRequest(remote, number, text(pr.get("state")), clip(pr.get("title"), 300),
                text(base.get("ref")), text(base.get("sha")), text(head.get("ref")), text(head.get("sha")),
                text(object(head.get("repo")).get("full_name")), text(baseRepo.get("default_branch")));
        if (!valid(root, metadata, pr.get("number"), text(baseRepo.get("full_name")))) {
            throw new Refused("github_pr_invalid", "GitHub did not return verifiable pull request metadata");
        }
        return metadata;
    }

    PullRequest readSnapshot(Path root, Remote remote, int number, Path file) throws InterruptedException, IOException {
        Map<?, ?> snapshot;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_SNAPSHOT_BYTES) {
                throw new IOException("snapshot is not a regular file or exceeds the size limit");
            }
            snapshot = Json.parseObject(Files.readString(file));
        } catch (IOException | Json.JsonException invalid) {
            throw new Refused("pr_snapshot_invalid", "expected a UTF-8 JSON file saved from oseer pr " + number);
        }
        Map<?, ?> pr = object(snapshot.get("pull_request"));
        PullRequest metadata = new PullRequest(remote, number, text(pr.get("state")), clip(pr.get("title"), 300),
                text(pr.get("base")), text(pr.get("base_sha")), text(pr.get("head")), text(pr.get("head_sha")),
                text(pr.get("head_repository")), text(pr.get("default_branch")));
        if (!Boolean.TRUE.equals(snapshot.get("ok"))
                || !valid(root, metadata, pr.get("number"), text(pr.get("repository")))) {
            throw new Refused("pr_snapshot_invalid", "the snapshot does not identify the selected repository and PR");
        }
        return metadata;
    }

    private boolean valid(Path root, PullRequest pr, Object number, String repository)
            throws IOException, InterruptedException {
        return number instanceof Number found && found.longValue() == pr.number()
                && pr.remote().repository().equalsIgnoreCase(repository)
                && sha(pr.baseSha()) && sha(pr.headSha()) && branch(root, pr.base())
                && branch(root, pr.head()) && branch(root, pr.defaultBranch())
                && List.of("open", "closed").contains(pr.state());
    }

    private boolean branch(Path root, String name) throws IOException, InterruptedException {
        return !name.isEmpty() && name.length() <= 255
                && processes.run(List.of("git", "check-ref-format", "refs/heads/" + name), root, TIMEOUT).ok();
    }

    List<Map<String, Object>> reviewComments(Path root, PullRequest pr) throws IOException, InterruptedException {
        Object raw = api.request(root, pr.endpoint() + "/comments?per_page=100", true, null);
        if (!(raw instanceof List<?> pages)) {
            throw new Refused("github_comments_invalid", "GitHub did not return a review comment list");
        }
        List<Map<String, Object>> comments = new ArrayList<>();
        for (Object page : pages) {
            if (!(page instanceof List<?> entries)) {
                throw new Refused("github_comments_invalid", "GitHub did not return a review comment page");
            }
            for (Object item : entries) {
                Map<?, ?> row = object(item);
                if (!(row.get("id") instanceof Number id)) continue;
                Map<String, Object> comment = new LinkedHashMap<>();
                comment.put("id", id.longValue());
                comment.put("author", clip(object(row.get("user")).get("login"), 100));
                comment.put("path", clip(row.get("path"), 500));
                comment.put("side", clip(row.get("side"), 8));
                comment.put("commit_id", clip(row.get("commit_id"), 40));
                comment.put("original_commit_id", clip(row.get("original_commit_id"), 40));
                comment.put("line", row.get("line") instanceof Number line ? line.longValue() : null);
                comment.put("original_line", row.get("original_line") instanceof Number line ? line.longValue() : null);
                if (row.get("in_reply_to_id") instanceof Number parent && parent.longValue() > 0) {
                    comment.put("in_reply_to_id", parent.longValue());
                }
                comment.put("body", clip(row.get("body"), 8_000));
                comments.add(comment);
            }
        }
        return comments;
    }

    long postReview(Path root, PullRequest pr, String body) throws IOException, InterruptedException {
        Map<?, ?> posted = object(api.request(root, pr.endpoint() + "/reviews", false,
                Map.of("event", "COMMENT", "commit_id", pr.headSha(), "body", body)));
        if (!(posted.get("id") instanceof Number id)) {
            throw new Refused("github_review_unconfirmed", "GitHub did not confirm the review ID; check the PR before retrying");
        }
        return id.longValue();
    }

    long postReply(Path root, PullRequest pr, long comment, String body) throws IOException, InterruptedException {
        Map<?, ?> posted = object(api.request(root, pr.endpoint() + "/comments/" + comment + "/replies", false,
                Map.of("body", body)));
        if (!(posted.get("id") instanceof Number id)) {
            throw new Refused("github_reply_unconfirmed", "GitHub did not confirm the reply ID; check the PR before retrying");
        }
        return id.longValue();
    }

    static void requireHead(PullRequest pr, String expected) throws Refused {
        if (!pr.open()) throw new Refused("pr_not_open", "this pull request is no longer open");
        if (expected != null && !expected.equals(pr.headSha())) {
            throw new Refused("pr_head_changed", "the current PR head differs from --expect-head; inspect the PR again");
        }
    }

    void requireBase(Path root, PullRequest pr, String recordedBase) throws IOException, InterruptedException {
        requireBase(root, pr, pr.headSha(), recordedBase);
    }

    void requireBase(Path root, PullRequest pr, String candidate, String recordedBase)
            throws IOException, InterruptedException {
        ProcessRunner.Result merge = processes.run(
                List.of("git", "merge-base", candidate, pr.baseSha()), root, TIMEOUT);
        if (!merge.ok() || !merge.stdout().strip().equals(recordedBase)) {
            throw new Refused("pr_base_mismatch", "the PR merge base differs from the run, or the PR commits are "
                    + "unavailable locally; fetch the PR base and head, then run against the correct base");
        }
    }

    void verifyRemoteHead(Path root, PullRequest pr) throws IOException, InterruptedException {
        String ref = "refs/heads/" + pr.head();
        ProcessRunner.Result result = processes.run(List.of("git", "ls-remote", "--exit-code",
                pr.remote().pushUrl(), ref), root, TIMEOUT);
        if (!result.ok() || !result.stdout().strip().equals(pr.headSha() + "\t" + ref)) {
            throw new Refused("pr_head_changed", "the remote branch could not be verified at the expected PR head");
        }
    }

    private Object request(Path root, String endpoint, boolean paginate, Map<String, Object> payload)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("gh", "api", "--hostname", "github.com",
                "-H", "Accept: application/vnd.github+json", endpoint));
        if (paginate) command.addAll(List.of("--paginate", "--slurp"));
        if (payload != null) command.addAll(List.of("--method", "POST", "--input", "-"));
        ProcessRunner.Result result;
        try {
            result = processes.run(command, root, TIMEOUT, 4 * 1024 * 1024,
                    payload == null ? null : Json.write(payload));
        } catch (IOException unavailable) {
            throw new Refused("github_unavailable", "could not run gh; check installation and authentication");
        }
        if (!result.ok() || result.stdoutTruncated()) {
            throw new Refused("github_request_failed", payload == null
                    ? "GitHub read failed; check gh auth status, repository access and PR number"
                    : "GitHub did not confirm the post; check the PR before retrying");
        }
        try { return Json.parse(result.stdout()); }
        catch (RuntimeException invalid) {
            throw new Refused("github_response_invalid", "GitHub returned an unreadable response");
        }
    }

    private static Map<?, ?> object(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    static String text(Object value) { return value instanceof String input ? input : ""; }

    private static String clip(Object value, int limit) {
        String input = text(value).replaceAll("[\\p{Cc}&&[^\\n\\t]]|\\p{Cf}", "");
        return input.codePointCount(0, input.length()) <= limit
                ? input : input.substring(0, input.offsetByCodePoints(0, limit)) + "…";
    }
}
