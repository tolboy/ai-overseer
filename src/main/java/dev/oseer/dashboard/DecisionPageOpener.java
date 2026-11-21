package dev.oseer.dashboard;

import dev.oseer.approval.ApprovalException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shares decision-page startup between concurrent clicks from one dashboard. */
public final class DecisionPageOpener {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    @FunctionalInterface
    public interface Publisher {
        boolean open(String url) throws Exception;
    }

    @FunctionalInterface
    public interface Starter {
        void start(Publisher publisher) throws Exception;
    }

    private record Run(Path project, String id) {}

    private final Duration startupTimeout;
    private final ConcurrentHashMap<Run, CompletableFuture<String>> starting = new ConcurrentHashMap<>();

    public DecisionPageOpener() {
        this(STARTUP_TIMEOUT);
    }

    DecisionPageOpener(Duration startupTimeout) {
        this.startupTimeout = startupTimeout;
    }

    /**
     * Returns the first page's address for the dashboard redirect. The supervisor continues
     * on its own thread and sends any subsequent pages to {@code openFollowUp}.
     */
    public String open(Path project, String runId, Starter starter, Publisher openFollowUp) throws Exception {
        Run run = new Run(project.toAbsolutePath().normalize(), runId);
        CompletableFuture<String> requested = new CompletableFuture<>();
        CompletableFuture<String> ready = starting.compute(run,
                (key, current) -> current == null || current.isDone() ? requested : current);
        if (ready == requested) {
            ready.whenComplete((url, failure) -> starting.remove(run, ready));
            try {
                Thread.ofVirtual().name("oseer-decide-" + runId)
                        .start(() -> supervise(runId, ready, starter, openFollowUp));
            } catch (RuntimeException | Error failure) {
                ready.completeExceptionally(failure);
                throw failure;
            }
        }
        try {
            return ready.get(startupTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        } catch (TimeoutException timeout) {
            throw new ApprovalException("decision_page_start_timeout",
                    "timed out waiting for the decision page for run " + runId, timeout);
        }
    }

    private static void supervise(String runId, CompletableFuture<String> ready,
                                  Starter starter, Publisher openFollowUp) {
        try {
            starter.start(url -> ready.complete(Objects.requireNonNull(url)) || openFollowUp.open(url));
            if (!ready.isDone()) {
                ready.completeExceptionally(new ApprovalException("decision_page_not_started",
                        "run " + runId + " has no pending question this page could show"));
            }
        } catch (Throwable failure) {
            ready.completeExceptionally(failure);
            if (failure instanceof Error fatal) throw fatal;
        }
    }
}
