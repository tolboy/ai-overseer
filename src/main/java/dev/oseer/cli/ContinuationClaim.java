package dev.oseer.cli;

import dev.oseer.json.Json;
import dev.oseer.json.JsonFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContinuationClaim {
    private static final Pattern NUMBERED_RUN = Pattern.compile("^(.*-)(\\d+)$");
    private static final int SEQUENTIAL_ID_ATTEMPTS = 50;

    private ContinuationClaim() {}

    /** Beside an answered run: the run that continues it, written once. */
    public static final String CONTINUATION_CLAIM = "continuation.json";

    /**
     * Record that {@code nextId} continues {@code runId}, unless a run already does: null when
     * this call made the record, the existing one otherwise.
     *
     * An answer is consumed once. The same `retry` or `apply` could be seen by a supervisor
     * and by `oseer approve` without `--no-start`, or by two supervisors, and each started a
     * continuation; {@link #nextRunId} gave the second a different unused id, so both ran
     * against one worktree. Claims are read and written under one cross-process lock, before
     * any run folder exists.
     *
     * A claim is a reservation, not a run. It stands while the run it names has reserved its
     * evidence ({@code run.json}, written before the first paid call), or while the process
     * that made it is alive and may still be starting it. A claim with neither — its process
     * died between the claim and the run — is taken over, so the answer is not lost to a
     * crash; a failed start in a live process gives its claim back itself
     * ({@link #releaseContinuation}).
     */
    public static Map<String, Object> claimContinuation(Path root, String runId, String nextId)
            throws IOException {
        Path claim = root.resolve(".oseer/runs").resolve(runId).resolve(CONTINUATION_CLAIM);
        return JsonFile.underExclusiveLock(claim, CONTINUATION_CLAIM + ".lock", () -> {
            if (Files.exists(claim, LinkOption.NOFOLLOW_LINKS)) {
                Map<String, Object> existing;
                try {
                    existing = Json.parseObject(Files.readString(claim,
                            StandardCharsets.UTF_8));
                } catch (Exception unreadable) {
                    // Nothing says which run it protects, so it protects all of them.
                    return Map.<String, Object>of("run_id", "(unreadable claim: " + claim + ")");
                }
                if (continuationRunCreated(root, String.valueOf(existing.get("run_id")))
                        || claimantAlive(existing)) {
                    return existing;
                }
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("run_id", nextId);
            record.put("continues", runId);
            record.put("pid", ProcessHandle.current().pid());
            record.put("process_started_at", ProcessHandle.current().info().startInstant()
                    .map(Object::toString).orElse(null));
            record.put("claimed_at", Instant.now().toString());
            JsonFile.writeAtomically(claim, record);
            return null;
        });
    }

    /** Give back this process's claim for {@code nextId}, if the run never came to exist. */
    public static void releaseContinuation(Path root, String runId, String nextId) {
        Path claim = root.resolve(".oseer/runs").resolve(runId).resolve(CONTINUATION_CLAIM);
        try {
            JsonFile.underExclusiveLock(claim, CONTINUATION_CLAIM + ".lock", () -> {
                if (!Files.isRegularFile(claim, LinkOption.NOFOLLOW_LINKS)) return null;
                Map<String, Object> existing = Json.parseObject(Files.readString(claim,
                        StandardCharsets.UTF_8));
                boolean ours = nextId.equals(existing.get("run_id"))
                        && existing.get("pid") instanceof Number pid && pid.longValue() == ProcessHandle.current().pid();
                if (ours && !continuationRunCreated(root, nextId)) Files.delete(claim);
                return null;
            });
        } catch (Exception cannotRelease) {
            // The claim then waits for this process to end, and is taken over after that.
        }
    }

    /** Whether {@code runId} reserved its evidence, which every run does before its first paid call. */
    static boolean continuationRunCreated(Path root, String runId) {
        // A name that is not a run id cannot be looked up, so it is treated as a run that exists.
        if (runId == null || !runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) return true;
        Path directory = root.resolve(".oseer/runs").resolve(runId);
        return Files.isRegularFile(directory.resolve("run.json"))
                || Files.isRegularFile(directory.resolve("task-run.json"));
    }

    /** Whether the process that wrote a claim is still the one running under its pid. */
    private static boolean claimantAlive(Map<String, Object> claim) {
        if (!(claim.get("pid") instanceof Number pid)) return false;
        Object started = claim.get("process_started_at");
        return ProcessHandle.of(pid.longValue())
                .filter(ProcessHandle::isAlive)
                .filter(process -> started == null || String.valueOf(started).equals(
                        process.info().startInstant().map(Object::toString).orElse(null)))
                .isPresent();
    }

    /** bakery-3 → bakery-4; a name without a trailing number gets {@code -2}. */
    static String nextRunId(Path root, String runId) {
        String candidate = bumpRunId(runId);
        Path runs = root.resolve(".oseer/runs");
        for (int i = 0; i < SEQUENTIAL_ID_ATTEMPTS; i++) {
            if (!Files.isDirectory(runs.resolve(candidate))) return candidate;
            candidate = bumpRunId(candidate);
        }
        return runId + "-" + Long.toHexString(System.currentTimeMillis());
    }

    public static String bumpRunId(String runId) {
        Matcher trailing = NUMBERED_RUN.matcher(runId);
        if (trailing.matches()) {
            return trailing.group(1) + (Long.parseLong(trailing.group(2)) + 1);
        }
        return runId + "-2";
    }
}
