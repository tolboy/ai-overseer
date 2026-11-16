package dev.oseer.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The browser pages' words, in one bundle per language ({@link MessagesEn}, {@link MessagesRu}).
 *
 * Page templates are written with {@code {{t:key}}} markers and rendered for the
 * reader's language just before they are sent, so one template serves every language and a
 * missing translation is a test failure rather than a mixed page. A bundle value never
 * contains a quote, a backslash, an angle bracket or a brace (see {@link #unsafe}), which is
 * what lets one marker sit unescaped in HTML text, an attribute, a JS or a JSON string.
 *
 * The language is the {@code oseer_lang} cookie the page's switcher sets, else the first
 * language the browser asks for that a bundle exists for, else English.
 */
public final class Messages {
    public static final List<String> LANGUAGES = List.of("en", "ru");
    public static final String DEFAULT = "en";
    public static final String COOKIE = "oseer_lang";
    private static final Pattern MARKER = Pattern.compile("\\{\\{t:([a-z0-9_.-]+)}}");
    private static final Map<String, Map<String, String>> BUNDLES = Map.of(
            "en", parse(MessagesEn.TEXT, "en"), "ru", parse(MessagesRu.TEXT, "ru"));

    private Messages() {}

    public static Map<String, String> bundle(String language) {
        return BUNDLES.getOrDefault(language, BUNDLES.get(DEFAULT));
    }

    /** One message; a key no bundle knows is returned as itself, so it is visible, not blank. */
    public static String t(String language, String key) {
        String value = bundle(language).get(key);
        if (value == null) value = BUNDLES.get(DEFAULT).get(key);
        return value == null ? key : value;
    }

    /** Replaces every marker in {@code text}; text without markers is returned unchanged. */
    public static String render(String text, String language) {
        if (text == null || !text.contains("{{t:")) return text;
        Matcher matcher = MARKER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(t(language, matcher.group(1))));
        matcher.appendTail(out);
        return out.toString();
    }

    /** The reader's language from the cookie header and Accept-Language; never null. */
    public static String language(String cookieHeader, String acceptLanguage) {
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && COOKIE.equals(pair[0]) && LANGUAGES.contains(pair[1].trim())) return pair[1].trim();
            }
        }
        if (acceptLanguage != null) {
            for (String range : acceptLanguage.split(",")) {
                String tag = range.split(";")[0].trim().toLowerCase(Locale.ROOT);
                String primary = tag.split("-")[0];
                if (LANGUAGES.contains(primary)) return primary;
            }
        }
        return DEFAULT;
    }

    public static String language(com.sun.net.httpserver.HttpExchange exchange) {
        return language(exchange.getRequestHeaders().getFirst("Cookie"),
                exchange.getRequestHeaders().getFirst("Accept-Language"));
    }

    /** Why a bundle value could break the page it is rendered into, or null. */
    public static String unsafe(String value) {
        for (char c : new char[] {'\'', '"', '`', '\\', '<', '>', '{', '}', '\n', '\r'}) {
            if (value.indexOf(c) >= 0) return "contains " + (c == '\n' ? "a newline" : "'" + c + "'");
        }
        return null;
    }

    static Map<String, String> parse(String text, String language) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int at = line.indexOf(" = ");
            if (at < 0) throw new IllegalStateException(language + " bundle: not 'key = value': " + line);
            String key = line.substring(0, at).trim(), value = line.substring(at + 3);
            if (!key.matches("[a-z0-9_.-]+")) throw new IllegalStateException(language + " bundle: bad key " + key);
            if (values.put(key, value) != null) throw new IllegalStateException(language + " bundle: duplicate key " + key);
        }
        return Map.copyOf(values);
    }
}
