package dev.oseer.ledger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the import journal for evidence gaps that still affect a corpus report. */
final class CorpusImportReceipts {
    private CorpusImportReceipts() {}

    static List<JsonlDiagnostics.Skip> unresolvedSkips(Path importsFile, String projectId) throws IOException {
        JsonlDiagnostics.Read read = JsonlDiagnostics.read(importsFile, CorpusImport.IMPORTS);
        List<JsonlDiagnostics.Skip> skipped = new ArrayList<>(read.skipped());
        // Only an explicit re-import of the same source bytes and transform reconciles a failed receipt.
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        long anonymous = 0;
        for (Map<String, Object> row : read.rows()) {
            String fingerprint = nonBlankText(row.get("source_fingerprint"));
            String transform = nonBlankText(row.get("transform_version"));
            String key = fingerprint == null || transform == null
                    ? "unknown:" + anonymous++ : transform + ":" + fingerprint;
            latest.put(key, row);
        }
        for (Map<String, Object> row : latest.values()) {
            ImportReceipt receipt = ImportReceipt.from(row);
            if (receipt.incomplete() && receipt.affects(projectId)) {
                skipped.addAll(receipt.skips());
            }
        }
        return skipped;
    }

    private record ImportReceipt(
            boolean incomplete,
            Set<String> projectIds,
            long unknownProjectRows,
            boolean unattributedLoss,
            List<JsonlDiagnostics.Skip> skips) {

        static ImportReceipt from(Map<String, Object> row) {
            Map<?, ?> skipped = row.get("skipped") instanceof Map<?, ?> body ? body : Map.of();
            // Older importers could report complete:true even after losing rows.
            boolean incomplete = Boolean.FALSE.equals(row.get("complete"))
                    || count(row.get("failed")) > 0 || count(row.get("conflicts")) > 0
                    || count(skipped.get("count")) > 0;
            Set<String> projectIds = projectIdsOf(row.get("project_ids"));
            long unknownRows = count(row.get("unknown_project_id"));
            // A legacy incomplete receipt without a recovered identity affects every project.
            boolean unattributed = row.get("unattributed_skipped") instanceof Number known
                    ? known.longValue() > 0 : incomplete && projectIds.isEmpty() && unknownRows == 0;
            List<JsonlDiagnostics.Skip> skips = incomplete ? parseSkips(skipped.get("records")) : List.of();
            if (incomplete && skips.isEmpty()) {
                String sourceName = nonBlankText(row.get("source_name"));
                skips = List.of(new JsonlDiagnostics.Skip(
                        sourceName == null ? CorpusImport.IMPORTS : sourceName, 0L, "incomplete_import"));
            }
            return new ImportReceipt(incomplete, projectIds, unknownRows, unattributed, skips);
        }

        boolean affects(String projectId) {
            if (unattributedLoss) {
                return true;
            }
            return projectId == null || projectId.isBlank() ? unknownProjectRows > 0 : projectIds.contains(projectId);
        }
    }

    private static Set<String> projectIdsOf(Object listed) {
        Set<String> ids = new LinkedHashSet<>();
        if (listed instanceof List<?> items) {
            for (Object item : items) {
                String id = nonBlankText(item);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static List<JsonlDiagnostics.Skip> parseSkips(Object records) {
        if (!(records instanceof List<?> rows)) {
            return List.of();
        }
        List<JsonlDiagnostics.Skip> skips = new ArrayList<>();
        for (Object item : rows) {
            if (item instanceof Map<?, ?> row) {
                Object file = row.get("file");
                Object reason = row.get("reason");
                skips.add(new JsonlDiagnostics.Skip(
                        file == null ? "" : String.valueOf(file),
                        count(row.get("offset")),
                        reason == null ? "corrupt" : String.valueOf(reason)));
            }
        }
        return skips;
    }

    private static String nonBlankText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static long count(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
