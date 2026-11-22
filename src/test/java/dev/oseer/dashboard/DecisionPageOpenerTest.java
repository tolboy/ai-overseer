package dev.oseer.dashboard;

import dev.oseer.approval.ApprovalException;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DecisionPageOpenerTest implements Suite {
    private static final Path PROJECT = Path.of(".");
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    @Override public String name() { return "decision-page-opener"; }

    @Override public void run(Check check) throws Exception {
        sharesStartupAndOpensFollowUps(check);
        failedStartupCanBeRetried(check);
        timeoutLeavesTheSharedStartupRunning(check);
    }

    private static void sharesStartupAndOpensFollowUps(Check check) throws Exception {
        var opener = new DecisionPageOpener(TEST_TIMEOUT);
        var started = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        var followUp = new CompletableFuture<String>();
        var virtual = new AtomicBoolean();
        var first = new FutureTask<>(() -> opener.open(PROJECT, "run-a", publisher -> {
            virtual.set(Thread.currentThread().isVirtual());
            started.countDown();
            publish.await();
            publisher.open("first");
            publisher.open("next");
        }, followUp::complete));
        Thread.ofVirtual().start(first);
        try {
            check.that("the supervisor starts", started.await(10, TimeUnit.SECONDS));
            var second = new FutureTask<>(() -> opener.open(PROJECT.toAbsolutePath(), "run-a", publisher -> {
                throw new IllegalStateException("a second supervisor started");
            }, ignored -> false));
            Thread secondRequest = Thread.ofVirtual().start(second);
            check.that("the duplicate click waits for the same startup", awaitsStartup(secondRequest));
            check.eq("an unrelated run can start independently", "other",
                    opener.open(PROJECT, "run-b", publisher -> publisher.open("other"), ignored -> false));
            publish.countDown();
            check.eq("the first click redirects to the page", "first", first.get(10, TimeUnit.SECONDS));
            check.eq("the duplicate click redirects to the same page", "first", second.get(10, TimeUnit.SECONDS));
            check.eq("a later page opens through the supplied callback", "next", followUp.get(10, TimeUnit.SECONDS));
            check.that("supervision runs on a virtual thread", virtual.get());
        } finally {
            publish.countDown();
        }
    }

    private static boolean awaitsStartup(Thread request) throws InterruptedException {
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        while (request.isAlive() && System.nanoTime() < deadline) {
            if (request.getState() == Thread.State.TIMED_WAITING) return true;
            Thread.sleep(Duration.ofMillis(5));
        }
        return false;
    }

    private static void failedStartupCanBeRetried(Check check) throws Exception {
        var opener = new DecisionPageOpener(TEST_TIMEOUT);
        check.rejects("a supervisor that never publishes reports no page", "has no pending question",
                () -> opener.open(PROJECT, "run", publisher -> {}, ignored -> false));
        check.rejects("a startup failure preserves the underlying message", "unavailable",
                () -> opener.open(PROJECT, "run", publisher -> {
                    throw new ApprovalException("decision_supervised_elsewhere", "unavailable");
                }, ignored -> false));
        check.eq("another click can retry after startup fails", "retried",
                opener.open(PROJECT, "run", publisher -> publisher.open("retried"), ignored -> false));
    }

    private static void timeoutLeavesTheSharedStartupRunning(Check check) throws Exception {
        var opener = new DecisionPageOpener(Duration.ofMillis(75));
        var publish = new CountDownLatch(1);
        var published = new CompletableFuture<Boolean>();
        try {
            check.rejects("opening waits only for its startup deadline", "timed out",
                    () -> opener.open(PROJECT, "slow", publisher -> {
                        publish.await();
                        published.complete(publisher.open("slow"));
                    }, ignored -> false));
            check.rejects("timeout of one click leaves the same supervisor in place", "timed out",
                    () -> opener.open(PROJECT, "slow", publisher -> {
                        throw new IllegalStateException("a second supervisor started");
                    }, ignored -> false));
        } finally {
            publish.countDown();
        }
        check.that("the supervisor can still publish its page", published.get(10, TimeUnit.SECONDS));
    }
}
