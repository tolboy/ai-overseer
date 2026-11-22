package dev.oseer.dashboard;

import dev.oseer.dashboard.DecisionPage.Shot;
import dev.oseer.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.oseer.dashboard.DecisionPage.escape;

/** Reads the current run's browser evidence and presents it on the decision page. */
final class BrowserEvidence {
    /** Fits a full browser pass below the decision controls. */
    private static final int MAX_SHOTS = 12;

    record Sections(String checks, String screenshots) {}

    private final Path root;
    private final String runId;

    BrowserEvidence(Path root, String runId) {
        this.root = root;
        this.runId = runId;
    }

    Sections sections() {
        List<Map<String, Object>> scenarios = harnessScenarios();
        List<Shot> shots = shots(scenarios);
        return new Sections(browserChecks(scenarios, !shots.isEmpty()), screenshots(shots));
    }

    private static String screenshots(List<Shot> shots) {
        StringBuilder html = new StringBuilder();
        if (!shots.isEmpty()) {
            html.append("<h2 id=shots>{{t:decision.screenshots}}</h2><p class=muted>{{t:decision.in_the_order_the_browser}} "
                    + "{{t:decision.the_tooltip_shows_what_was}}</p><div class=shots>");
            for (int index = 0; index < shots.size(); index++) {
                Shot shot = shots.get(index);
                html.append("<figure");
                if (!shot.checked().isEmpty()) html.append(" title='").append(escape(shot.checked())).append("'");
                html.append("><img alt='' src='shot/").append(index).append("'><figcaption>")
                        .append(escape(shot.caption())).append("</figcaption></figure>");
            }
            html.append("</div>");
        }
        return html.toString();
    }

    /**
     * This run's screenshots, captioned from the harness's own report and in the order it took
     * them; nothing outside this run's evidence is served.
     *
     * A file name is not a caption. On the first all-Orca run the page listed
     * `1280x720-after-click-3.png` beside `1280x720.png`, the two looked the same, and the
     * operator could not tell that the second was the page after the button's second press,
     * checked visible, rather than a duplicate of the first.
     *
     * Nor is the list of checks one. Captions that carried every assertion made after the press
     * ran to a line of selectors under each picture on the traffic-light run (2026-09-24); the
     * caption now says when the picture was taken, and what was checked on it is the tooltip.
     */
    List<Shot> shots() {
        return shots(harnessScenarios());
    }

    private List<Shot> shots(List<Map<String, Object>> scenarios) {
        List<Path> files = pictureFiles();
        Map<Path, Frame> frames = new LinkedHashMap<>();
        for (Map<String, Object> scenario : scenarios) {
            String viewport = viewport(scenario);
            Path base = inRun(scenario.get("screenshot"), files);
            Frame frame = base == null ? null
                    : frames.computeIfAbsent(base, key -> new Frame()).taken(viewport + " {{t:decision.page_opened}}");
            int clicks = 0;
            for (Map<String, Object> step : steps(scenario)) {
                boolean failed = Boolean.FALSE.equals(step.get("ok"));
                if (!"click".equals(step.get("assertion"))) {
                    if (frame != null) frame.checked(stepText(step), failed);
                    continue;
                }
                clicks++;
                // The harness names the file after the step's place in the scenario, not after
                // the press, so every scenario that presses first at step 1 shares one picture.
                Path shot = inRun(step.get("screenshot_after"), files);
                frame = shot == null ? null : frames.computeIfAbsent(shot, key -> new Frame())
                        .taken(viewport + " {{t:decision.after}} " + clicks + "{{t:decision.click_s}} " + target(step));
                if (frame != null && failed) frame.checked(stepText(step), true);
            }
        }
        List<Shot> shots = new ArrayList<>();
        for (Map.Entry<Path, Frame> entry : frames.entrySet()) {
            if (shots.size() >= MAX_SHOTS) return shots;
            Frame frame = entry.getValue();
            shots.add(new Shot(entry.getKey(), String.join("; ", frame.taken)
                    + (frame.failed ? " {{t:decision.check_failed}}" : ""),
                    frame.checked.isEmpty() ? "" : "{{t:decision.checked_in_screenshot}}\n" + String.join("\n", frame.checked)));
        }
        for (Path file : files) {
            if (shots.size() >= MAX_SHOTS) break;
            if (!frames.containsKey(file)) shots.add(new Shot(file, file.getFileName().toString(), ""));
        }
        return shots;
    }

    /** One picture as the scenarios saw it: when it was taken, and what was checked on it. */
    private static final class Frame {
        final Set<String> taken = new LinkedHashSet<>();
        final Set<String> checked = new LinkedHashSet<>();
        boolean failed;

        Frame taken(String when) {
            taken.add(when);
            return this;
        }

        void checked(String what, boolean failedHere) {
            checked.add(what);
            failed |= failedHere;
        }
    }

    /**
     * What the browser checked: one line to read, each failure spelled out, and every step of
     * every scenario folded away.
     *
     * Every scenario used to be its own line of selectors above the buttons. The traffic-light
     * run's eleven filled a screen with `css=.housing[data-probe-glow=green] виден ✓ → …`, and
     * the operator read it as detail a person deciding does not need (2026-09-24).
     */
    private static String browserChecks(List<Map<String, Object>> scenarios, boolean shotsBelow) {
        if (scenarios.isEmpty()) return "";
        List<Map<String, Object>> failed = new ArrayList<>();
        Set<String> viewports = new LinkedHashSet<>();
        for (Map<String, Object> scenario : scenarios) {
            if (!Boolean.TRUE.equals(scenario.get("ok"))) failed.add(scenario);
            viewports.add(viewport(scenario));
        }
        int total = scenarios.size();
        StringBuilder html = new StringBuilder("<h2>{{t:decision.what_the_browser_checked}}</h2><p>");
        if (failed.isEmpty()) {
            html.append("<b class=pass>✓ ").append(total).append(' ')
                    .append(plural(total, "{{t:shell.check}}", "{{t:decision.checks}}", "{{t:decision.checks_2}}"))
                    .append(total == 1 ? "{{t:decision.passed}}" : "{{t:decision.all_passed}}").append("</b>");
        } else {
            html.append("<b class=bad-inline>✗ ").append(failed.size()).append(" {{t:decision.of}} ").append(total).append(' ')
                    .append(plural(total, "{{t:decision.checks}}", "{{t:decision.checks_2}}", "{{t:decision.checks_2}}"))
                    .append(plural(failed.size(), " {{t:decision.failed_2}}", " {{t:decision.failed_3}}", " {{t:decision.failed_3}}")).append("</b>");
        }
        html.append(" <span class=muted>· ").append(escape(String.join(", ", viewports)));
        if (shotsBelow) html.append(" · <a href='#shots'>{{t:decision.screenshots_below}}</a>");
        html.append("</span></p>");
        if (!failed.isEmpty()) {
            html.append("<ul>");
            for (Map<String, Object> scenario : failed) html.append("<li>").append(scenarioLine(scenario)).append("</li>");
            html.append("</ul>");
        }
        html.append("<details><summary>{{t:decision.all_checks_by_step}}</summary><ul>");
        for (Map<String, Object> scenario : scenarios) html.append("<li>").append(scenarioLine(scenario)).append("</li>");
        return html.append("</ul></details>").toString();
    }

    /** One scenario as a person would say it, with why it failed when it did. */
    private static String scenarioLine(Map<String, Object> scenario) {
        boolean ok = Boolean.TRUE.equals(scenario.get("ok"));
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> step : steps(scenario)) parts.add(stepText(step));
        String raw = String.valueOf(scenario.get("raw"));
        if (parts.isEmpty() && raw.contains("no-console-errors")) parts.add("{{t:decision.no_console_errors}}");
        StringBuilder line = new StringBuilder(ok ? "✓ " : "✗ ").append(escape(viewport(scenario))).append(": ")
                .append(escape(String.join(" → ", parts)));
        if (!ok && scenario.get("why") != null) {
            line.append(" <span class=bad-inline>").append(escape(String.valueOf(scenario.get("why")))).append("</span>");
        }
        return line.toString();
    }

    /** The Russian noun form for {@code count}: one, few (2–4) or many. */
    private static String plural(long count, String one, String few, String many) {
        long tens = count % 100;
        long units = count % 10;
        if (tens >= 11 && tens <= 14) return many;
        if (units == 1) return one;
        return units >= 2 && units <= 4 ? few : many;
    }

    private List<Map<String, Object>> harnessScenarios() {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Path directory : runDirectories()) {
            for (Path report : List.of(directory.resolve("screenshots").resolve("visual-qa.json"),
                    directory.resolve("visual-qa.json"))) {
                try {
                    if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS) || Files.size(report) > 8L * 1024 * 1024) {
                        continue;
                    }
                    Object parsed = Json.parse(Files.readString(report));
                    Object body = parsed instanceof Map<?, ?> map && map.get("adapter") instanceof Map<?, ?> adapter
                            ? adapter : parsed;
                    if (!(body instanceof Map<?, ?> map) || !(map.get("scenarios") instanceof List<?> list)) continue;
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> scenario) scenarios.add(cast(scenario));
                    }
                    break;
                } catch (Exception unreadable) {
                    // A report that cannot be read captions nothing; the pictures still show.
                }
            }
        }
        return scenarios;
    }

    private static List<Map<String, Object>> steps(Map<String, Object> scenario) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (scenario.get("steps") instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> step) steps.add(cast(step));
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String viewport(Map<String, Object> scenario) {
        if (scenario.get("viewport") instanceof Map<?, ?> view && view.get("width") instanceof Number width
                && view.get("height") instanceof Number height) {
            return width.longValue() + "×" + height.longValue()
                    + (Boolean.TRUE.equals(view.get("mobile")) ? " mobile" : "");
        }
        String raw = String.valueOf(scenario.get("raw"));
        int colon = raw.indexOf(':');
        return colon > 0 ? raw.substring(0, colon) : raw;
    }

    /**
     * The element a step names, by its test id where it has one:
     * `css=[data-testid=lamp-green][data-lit=true]` reads as `lamp-green (data-lit=true)`.
     * A selector that says more than which element and which attributes stays as written.
     */
    private static String target(Map<String, Object> step) {
        String matcher = String.valueOf(step.get("matcher"));
        if (matcher.startsWith("testid=")) return matcher.substring("testid=".length());
        if (matcher.startsWith("text=")) return "«" + matcher.substring("text=".length()) + "»";
        if (!matcher.startsWith("css=")) return matcher;
        Matcher simple = SIMPLE_SELECTOR.matcher(matcher.substring("css=".length()));
        if (!simple.matches()) return matcher;
        String element = simple.group(1) != null ? simple.group(1) : "." + simple.group(2);
        String filters = simple.group(3);
        if (filters.isEmpty()) return element;
        return element + " (" + filters.substring(1, filters.length() - 1).replace("][", ", ") + ")";
    }

    /** `[data-testid=x]` or `.class`, then attribute filters and nothing else. */
    private static final Pattern SIMPLE_SELECTOR = Pattern.compile(
            "(?:\\[data-testid=['\"]?([\\w-]+)['\"]?]|\\.([\\w-]+))((?:\\[[^\\]\\[]+])*)");

    private static String stepText(Map<String, Object> step) {
        String assertion = String.valueOf(step.get("assertion"));
        String text = switch (assertion) {
            case "click" -> "{{t:decision.click_on}} " + target(step);
            case "visible" -> target(step) + " {{t:decision.visible}}";
            case "hidden" -> target(step) + " {{t:decision.hidden}}";
            default -> target(step) + " " + assertion;
        };
        return text + (Boolean.TRUE.equals(step.get("ok")) ? " ✓" : Boolean.FALSE.equals(step.get("ok")) ? " ✗" : "");
    }

    /** A picture the report names, when it is one of this run's own. */
    private static Path inRun(Object named, List<Path> files) {
        if (!(named instanceof String text) || text.isBlank()) return null;
        String wanted = text.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (Path file : files) {
            String candidate = file.toAbsolutePath().normalize().toString().replace('\\', '/')
                    .toLowerCase(Locale.ROOT);
            if (candidate.equals(wanted)) return file;
            // The report may have been written from another spelling of the same worktree.
            String tail = "/" + file.getParent().getParent().getFileName() + "/screenshots/" + file.getFileName();
            if (wanted.endsWith(tail.toLowerCase(Locale.ROOT))) return file;
        }
        return null;
    }

    /**
     * This run's evidence directories, keeping only the newest attempt of each stage.
     *
     * A stage writes `<run>--<stage>-<attempt>` once per fix round. Every attempt but the last
     * judged a candidate a repair has since replaced, and listing them all put the failed
     * frames of the first browser pass beside the passing verdict of the last one — or, with
     * eight frames from the first, left the final ones off the page altogether.
     */
    private List<Path> runDirectories() {
        Path runs = root.resolve(".oseer/runs");
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> all;
        try (var listing = Files.list(runs)) {
            all = listing.filter(dir -> {
                String name = dir.getFileName().toString();
                return (name.equals(runId) || name.startsWith(runId + "--"))
                        && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
            }).sorted().toList();
        } catch (IOException unreadable) {
            return List.of();
        }
        Map<String, Path> newest = new LinkedHashMap<>();
        Map<String, Long> newestAttempt = new LinkedHashMap<>();
        for (Path dir : all) {
            String name = dir.getFileName().toString();
            String stage = name;
            long attempt = 0;
            if (name.startsWith(runId + "--")) {
                String tail = name.substring(runId.length() + 2);
                int dash = tail.lastIndexOf('-');
                if (dash > 0 && tail.substring(dash + 1).matches("\\d{1,9}")) {
                    stage = tail.substring(0, dash);
                    attempt = Long.parseLong(tail.substring(dash + 1));
                }
            }
            Long seen = newestAttempt.get(stage);
            if (seen == null || attempt > seen) {
                newestAttempt.put(stage, attempt);
                newest.put(stage, dir);
            }
        }
        return newest.values().stream().sorted().toList();
    }

    /** Every PNG in this run's evidence, in a stable order. */
    private List<Path> pictureFiles() {
        List<Path> files = new ArrayList<>();
        try {
            Path runs = root.resolve(".oseer/runs");
            if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) return files;
            Path runsRoot = runs.toRealPath();
            for (Path directory : runDirectories()) {
                Path pictures = directory.resolve("screenshots");
                if (!Files.isDirectory(pictures, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var listing = Files.list(pictures)) {
                    for (Path file : listing.sorted().toList()) {
                        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) continue;
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (!file.toRealPath().startsWith(runsRoot)) continue;
                        if (Files.size(file) > 16L * 1024 * 1024) continue;
                        files.add(file);
                    }
                }
            }
        } catch (IOException unreadable) {
            // Fewer pictures, not a broken page.
        }
        return files;
    }
}
