package dev.oseer;

import dev.oseer.dashboard.Messages;
import dev.oseer.testing.Check;
import dev.oseer.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Both languages say everything, nothing in a page is left untranslated, and every value is safe to render anywhere. */
public final class MessagesTest implements Suite {
    public String name() { return "messages"; }

    public void run(Check check) throws Exception {
        Set<String> en = new TreeSet<>(Messages.bundle("en").keySet());
        Set<String> ru = new TreeSet<>(Messages.bundle("ru").keySet());
        Set<String> onlyEn = new TreeSet<>(en); onlyEn.removeAll(ru);
        Set<String> onlyRu = new TreeSet<>(ru); onlyRu.removeAll(en);
        check.eq("every English key has a Russian value", Set.of(), onlyEn);
        check.eq("every Russian key has an English value", Set.of(), onlyRu);
        check.that("the bundles are not empty", en.size() > 500);

        List<String> unsafe = new ArrayList<>();
        Pattern cyrillic = Pattern.compile("\\p{IsCyrillic}");
        List<String> russianInEnglish = new ArrayList<>();
        for (String language : Messages.LANGUAGES) {
            Messages.bundle(language).forEach((key, value) -> {
                String problem = Messages.unsafe(value);
                if (problem != null) unsafe.add(language + ":" + key + " " + problem);
                if (value.isBlank()) unsafe.add(language + ":" + key + " is blank");
            });
        }
        Messages.bundle("en").forEach((key, value) -> {
            if (cyrillic.matcher(value).find() && !key.equals("lang.ru")) russianInEnglish.add(key);
        });
        check.eq("every value renders safely in HTML, attributes, JS and JSON", List.of(), unsafe);
        check.eq("the English bundle has no Russian left in it", List.of(), russianInEnglish);

        // Every marker the sources use names a key, and no page source still carries Russian
        // outside comments: a word written straight into a page would show in both languages.
        Pattern marker = Pattern.compile("\\{\\{t:([a-z0-9_.-]+)}}");
        Set<String> unknown = new TreeSet<>(), used = new TreeSet<>();
        List<String> leftovers = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java/dev/oseer"))) {
            sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java")).toList());
        }
        sources.add(Path.of("src/main/resources/dev/oseer/dashboard/dashboard.html"));
        Set<String> pageSources = Set.of("ConnectionSettingsUi.java", "Dashboard.java", "DashboardControls.java",
                "DashboardShell.java", "DecisionPage.java", "ModelSettingsUi.java", "YamlSettingsEdit.java",
                "VendorSettingsService.java", "CandidatePreview.java", "RunLauncherUi.java", "RunLauncher.java",
                "BrowserEvidence.java", "dashboard.html");
        for (Path source : sources) {
            if (source.getFileName().toString().equals("Messages.java")) continue;
            String text = Files.readString(source);
            Matcher matcher = marker.matcher(text);
            while (matcher.find()) { used.add(matcher.group(1)); if (!en.contains(matcher.group(1))) unknown.add(matcher.group(1)); }
            if (!pageSources.contains(source.getFileName().toString())) continue;
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
                if (cyrillic.matcher(line).find()) leftovers.add(source.getFileName() + ": " + trimmed.substring(0, Math.min(80, trimmed.length())));
            }
        }
        check.eq("every marker in the sources names a key", Set.of(), unknown);
        check.eq("no page source carries Russian outside the bundles", List.of(), leftovers);

        check.eq("render replaces a marker", "en", Messages.render("{{t:meta.html_lang}}", "en"));
        check.eq("render in Russian", "ru", Messages.render("{{t:meta.html_lang}}", "ru"));
        check.eq("an unknown key stays visible", "missing.key", Messages.t("en", "missing.key"));
        check.eq("text without markers is untouched", "plain {{ text }}", Messages.render("plain {{ text }}", "ru"));
        check.eq("the cookie wins", "ru", Messages.language("a=b; oseer_lang=ru", "en-US,en"));
        check.eq("an unknown cookie value is ignored", "en", Messages.language("oseer_lang=xx", null));
        check.eq("the browser language decides without a cookie", "ru", Messages.language(null, "ru-RU,ru;q=0.9,en;q=0.8"));
        check.eq("a browser language without a bundle falls back to the next one", "ru", Messages.language(null, "de-DE,ru;q=0.5"));
        check.eq("English is the default", "en", Messages.language(null, "de"));
    }
}
