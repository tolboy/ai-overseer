package dev.oseer.ledger;

import dev.oseer.config.RepoPath;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a product finding's citation is in the tree it claims to describe.
 *
 * Readers have filed P1s against lines that do not say what the finding says, and the next
 * reader then repeats the citation. This check runs before such a finding can block: the
 * file has to exist in the reviewed tree, the cited line has to exist, and a quote the
 * finding offers has to appear at that spot. It reads the worktree and calls no model.
 * A later check that the quote means what the finding claims is a separate, optional step.
 */
public final class CitationCheck {

    /** How far either side of the cited line a quote may sit and still count as "there". */
    static final int WINDOW = 20;

    /** A backtick span shorter than this is a name, not a snippet worth failing a finding over. */
    private static final int BACKTICK_MIN = 12;

    /** An explicit {@code quote} or {@code snippet} shorter than this is not evidence. */
    private static final int EXPLICIT_MIN = 8;

    /** Past this size a line or quote check is not worth reading the whole file into the loop. */
    private static final long MAX_BYTES = 2_000_000L;

    private static final Pattern LINE_RANGE = Pattern.compile("^(.*):(\\d+)[-\u2013\u2014](\\d+)$");
    private static final Pattern LINE_COLUMN = Pattern.compile("^(.*):(\\d+):(\\d+)$");
    private static final Pattern LINE_ONLY = Pattern.compile("^(.*):(\\d+)$");
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\n]+)`");

    private CitationCheck() {}

    /**
     * Product P1 and P2 findings whose citation cannot be checked are marked {@code unverified}.
     * A P1 is recorded as P2 so it does not block and a later reading of the same words is
     * not severity laundering. A P2 stays a P2. Other categories are left as filed: a
     * contract gap is about the task, not a line of the diff.
     */
    public static List<Findings.Finding> verify(List<Findings.Finding> findings, Path tree) {
        return verify(findings, finding -> sourceProblem(finding, tree));
    }

    /** Visual findings cite one of the screenshots the controller authorized for this role. */
    public static List<Findings.Finding> verifyVisual(List<Findings.Finding> findings, Path tree,
                                                       List<Path> screenshots) {
        return verify(findings, finding -> screenshotProblem(finding, tree, screenshots)).stream()
                .map(CitationCheck::visualIdentity).toList();
    }

    private static Findings.Finding visualIdentity(Findings.Finding finding) {
        if (!"derived".equals(finding.idSource()) || !"product_defect".equals(finding.category())
                || finding.path() == null || finding.path().isBlank()) return finding;
        try {
            Path name = Path.of(finding.path()).getFileName();
            if (name == null || name.toString().isBlank()) return finding;
            // The evidence directory changes with each run and attempt. The screenshot
            // name and viewport can still identify the same view on the same candidate.
            String viewport = finding.raw().get("viewport") instanceof String value ? value.strip() : "";
            String id = Findings.derive("visual:" + name + ":" + viewport, finding.message());
            return new Findings.Finding(id, finding.idSource(), finding.category(), finding.categorySource(),
                    finding.severity(), finding.path(), finding.message(), finding.raw());
        } catch (InvalidPathException malformed) {
            return finding;
        }
    }

    private static List<Findings.Finding> verify(List<Findings.Finding> findings,
                                                  Function<Findings.Finding, String> check) {
        if (findings == null || findings.isEmpty()) return findings == null ? List.of() : findings;
        List<Findings.Finding> checked = new ArrayList<>();
        for (Findings.Finding finding : findings) {
            if (!checks(finding)) {
                checked.add(finding);
                continue;
            }
            String problem = check.apply(finding);
            if (problem == null) {
                checked.add(finding);
                continue;
            }
            int split = problem.indexOf('\n');
            String reason = split < 0 ? problem : problem.substring(0, split);
            String detail = split < 0 ? reason : problem.substring(split + 1);
            checked.add(flag(finding, reason, detail));
        }
        return checked;
    }

    private static boolean checks(Findings.Finding finding) {
        if (!"product_defect".equals(finding.category())) return false;
        return "P1".equals(finding.severity()) || "P2".equals(finding.severity());
    }

    /** {@code null} when the citation holds. Otherwise {@code reason\ndetail}. */
    private static String sourceProblem(Findings.Finding finding, Path tree) {
        if (tree == null) return "tree_unavailable\nthe reviewed tree was not available to check the citation";
        Cited cited = Cited.parse(finding);
        if (cited.badNumber) {
            return "line_missing\nline is not a positive integer";
        }
        if (cited.disagree) {
            return "line_disagrees\nthe line on the path and the line field name different places";
        }
        if (cited.path == null || cited.path.isBlank()) {
            return "path_missing\nthe finding names no file";
        }
        String relative = RepoPath.normalize(cited.path);
        if (relative == null) {
            return "path_escapes\nthe path is not a file inside the reviewed tree";
        }
        Path root;
        Path file;
        try {
            root = tree.toRealPath();
            file = root.resolve(relative).normalize();
        } catch (InvalidPathException malformed) {
            return "path_invalid\nthe citation is not a valid file path";
        } catch (IOException unreadable) {
            return "file_missing\n" + relative + " is not in the reviewed tree";
        }
        if (!file.startsWith(root)) {
            return "path_escapes\nthe path is not a file inside the reviewed tree";
        }
        if (!Files.isRegularFile(file)) {
            return "file_missing\n" + relative + " is not in the reviewed tree";
        }
        try {
            file = file.toRealPath();
            if (!file.startsWith(root)) {
                return "path_escapes\nthe path is not a file inside the reviewed tree";
            }
        } catch (IOException unreadable) {
            return "file_missing\n" + relative + " is not in the reviewed tree";
        }
        boolean needsBody = cited.line != null || !cited.quotes.isEmpty();
        if (!needsBody) return null;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException unreadable) {
            return "not_readable\n" + relative + " could not be read";
        }
        if (size > MAX_BYTES) {
            return "file_too_large\n" + relative + " is too large to check a line or a quote offline";
        }
        String body;
        try {
            body = Files.readString(file);
        } catch (CharacterCodingException binary) {
            return "not_text\n" + relative + " is not a text file";
        } catch (IOException unreadable) {
            return "not_readable\n" + relative + " could not be read";
        }
        if (body.indexOf('\0') >= 0) return "not_text\n" + relative + " is not a text file";
        List<String> lines = linesOf(body);
        if (cited.line != null) {
            if (cited.line < 1 || cited.line > lines.size()) {
                return "line_missing\nline " + cited.line + " is past the end of " + relative
                        + " (" + lines.size() + " lines)";
            }
            int end = cited.end == null ? cited.line : cited.end;
            if (end < cited.line || end > lines.size()) {
                return "line_missing\nthe cited range " + cited.line + "-" + end
                        + " is not inside " + relative + " (" + lines.size() + " lines)";
            }
        }
        if (cited.quotes.isEmpty()) return null;
        String window = cited.line == null ? body : window(lines, cited.line, cited.end);
        String collapsedWindow = collapse(window);
        for (String quote : cited.quotes) {
            if (!collapsedWindow.contains(collapse(quote))) {
                String where = cited.line == null ? relative
                        : relative + ":" + cited.line + (cited.end != null && !cited.end.equals(cited.line)
                        ? "-" + cited.end : "");
                return "quote_missing\nquoted text was not found within " + WINDOW + " lines of " + where;
            }
        }
        return null;
    }

    private static String screenshotProblem(Findings.Finding finding, Path tree, List<Path> screenshots) {
        if (finding.path() == null || finding.path().isBlank()) {
            return "path_missing\nthe finding names no screenshot";
        }
        try {
            Path cited = Path.of(finding.path().strip());
            if (tree == null || screenshots == null || screenshots.isEmpty()) {
                return "screenshot_missing\nthe citation does not name an available screenshot from this run";
            }
            Path root = tree.toRealPath();
            Path runs = root.resolve(".oseer/runs").toRealPath();
            if (!runs.startsWith(root)) return "path_escapes\nthe screenshots directory is outside the reviewed tree";
            boolean nameOnly = !cited.isAbsolute() && cited.getNameCount() == 1;
            Path requested = nameOnly ? null : root.resolve(cited).normalize().toRealPath();
            Set<Path> matches = new LinkedHashSet<>();
            for (Path screenshot : screenshots) {
                Path path = root.resolve(screenshot).normalize();
                // Accept a bare filename only when it identifies a single authorized image.
                if (nameOnly && !cited.equals(path.getFileName())) continue;
                if (!Files.isRegularFile(path)) continue;
                Path real = path.toRealPath();
                if (real.startsWith(runs) && (nameOnly || real.equals(requested))
                        && Files.size(real) > 0) matches.add(real);
            }
            if (matches.size() == 1) return null;
        } catch (InvalidPathException malformed) {
            return "path_invalid\nthe citation is not a valid screenshot path";
        } catch (IOException unreadable) {
            // An evidence directory or screenshot that no longer exists cannot support a finding.
        }
        return "screenshot_missing\nthe citation does not name an available screenshot from this run";
    }

    private static Findings.Finding flag(Findings.Finding finding, String reason, String detail) {
        Map<String, Object> copy = new LinkedHashMap<>(finding.raw());
        copy.put("unverified", true);
        copy.put("verification_reason", reason);
        copy.put("verification_detail", detail);
        copy.put("advisory", true);
        String severity = finding.severity();
        if ("P1".equals(severity)) {
            copy.put("severity", "P2");
            copy.put("severity_as_filed", "P1");
            severity = "P2";
        }
        return new Findings.Finding(finding.id(), finding.idSource(), finding.category(),
                finding.categorySource(), severity, finding.path(), finding.message(), copy);
    }

    private static List<String> linesOf(String body) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            if (body.charAt(index) != '\n') continue;
            int end = index;
            if (end > start && body.charAt(end - 1) == '\r') end--;
            lines.add(body.substring(start, end));
            start = index + 1;
        }
        if (start < body.length()) {
            String tail = body.substring(start);
            if (tail.endsWith("\r")) tail = tail.substring(0, tail.length() - 1);
            lines.add(tail);
        }
        return lines;
    }

    private static String window(List<String> lines, int line, Integer endLine) {
        int end = endLine == null ? line : endLine;
        int from = Math.max(1, line - WINDOW);
        int to = Math.min(lines.size(), end + WINDOW);
        StringBuilder text = new StringBuilder();
        for (int number = from; number <= to; number++) {
            text.append(lines.get(number - 1)).append('\n');
        }
        return text.toString();
    }

    static String collapse(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private record Cited(String path, Integer line, Integer end, List<String> quotes,
                         boolean disagree, boolean badNumber) {
        static Cited parse(Findings.Finding finding) {
            String rawPath = finding.path() == null ? "" : finding.path().strip();
            Integer line = null;
            Integer end = null;
            boolean disagree = false;
            boolean badNumber = false;
            String path = rawPath;
            Matcher range = LINE_RANGE.matcher(rawPath);
            Matcher column = LINE_COLUMN.matcher(rawPath);
            Matcher only = LINE_ONLY.matcher(rawPath);
            Integer pathLine = null;
            Integer pathEnd = null;
            if (range.matches() && !range.group(1).isBlank()) {
                path = range.group(1);
                pathLine = parsePositive(range.group(2));
                pathEnd = parsePositive(range.group(3));
                badNumber = pathLine == null || pathEnd == null;
            } else if (column.matches() && !column.group(1).isBlank()) {
                path = column.group(1);
                pathLine = parsePositive(column.group(2));
                badNumber = pathLine == null || parsePositive(column.group(3)) == null;
            } else if (only.matches() && !only.group(1).isBlank()) {
                path = only.group(1);
                pathLine = parsePositive(only.group(2));
                badNumber = pathLine == null;
            }
            Object rawLine = finding.raw().get("line");
            if (rawLine != null) {
                line = positiveLine(rawLine);
                if (line == null) badNumber = true;
            }
            Object rawEnd = finding.raw().get("end_line");
            if (rawEnd != null) {
                end = positiveLine(rawEnd);
                if (end == null) badNumber = true;
            }
            if (line == null) line = pathLine;
            else if (pathLine != null && !line.equals(pathLine)) disagree = true;
            if (end == null) end = pathEnd;
            else if (pathEnd != null && !end.equals(pathEnd)) disagree = true;
            return new Cited(path, line, end, quotedSpans(finding, path), disagree,
                    badNumber || end != null && line == null);
        }
    }

    /** The record component is also named quotes, so the collector cannot share that name. */
    private static List<String> quotedSpans(Findings.Finding finding, String path) {
        List<String> quotes = new ArrayList<>();
        for (String field : List.of("quote", "snippet")) {
            String explicit = text(finding.raw().get(field));
            if (explicit.length() >= EXPLICIT_MIN) quotes.add(explicit);
        }
        if (!quotes.isEmpty()) return quotes;
        Matcher matcher = BACKTICK.matcher(text(finding.raw().get("actual")));
        while (matcher.find()) {
            String span = matcher.group(1).strip();
            if (span.length() < BACKTICK_MIN) continue;
            if (isPathCitation(span, path)) continue;
            if (span.indexOf(' ') >= 0 && !looksLikeCode(span)) continue;
            quotes.add(span);
        }
        return quotes;
    }

    /** A backtick that only names the file is a citation, not a claim about its contents. */
    private static boolean isPathCitation(String span, String path) {
        String folded = span.replace('\\', '/');
        if (path != null && (folded.equals(path) || folded.startsWith(path + ":"))) return true;
        return folded.indexOf(' ') < 0 && folded.indexOf('/') >= 0 && !looksLikeCode(folded);
    }

    private static boolean looksLikeCode(String span) {
        for (int index = 0; index < span.length(); index++) {
            switch (span.charAt(index)) {
                case '(', ')', '{', '}', '[', ']', '=', ';', '<', '>' -> {
                    return true;
                }
                default -> {}
            }
        }
        return false;
    }

    static Integer positiveLine(Object raw) {
        if (raw instanceof Number number) {
            long value = number.longValue();
            if (value >= 1 && value <= Integer.MAX_VALUE) return (int) value;
            return null;
        }
        if (raw instanceof String text) return parsePositive(text.strip());
        return null;
    }

    private static Integer parsePositive(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            int value = Integer.parseInt(text);
            return value >= 1 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object raw) {
        return raw instanceof String value ? value : "";
    }
}
