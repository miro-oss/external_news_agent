package com.example.be.domain.collection.content;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Reads the current, explicitly free article's paragraph data from its server-rendered page. */
final class PublicArticleHydration {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DATA_CHARACTERS = 512 * 1024;
    private static final List<String> RESTRICTED_FLAGS = List.of(
            "isPremium", "isPaid", "requiresSubscription", "requiresLogin", "paywall");

    private PublicArticleHydration() {
    }

    static Element articleBody(Document document) {
        // This is one observed page schema, not a search through arbitrary JSON strings.
        var scripts = document.select("script#__NEXT_DATA__[type=application/json]");
        if (scripts.size() != 1 || document.select(
                ".paywall, [data-paywall], .subscription-required, .login-required").size() > 0) {
            return null;
        }
        JsonNode data = parse(scripts.first().data());
        if (data == null) {
            return null;
        }
        JsonNode page = data.path("props").path("pageProps");
        JsonNode article = page.path("articleView");
        String id = identifier(article.path("id"));
        String title = string(article.path("title"));
        URI requested = articleUri(document.baseUri(), document.baseUri());
        URI canonical = articleUri(document.select("link[rel=canonical]").attr("href"), document.baseUri());
        URI articleUrl = articleUri(string(article.path("canonical_url")), document.baseUri());
        if (id.isBlank() || title.isBlank() || !id.equals(identifier(page.path("id")))
                || !sameUrl(requested, canonical) || !sameUrl(canonical, articleUrl)
                || !articleUrl.getPath().endsWith("/" + id)
                || restricted(page) || restricted(article)) {
            return null;
        }
        JsonNode queryId = data.path("query").path("id");
        if (!queryId.isMissingNode() && !id.equals(identifier(queryId))) {
            return null;
        }
        boolean visibleTitle = document.select("h1, [itemprop=headline]").eachText().stream()
                .anyMatch(value -> sameTitle(title, value))
                || sameTitle(title, document.select("meta[property=og:title]").attr("content"));
        if (!visibleTitle || !explicitlyFreeArticle(document, canonical, title)) {
            return null;
        }

        JsonNode paragraphs = article.path("contentArrange");
        if (!paragraphs.isArray()) {
            return null;
        }
        Element body = new Element("div");
        for (JsonNode paragraph : paragraphs) {
            // Images, captions, ads, recommendations and arbitrary metadata are never body text.
            if ("text".equals(string(paragraph.path("type")))) {
                String content = string(paragraph.path("content"));
                if (!content.isBlank()) {
                    body.appendElement("p").html(content);
                }
            }
        }
        return body;
    }

    private static boolean explicitlyFreeArticle(Document document, URI canonical, String title) {
        boolean found = false;
        for (Element script : document.select("script[type=application/ld+json]")) {
            JsonNode article = parse(script.data());
            if (article == null || !"NewsArticle".equals(string(article.path("@type")))
                    || !sameUrl(canonical, articleUri(string(article.path("url")), document.baseUri()))
                    || !sameTitle(title, string(article.path("headline")))) {
                continue;
            }
            JsonNode free = article.path("isAccessibleForFree");
            // Missing/unknown access status and paid portions do not authorize this fallback.
            if (!free.isBoolean() || !free.booleanValue() || restricted(article)
                    || restrictedParts(article.path("hasPart"))) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static boolean restrictedParts(JsonNode parts) {
        if (parts.isObject()) {
            return restricted(parts) || restrictedParts(parts.path("hasPart"));
        }
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (restrictedParts(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean restricted(JsonNode object) {
        JsonNode free = object.path("isAccessibleForFree");
        if (!free.isMissingNode() && (!free.isBoolean() || !free.booleanValue())) {
            return true;
        }
        return RESTRICTED_FLAGS.stream().map(object::path)
                .anyMatch(value -> !value.isMissingNode() && !value.isNull()
                        && !(value.isBoolean() && !value.booleanValue()));
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_DATA_CHARACTERS) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String string(JsonNode value) {
        return value.isString() ? value.asString() : "";
    }

    private static String identifier(JsonNode value) {
        return value.isIntegralNumber() ? value.toString() : string(value);
    }

    private static boolean sameTitle(String left, String right) {
        return !right.isBlank() && normalizeTitle(left).equals(normalizeTitle(right));
    }

    private static String normalizeTitle(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static URI articleUri(String value, String baseUrl) {
        if (value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(baseUrl).resolve(value).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
            return URI.create(uri.toString().split("#", 2)[0]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean sameUrl(URI left, URI right) {
        return left != null && left.equals(right);
    }
}
