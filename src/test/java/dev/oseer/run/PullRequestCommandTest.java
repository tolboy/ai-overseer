package dev.oseer.run;

import dev.oseer.approval.ApprovalStore;
import dev.oseer.approval.HumanDecision;
import dev.oseer.git.GitRepository;
import dev.oseer.json.Json;
import dev.oseer.ledger.CitationCheck;
import dev.oseer.ledger.Findings;
import dev.oseer.process.ProcessRunner;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class PullRequestCommandTest implements Suite {
    private static final ProcessRunner PROCESSES = new ProcessRunner();
    private static final String ENDPOINT = "repos/example/widget/pulls/72";
    private static final String DIFFERENT_HEAD = "b".repeat(40);

    @Override public String name() { return "pull-requests"; }

    @Override
    public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("oseer-pr-test-");
        try {
            Fixture fixture = project(sandbox.resolve("project"));
            FakeGithub api = new FakeGithub(fixture);
            GithubPullRequests github = new GithubPullRequests(PROCESSES, api);
            PullRequestCommand command = new PullRequestCommand(PROCESSES, github);
            comments(check, sandbox, fixture, api, command);
            publish(check, fixture, api, command);
            var snapshot = command.run(new String[]{"pr", "72", "--metadata-only"}, fixture.root());
            check.that("snapshot leaves out the comments", !snapshot.report().containsKey("review_comments"));
            update(check, sandbox, fixture, api, new LandCommand(PROCESSES, github), snapshot.report());
        } finally {
            try (var paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void comments(Check check, Path sandbox, Fixture repo, FakeGithub api,
                                 PullRequestCommand command) throws Exception {
        var listing = command.run(new String[]{"pr", "72", "--expect-head", repo.head()}, repo.root());
        check.eq("PR lists both comment pages", 2, ((List<?>) listing.report().get("review_comments")).size());
        check.eq("PR flags external text", true, listing.report().get("github_comments_untrusted"));
        check.eq("PR refuses an unknown comment", "pr_comment_not_found",
                command.run(new String[]{"pr", "72", "--comment", "999"}, repo.root()).report().get("code"));
        check.eq("PR inspection detects a stale pin", "pr_head_changed",
                command.run(new String[]{"pr", "72", "--expect-head", DIFFERENT_HEAD}, repo.root()).report().get("code"));

        Path reply = sandbox.resolve("reply.txt");
        Files.writeString(reply, "Thanks, this was fixed.\n");
        String[] preview = {"pr", "72", "--comment", "124", "--reply-file", reply.toString()};
        var planned = command.run(preview, repo.root());
        check.eq("select only the named reply", List.of(124L), planned.report().get("selected_comment_ids"));
        check.eq("reply body is previewed", Files.readString(reply), planned.report().get("body"));
        check.eq("preview does not post a reply", 0, api.posts.size());
        check.rejects("posting a reply requires a pin", "--expect-head",
                () -> command.run(new String[]{"pr", "72", "--comment", "124",
                        "--reply-file", reply.toString(), "--post"}, repo.root()));

        var posted = command.run(new String[]{"pr", "72", "--comment", "124", "--reply-file", reply.toString(),
                "--post", "--expect-head", repo.head()}, repo.root());
        check.eq("posted reply is confirmed", 301L, posted.report().get("reply_id"));
        check.eq("reply to a reply resolves to its thread root",
                List.of(new Post(ENDPOINT + "/comments/123/replies", Map.of("body", Files.readString(reply)))),
                api.posts);
    }

    private static void publish(Check check, Fixture repo, FakeGithub api, PullRequestCommand command)
            throws Exception {
        api.posts.clear();
        GitRepository git = new GitRepository(repo.root(), PROCESSES);
        String fingerprint = git.sourceFingerprint(repo.base());
        String fakeCredential = "ghp_exampleCredential0123456789";
        var reviewerArtifact = Map.<String, Object>of("findings",
                List.of(Map.of("severity", "P1", "path", "src/kept.txt", "line", "1", "end_line", 1L,
                        "message", "Example finding " + fakeCredential)));
        var verified = CitationCheck.verify(Findings.of(reviewerArtifact), repo.root());
        var round = Findings.round("review", 0, fingerprint, verified, null);
        var persisted = (Map<?, ?>) ((List<?>) round.get("findings")).getFirst();
        check.eq("finding history preserves the verified range", 1, persisted.get("end_line"));
        writeRun(repo, "review-72", Map.of(
                "candidate_review_passed", false,
                "review_coverage", List.of(Map.of("role", "reviewer", "stage", "review",
                        "candidate_fingerprint", fingerprint, "ok", false, "blocking_findings", 1L)),
                "finding_history", List.of(round)), false);

        String[] preview = {"publish", "review-72", "--pr", "72"};
        var planned = command.run(preview, repo.root());
        String body = (String) planned.report().get("body");
        check.eq("review defaults to a preview", "pr_review_planned", planned.report().get("code"));
        check.eq("preview does not post a review", 0, api.posts.size());
        check.contains("review shows the examined commit", body, repo.head());
        check.contains("review shows finding location", body, "src/kept.txt:1");
        check.contains("review redacts token-like text", body, "[redacted]");
        check.that("preview excludes the sample token", body != null && !body.contains(fakeCredential));

        api.moveHeadAfterRead = true;
        var stale = command.run(new String[]{"publish", "review-72", "--pr", "72",
                "--post", "--expect-head", repo.head()}, repo.root());
        check.eq("head moving during review publication refuses the post", "pr_head_changed", stale.report().get("code"));
        check.eq("stale publication sends no write", 0, api.posts.size());
        api.head = repo.head();
        var posted = command.run(new String[]{"publish", "review-72", "--pr", "72",
                "--post", "--expect-head", repo.head()}, repo.root());
        check.eq("posted review is confirmed", 301L, posted.report().get("review_id"));
        check.eq("publication sends an ordinary comment pinned to the commit", List.of(
                new Post(ENDPOINT + "/reviews", Map.of("event", "COMMENT", "commit_id", repo.head(), "body", body))),
                api.posts);
        Files.writeString(repo.root().resolve("src/kept.txt"), "dirty\n");
        check.eq("publishing refuses dirty source even when git HEAD still matches", "pr_candidate_not_head",
                command.run(preview, repo.root()).report().get("code"));
        git(repo.root(), "restore", "src/kept.txt");
    }

    private static void update(Check check, Path sandbox, Fixture repo, FakeGithub api, LandCommand command,
                               Map<String, Object> metadata) throws Exception {
        api.posts.clear();
        Files.writeString(repo.root().resolve("src/kept.txt"), "accepted follow-up\n");
        writeRun(repo, "fix-72", Map.of(), true);
        var planned = land(command, repo, "land", "fix-72", "--update-pr", "72");
        check.eq("existing PR defaults to a plan", "planned", planned.code());
        check.eq("land plan leaves the branch at its original head", repo.head(),
                git(repo.root(), "rev-parse", "HEAD").strip());
        check.eq("land refuses to plan a changed pinned head", "pr_head_changed",
                land(command, repo, "land", "fix-72", "--update-pr", "72",
                        "--expect-head", DIFFERENT_HEAD).code());
        api.prBase = "stack-parent";
        check.eq("land refuses a PR that targets a different configured branch", "pr_base_mismatch",
                land(command, repo, "land", "fix-72", "--update-pr", "72").code());
        api.prBase = "main";

        Path bundle = sandbox.resolve("relay.bundle");
        var exported = land(command, repo, "land", "fix-72", "--update-pr", "72", "--expect-head", repo.head(),
                "--emit-bundle", bundle.toString());
        check.eq("an accepted fast-forward update can be exported", "pr_bundle_exported", exported.code());
        String candidate = git(repo.root(), "rev-parse", "HEAD").strip();
        check.that("bundle creation commits accepted changes", !candidate.equals(repo.head()));
        check.eq("Git bundle carries the exact candidate and only the PR branch",
                candidate + " refs/heads/work", git(repo.root(), "bundle", "list-heads", bundle.toString()).strip());
        check.eq("bundle export never overwrites a file", "bundle_exists",
                land(command, repo, "land", "fix-72", "--update-pr", "72", "--expect-head", repo.head(),
                        "--emit-bundle", bundle.toString()).code());
        check.eq("planning and bundling do not post GitHub content", 0, api.posts.size());

        Path snapshot = sandbox.resolve("pr.json");
        Path offlineBundle = sandbox.resolve("offline.bundle");
        Files.writeString(snapshot, Json.writePretty(metadata));
        var offline = new LandCommand(PROCESSES, new GithubPullRequests(PROCESSES,
                (root, endpoint, paginate, payload) -> { throw new IOException("offline fixture"); }));
        var offlineExport = land(offline, repo, "land", "fix-72", "--update-pr", "72", "--expect-head", repo.head(),
                "--emit-bundle", offlineBundle.toString(), "--pr-snapshot", snapshot.toString());
        check.eq("snapshot works without GitHub access", "pr_bundle_exported", offlineExport.code());
        check.eq("snapshot export discloses its metadata source", "snapshot", offlineExport.report().get("pr_metadata_source"));
        check.eq("offline bundle contains the same candidate", candidate + " refs/heads/work",
                git(repo.root(), "bundle", "list-heads", offlineBundle.toString()).strip());
        check.eq("a snapshot from another PR is refused", "pr_snapshot_invalid",
                land(offline, repo, "land", "fix-72", "--update-pr", "73", "--expect-head", repo.head(),
                        "--emit-bundle", sandbox.resolve("wrong.bundle").toString(), "--pr-snapshot", snapshot.toString()).code());
        rejectMovingLocalHead(check, sandbox, repo, api, candidate);

        Path remote = sandbox.resolve("relay.git");
        git(sandbox, "init", "--bare", "-q", remote.toString());
        git(repo.root(), "push", remote.toString(), repo.head() + ":refs/heads/work");
        String intended = candidate;
        Files.writeString(repo.root().resolve("src/kept.txt"), "later local commit\n");
        git(repo.root(), "commit", "-qam", "later work");
        git(repo.root(), "push", remote.toString(), "HEAD:refs/heads/work");
        var raced = PROCESSES.run(LandCommand.updatePushCommand(remote.toString(), "work", repo.head(), intended),
                repo.root(), Duration.ofSeconds(30));
        check.that("exact lease rejects a stale remote branch", !raced.ok());
        check.eq("lease rejection preserves the remote's new head",
                git(repo.root(), "rev-parse", "HEAD").strip(), git(remote, "rev-parse", "refs/heads/work").strip());
        git(remote, "update-ref", "refs/heads/work", repo.head());
        var pushed = PROCESSES.run(LandCommand.updatePushCommand(remote.toString(), "work", repo.head(), intended),
                repo.root(), Duration.ofSeconds(30));
        check.that("matching lease can push the already verified commit", pushed.ok());
        check.eq("push uses the pinned candidate even when local HEAD has moved",
                intended, git(remote, "rev-parse", "refs/heads/work").strip());

        Files.writeString(repo.root().resolve("src/kept.txt"), "accepted follow-up\n");
        git(repo.root(), "commit", "-qam", "restore accepted tree");
        var extraHistory = land(command, repo, "land", "fix-72", "--update-pr", "72", "--expect-head", repo.head());
        check.eq("matching final tree cannot carry extra intermediate versions",
                "pr_update_requires_single_commit", extraHistory.code());
    }

    private static void rejectMovingLocalHead(Check check, Path sandbox, Fixture repo, FakeGithub api, String candidate)
            throws Exception {
        String tree = git(repo.root(), "rev-parse", candidate + "^{tree}").strip();
        String replacement = git(repo.root(), "commit-tree", tree, "-p", repo.head(), "-m", "replacement commit").strip();
        AtomicInteger reads = new AtomicInteger();
        var github = new GithubPullRequests(PROCESSES, (root, endpoint, paginate, payload) -> {
            var response = api.request(root, endpoint, paginate, payload);
            if (ENDPOINT.equals(endpoint) && reads.incrementAndGet() == 2) {
                git(root, "update-ref", "refs/heads/work", replacement, candidate);
            }
            return response;
        });
        Path bundle = sandbox.resolve("moving-head.bundle");
        var result = land(new LandCommand(PROCESSES, github), repo, "land", "fix-72", "--update-pr", "72",
                "--expect-head", repo.head(), "--emit-bundle", bundle.toString());
        check.eq("a moved local HEAD is refused even when the replacement has the same tree",
                "pr_candidate_changed", result.code());
        check.that("a moved local HEAD does not export a bundle", Files.notExists(bundle));
        git(repo.root(), "update-ref", "refs/heads/work", candidate, replacement);
    }

    private static LandCommand.Outcome land(LandCommand command, Fixture repo, String... args) throws Exception {
        return command.run(LandCommand.parse(args), repo.root());
    }

    private static void writeRun(Fixture repo, String runId, Map<String, Object> data, boolean accept) throws Exception {
        Path summary = repo.root().resolve(".oseer/runs").resolve(runId).resolve("task-run.json");
        Files.createDirectories(summary.getParent());
        Map<String, Object> values = new java.util.LinkedHashMap<>(data);
        values.put("task_id", "hello");
        values.put("run_id", runId);
        values.put("ok", accept);
        values.put("diff_base_commit", repo.base());
        values.put("steps", List.of());
        Files.writeString(summary, Json.writePretty(values));
        ApprovalStore store = new ApprovalStore(repo.root());
        HumanDecision pending = store.createSuccess(runId, "hello", "recorded candidate",
                summary, new GitRepository(repo.root(), PROCESSES).sourceFingerprint(repo.base()));
        if (accept) store.resolve(runId, pending.updatedAt().toString(), "accept", "tester", "");
    }

    private static Fixture project(Path root) throws Exception {
        Files.createDirectories(root.resolve(".oseer/tasks"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(".oseer/project.yaml"), """
                version: 1
                project: hello
                base_ref: main
                checks:
                  fast: ["git diff --check"]
                scopes:
                  app: ["src"]
                defaults:
                  checks: fast
                  risk: medium
                land:
                  remote: origin
                  base: main
                """);
        Files.writeString(root.resolve(".oseer/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Update hello
                risk: medium
                scope: app
                authority:
                  workspace_write: true
                budgets:
                  max_role_runs: 4
                  max_cost_usd: 1.0
                max_fix_attempts: 1
                """);
        Files.writeString(root.resolve(".gitignore"), "/.oseer/runs/\n");
        Files.writeString(root.resolve(".gitattributes"), "* -text\n");
        Files.writeString(root.resolve("src/kept.txt"), "seed\n");
        git(root, "init", "-q", "-b", "main");
        git(root, "config", "user.email", "test@example.invalid");
        git(root, "config", "user.name", "test");
        git(root, "add", "-A");
        git(root, "commit", "-qm", "base");
        git(root, "remote", "add", "origin", "https://github.com/example/widget.git");
        String base = git(root, "rev-parse", "HEAD").strip();
        git(root, "checkout", "-qb", "work");
        Files.writeString(root.resolve("src/kept.txt"), "existing PR change\n");
        git(root, "commit", "-qam", "first PR commit");
        return new Fixture(root, base, git(root, "rev-parse", "HEAD").strip());
    }

    private static String git(Path root, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        var result = PROCESSES.run(command, root, Duration.ofSeconds(30));
        if (!result.ok()) throw new IOException(command + ": " + result.stderr());
        return result.stdout();
    }

    private record Fixture(Path root, String base, String head) {}
    private record Post(String endpoint, Map<String, Object> payload) {}

    private static final class FakeGithub implements GithubPullRequests.Api {
        final Fixture repo;
        final List<Post> posts = new ArrayList<>();
        String head;
        String prBase = "main";
        boolean moveHeadAfterRead;

        FakeGithub(Fixture repo) { this.repo = repo; this.head = repo.head(); }

        @Override
        public Object request(Path root, String endpoint, boolean paginate, Map<String, Object> payload)
                throws IOException {
            if (payload != null) {
                posts.add(new Post(endpoint, payload));
                return Map.of("id", 301L);
            }
            if ((ENDPOINT + "/comments?per_page=100").equals(endpoint) && paginate) {
                return List.of(List.of(Map.of("id", 123L, "path", "src/kept.txt", "line", 1L, "body", "First note")),
                        List.of(Map.of("id", 124L, "in_reply_to_id", 123L, "body", "A reply")));
            }
            if (ENDPOINT.equals(endpoint) && !paginate) {
                String current = head;
                if (moveHeadAfterRead) {
                    head = DIFFERENT_HEAD;
                    moveHeadAfterRead = false;
                }
                return Map.of("number", 72L, "state", "open", "title", "Test PR",
                        "base", Map.of("ref", prBase, "sha", repo.base(), "repo",
                                Map.of("full_name", "example/widget", "default_branch", "main")),
                        "head", Map.of("ref", "work", "sha", current, "repo",
                                Map.of("full_name", "example/widget")));
            }
            throw new IOException("unexpected fake GitHub endpoint: " + endpoint);
        }
    }
}
