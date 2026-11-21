package dev.oseer;

import dev.oseer.ledger.CitationCheck;
import dev.oseer.ledger.Findings;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A product finding blocks only when its file, line and quote are in the reviewed tree. */
public final class CitationCheckTest implements Suite {

    @Override public String name() { return "citation"; }

    @Override public void run(Check check) throws Exception {
        Path tree = Files.createTempDirectory("oseer-cite-");
        Path source = tree.resolve("src/Parse.java");
        Files.createDirectories(source.getParent());
        // The distant marker sits more than WINDOW lines below the early lines, so a quote of it
        // cannot pass a citation of line 2. The method body itself stays on lines 4 and 5.
        Files.writeString(source, """
                class Parse {
                    int ready;
                    Object read() {
                        if (ready) return null;
                        return this;
                    }
                }
                """ + "\n".repeat(25) + "// sentinel FARAWAY_MARKER stays distant\n");

        Findings.Finding cited = verify(tree, finding("real", "P1", "product_defect", "src/Parse.java",
                Map.of("line", 4L, "quote", "if (ready) return null;"))).get(0);
        check.eq("a quote on the cited line stays a P1", "P1", cited.severity());
        check.that("and is not flagged", !cited.unverified());

        Findings.Finding ranged = verify(tree, finding("range", "P1", "product_defect", "src/Parse.java:4-5",
                Map.of("quote", "return this;"))).get(0);
        check.eq("a path:start-end quote in range stays a P1", "P1", ranged.severity());
        check.that("a Unicode line-range dash is accepted on Windows too",
                verify(tree, finding("range", "P1", "product_defect", "src/Parse.java:4\u20135",
                        Map.of("quote", "return this;"))).getFirst().blocking());
        check.that("malformed file syntax becomes an unverified finding",
                verify(tree, finding("malformed", "P1", "product_defect", "src/Parse.java:bad-range",
                        Map.of())).getFirst().unverified());
        check.eq("zero is not a valid line on the path", "line_missing",
                verify(tree, finding("zero", "P1", "product_defect", "src/Parse.java:0",
                        Map.of())).getFirst().raw().get("verification_reason"));

        Findings.Finding elsewhere = verify(tree, finding("elsewhere", "P1", "product_defect", "src/Parse.java",
                Map.of("line", 2L, "quote", "sentinel FARAWAY_MARKER"))).get(0);
        check.eq("a quote that exists only far from the line is not a blocker", "P2", elsewhere.severity());
        check.eq("the reason is the quote", "quote_missing", elsewhere.raw().get("verification_reason"));
        check.eq("the vendor severity is kept", "P1", elsewhere.toMap().get("severity_as_filed"));
        check.that("and it does not block", !elsewhere.blocking());

        Findings.Finding past = verify(tree, finding("past", "P1", "product_defect", "src/Parse.java",
                Map.of("line", 400L))).get(0);
        check.eq("a line past the end of the file is unverified", "line_missing",
                past.raw().get("verification_reason"));

        Findings.Finding missing = verify(tree, finding("missing", "P1", "product_defect", "src/NoSuch.java",
                Map.of("line", 1L, "message", "nothing compiles here"))).get(0);
        check.eq("a missing file is unverified", "file_missing", missing.toMap().get("verification_reason"));
        check.that("the detail names the file", String.valueOf(missing.toMap().get("verification_detail"))
                .contains("src/NoSuch.java"));

        Findings.Finding escaped = verify(tree, finding("escape", "P1", "product_defect", "../secret.txt",
                Map.of())).get(0);
        check.eq("a path that leaves the tree is unverified", "path_escapes",
                escaped.raw().get("verification_reason"));

        Findings.Finding blank = verify(tree, finding("blank", "P1", "product_defect", "  ", Map.of())).get(0);
        check.eq("an empty path is unverified", "path_missing", blank.raw().get("verification_reason"));

        Findings.Finding gap = verify(tree, finding("gap", "P1", "contract_gap", "src/NoSuch.java",
                Map.of("line", 9L))).get(0);
        check.eq("a contract gap is not a citation check", "P1", gap.severity());
        check.that("and is not flagged", !gap.toMap().containsKey("unverified"));

        Findings.Finding note = verify(tree, finding("note", "P3", "product_defect", "src/NoSuch.java",
                Map.of())).get(0);
        check.eq("a P3 is left as filed", "P3", note.severity());

        Findings.Finding p2 = verify(tree, finding("soft", "P2", "product_defect", "src/NoSuch.java",
                Map.of())).get(0);
        check.eq("a P2 stays a P2", "P2", p2.severity());
        check.eq("and is still flagged", true, p2.toMap().get("unverified"));
        check.that("without a severity rewrite", !p2.toMap().containsKey("severity_as_filed"));

        Findings.Finding backtick = verify(tree, finding("ticks", "P1", "product_defect", "src/Parse.java",
                Map.of("line", 4L, "actual", "the method does `if (ready) return null;`"))).get(0);
        check.eq("a backtick span of code on the line stays a P1", "P1", backtick.severity());

        Findings.Finding invented = verify(tree, finding("invented", "P1", "product_defect", "src/Parse.java",
                Map.of("line", 4L, "actual", "it calls `explodeNow()`"))).get(0);
        check.eq("a backtick span that is not in the file is unverified", "quote_missing",
                invented.raw().get("verification_reason"));

        Findings.Finding pathOnly = verify(tree, finding("named", "P1", "product_defect", "src/Parse.java",
                Map.of("actual", "see `src/Parse.java`"))).get(0);
        check.eq("a backtick that only names the file is not a quote", "P1", pathOnly.severity());

        Findings.Finding prose = verify(tree, finding("prose", "P1", "product_defect", "src/Parse.java",
                Map.of("actual", "the reader claimed `nothing in this sentence is code at all`"))).get(0);
        check.eq("a backtick of prose is not treated as a code quote", "P1", prose.severity());

        Findings.Finding missingCall = verify(tree, finding("omitted-call", "P1", "product_defect", "src/Parse.java",
                Map.of("line", 4L, "quote", "if (ready) return null;",
                       "message", "The early return should call `releaseResource()`",
                       "expected", "Call `releaseResource()` before returning",
                       "suggestion", "Use `releaseResource()` before this return"))).getFirst();
        check.that("a P1 for missing behavior is checked against the current source quote", missingCall.blocking());

        Findings.Finding disagree = verify(tree, finding("split", "P1", "product_defect", "src/Parse.java:4",
                Map.of("line", 2L, "quote", "int ready;"))).get(0);
        check.eq("a path line and a line field that disagree are unverified", "line_disagrees",
                disagree.raw().get("verification_reason"));

        var first = Findings.round("review", 1, "tree", List.of(missing), null);
        var second = Findings.round("review", 2, "tree",
                verify(tree, finding("missing", "P1", "product_defect", "src/NoSuch.java", Map.of("line", 1L))),
                first);
        check.eq("the first recorded round was already non-blocking", List.of(), first.get("blocking_ids"));
        check.eq("so the same unverified citation is not severity laundering",
                List.of(), second.get("severity_downgraded"));

        Path current = tree.resolve(".oseer/runs/current--visual-qa-0/screenshots");
        Path previous = tree.resolve(".oseer/runs/previous--visual-qa-0/screenshots");
        Files.createDirectories(current);
        Files.createDirectories(previous);
        Path actual = Files.write(current.resolve("mobile.png"), new byte[] {1});
        Path other = Files.write(previous.resolve("mobile.png"), new byte[] {1});
        check.that("a visual P1 backed by its harness screenshot stays blocking",
                visualFinding(tree, List.of(actual), actual.toString()).blocking());
        check.that("a specific relative evidence path stays blocking",
                visualFinding(tree, List.of(actual), tree.relativize(actual).toString()).blocking());
        check.that("a unique authorized screenshot filename stays blocking",
                visualFinding(tree, List.of(actual), "mobile.png").blocking());
        check.that("a screenshot from another run cannot back the finding",
                visualFinding(tree, List.of(actual), other.toString()).unverified());
        check.that("an ambiguous filename does not identify an authorized screenshot",
                visualFinding(tree, List.of(actual, other), "mobile.png").unverified());
        check.that("an authorized screenshot that goes missing cannot back the finding",
                visualFinding(tree, List.of(current.resolve("missing.png")), "missing.png").unverified());
        Findings.Finding firstView = derivedVisualFinding(tree, actual);
        Findings.Finding nextView = derivedVisualFinding(tree, other);
        check.eq("a new attempt directory does not rename the same visual finding", firstView.id(), nextView.id());
        check.eq("the visual finding still names its actual screenshot", other.toString(), nextView.path());
        check.eq("an explicit reviewer ID is preserved", "visual",
                visualFinding(tree, List.of(other), other.toString()).id());
    }

    private static Findings.Finding derivedVisualFinding(Path tree, Path screenshot) {
        Map<String, Object> row = finding("unused", "P1", "product_defect", screenshot.toString(),
                Map.of("viewport", "390x844"));
        row.remove("id");
        return CitationCheck.verifyVisual(Findings.of(Map.of("findings", List.of(row))), tree,
                List.of(screenshot)).getFirst();
    }

    private static Findings.Finding visualFinding(Path tree, List<Path> screenshots, String path) {
        var findings = Findings.of(Map.of("findings", List.of(finding("visual", "P1", "product_defect",
                path, Map.of("actual", "The red text `Text is cut off` is clipped")))));
        return CitationCheck.verifyVisual(findings, tree, screenshots).getFirst();
    }

    private static List<Findings.Finding> verify(Path tree, Map<String, Object> row) {
        return CitationCheck.verify(Findings.of(Map.of("findings", List.of(row))), tree);
    }

    private static Map<String, Object> finding(String id, String severity, String category, String path,
                                               Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("severity", severity);
        row.put("category", category);
        row.put("path", path);
        row.put("message", extra.getOrDefault("message", "the behaviour is wrong"));
        row.put("expected", "the guarded return");
        row.put("actual", "it falls through");
        row.put("confidence", "confirmed");
        extra.forEach(row::put);
        return row;
    }
}
