package dev.oseer.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What an API reader is sent about the candidate, and how much of it.
 *
 * An API profile has no filesystem, so Overseer packs the evidence into the prompt itself.
 * The original packing sent the diff and the complete current text of every changed file and
 * refused the whole call when one file passed 64 KiB or the change touched more than 64
 * files. Measured 2026-11-18: an ordinary pull request that edits a 66 KiB README could not
 * be reviewed by any API reader at all, although its diff was a quarter of the budget.
 *
 * {@code mode} chooses what goes in:
 * <ul>
 *   <li>{@code full} — the diff and every changed file whole; anything over a limit refuses
 *       the call, naming the file and the limit. The historical behaviour.</li>
 *   <li>{@code diff} — the diff and the list of changed paths, no whole files. Only the diff
 *       itself has to fit {@code max_bytes}.</li>
 *   <li>{@code auto} (default) — {@code full} when it fits; otherwise {@code diff}, with a
 *       note in the prompt and the run evidence naming what did not fit and why. A diff that
 *       does not fit either still refuses: nothing is ever truncated.</li>
 * </ul>
 *
 * @param maxFiles     changed files whose whole contents may be included
 * @param maxFileBytes bytes of one changed file's whole contents
 * @param maxBytes     bytes of the complete snapshot (instructions, diff and files)
 */
public record ApiContextSettings(String mode, int maxFiles, int maxFileBytes, int maxBytes) {
    public static final Set<String> MODES = Set.of("auto", "full", "diff");
    public static final int DEFAULT_MAX_FILES = 64;
    public static final int DEFAULT_MAX_FILE_BYTES = 64 * 1024;
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;
    /** The largest snapshot a profile may declare; about 1M tokens is past every current model. */
    public static final int CEILING_BYTES = 4 * 1024 * 1024;
    public static final int CEILING_FILES = 4096;
    public static final ApiContextSettings DEFAULT = new ApiContextSettings("auto",
            DEFAULT_MAX_FILES, DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_BYTES);

    static final Set<String> KEYS = Set.of("mode", "max_files", "max_file_bytes", "max_bytes");

    public ApiContextSettings {
        if (mode == null || !MODES.contains(mode)) {
            throw new IllegalArgumentException("api_context.mode must be one of " + new java.util.TreeSet<>(MODES));
        }
        if (maxFiles < 1 || maxFiles > CEILING_FILES) {
            throw new IllegalArgumentException("api_context.max_files must be between 1 and " + CEILING_FILES);
        }
        if (maxFileBytes < 1 || maxFileBytes > CEILING_BYTES || maxBytes < 1 || maxBytes > CEILING_BYTES) {
            throw new IllegalArgumentException("api_context byte limits must be between 1 and " + CEILING_BYTES);
        }
    }

    public boolean wholeFilesAllowed() { return !"diff".equals(mode); }

    public boolean fallsBackToDiff() { return "auto".equals(mode); }

    /** Read from a profile's {@code api_context:} block; absent keys keep their defaults. */
    static ApiContextSettings parse(Values block) {
        block.rejectUnknownKeys(KEYS);
        String mode = block.requireEnum("mode", MODES, DEFAULT.mode());
        int files = (int) block.optInt("max_files", DEFAULT_MAX_FILES, 1, CEILING_FILES);
        int fileBytes = (int) block.optInt("max_file_bytes", DEFAULT_MAX_FILE_BYTES, 1, CEILING_BYTES);
        int bytes = (int) block.optInt("max_bytes", DEFAULT_MAX_BYTES, 1, CEILING_BYTES);
        return new ApiContextSettings(mode == null ? DEFAULT.mode() : mode, files, fileBytes, bytes);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", mode);
        value.put("max_files", (long) maxFiles);
        value.put("max_file_bytes", (long) maxFileBytes);
        value.put("max_bytes", (long) maxBytes);
        return value;
    }
}
