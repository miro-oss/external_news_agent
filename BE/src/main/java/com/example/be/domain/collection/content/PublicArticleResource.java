package com.example.be.domain.collection.content;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identifies one observed, public article-body resource without evaluating page scripts. */
final class PublicArticleResource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ARTICLE_QUERY = Pattern.compile("idx=([1-9][0-9]{3,11})");
    private static final Pattern RESOURCE_PATH = Pattern.compile("/data/newsText/news/([0-9]+)/([1-9][0-9]{3,11})\\.json");
    private static final Pattern VIEW_CONTENT = Pattern.compile(
            "function\\s+viewContent\\s*\\(\\s*\\)\\s*\\{\\s*\\$\\.ajax\\s*\\(\\s*\\{(.*?)\\}\\s*\\);\\s*\\}",
            Pattern.DOTALL);
    private static final Pattern RESOURCE_URL = Pattern.compile("\\burl\\s*:\\s*(['\"])(https://[^'\"\\s]+)\\1");
    private static final Pattern GET = Pattern.compile("\\btype\\s*:\\s*(['\"])get\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_TYPE = Pattern.compile("\\bdataType\\s*:\\s*(['\"])json\\1");
    private static final Pattern BODY_RENDERER = Pattern.compile(
            "\\$\\(\\s*(['\"])#view_content_body\\1\\s*\\)\\.html\\(\\s*data\\.view_content_body\\s*\\)");
    private static final List<String> RESTRICTED_FLAGS = List.of(
            "isPremium", "isPaid", "requiresSubscription", "requiresLogin", "paywall");

    private PublicArticleResource() {
    }

    static Resource find(byte[] html, String charsetName, String articleUrl) {
        try {
            Document page = Jsoup.parse(new ByteArrayInputStream(html), charsetName, articleUrl);
            URI article = uri(articleUrl);
            var canonicals = page.select("link[rel=canonical]");
            if (article == null || !"/news/view.php".equals(article.getPath()) || canonicals.size() != 1
                    || !article.equals(uri(canonicals.first().attr("abs:href")))
                    || page.select("[itemprop=articleBody] #view_content_body").size() != 1
                    || !page.select("[itemprop=articleBody] #view_content_body").text().isBlank()
                    || !page.select(".paywall, [data-paywall], .subscription-required, .login-required").isEmpty()) {
                return null;
            }
            var idMatch = ARTICLE_QUERY.matcher(article.getRawQuery() == null ? "" : article.getRawQuery());
            if (!idMatch.matches()) {
                return null;
            }
            String id = idMatch.group(1);
            String title = freeArticleTitle(page, article);
            if (title == null) {
                return null;
            }
            URI candidate = null;
            for (Element script : page.select("script:not([src])")) {
                String source = script.data();
                if (!BODY_RENDERER.matcher(source).find()) {
                    continue;
                }
                var function = VIEW_CONTENT.matcher(source);
                while (function.find()) {
                    String options = function.group(1);
                    if (!GET.matcher(options).find() || !JSON_TYPE.matcher(options).find()
                            || !options.contains("showContent(data)")) {
                        return null;
                    }
                    var urlMatch = RESOURCE_URL.matcher(options);
                    if (!urlMatch.find()) {
                        return null;
                    }
                    URI resource = uri(urlMatch.group(2));
                    if (urlMatch.find() || resource == null || !sameOrigin(article, resource)
                            || resource.getRawQuery() != null) {
                        return null;
                    }
                    var path = RESOURCE_PATH.matcher(resource.getPath());
                    if (!path.matches() || !id.equals(path.group(2))
                            || !id.substring(0, id.length() - 3).equals(path.group(1)) || candidate != null) {
                        return null;
                    }
                    candidate = resource;
                }
            }
            return candidate == null ? null : new Resource(candidate, article, id, title);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    static String extract(byte[] json, Resource resource) {
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isObject() || !resource.articleId().equals(string(root.path("idx")))
                    || !sameTitle(resource.title(), string(root.path("main_subject")))
                    || !"0".equals(string(root.path("read_level"))) || restricted(root)) {
                return null;
            }
            String body = string(root.path("view_content_body"));
            if (body.isBlank()) {
                return null;
            }
            // Only the field rendered into the current article's body is eligible.
            // The normal extractor still rejects captions, access notices and short placeholders.
            Document page = Document.createShell(resource.articleUri().toString());
            page.body().appendElement("div").attr("itemprop", "articleBody").html(body);
            return ArticleContentExtractor.extract(page.outerHtml().getBytes(StandardCharsets.UTF_8),
                    "UTF-8", resource.articleUri().toString(), resource.title());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String freeArticleTitle(Document page, URI article) {
        String title = null;
        for (Element script : page.select("script[type=application/ld+json]")) {
            JsonNode node;
            try {
                node = JSON.readTree(script.data());
            } catch (RuntimeException exception) {
                continue;
            }
            if (!"NewsArticle".equals(string(node.path("@type")))
                    || !article.equals(uri(string(node.path("url"))))) {
                continue;
            }
            JsonNode free = node.path("isAccessibleForFree");
            String headline = string(node.path("headline"));
            if (!free.isBoolean() || !free.booleanValue() || restricted(node)
                    || restrictedParts(node.path("hasPart"))
                    || !article.equals(uri(string(node.path("mainEntityOfPage").path("@id"))))
                    || !sameTitle(headline, page.select("meta[property=og:title]").attr("content"))) {
                return null;
            }
            if (title != null && !sameTitle(title, headline)) {
                return null;
            }
            title = headline;
        }
        return title;
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
        return RESTRICTED_FLAGS.stream().map(object::path).anyMatch(value -> !value.isMissingNode()
                && !value.isNull() && !(value.isBoolean() && !value.booleanValue()));
    }

    private static String string(JsonNode value) {
        return value.isString() ? value.asString() : "";
    }

    private static boolean sameTitle(String left, String right) {
        return !left.isBlank() && normalize(left).equals(normalize(right));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static URI uri(String text) {
        try {
            URI value = URI.create(text);
            return "https".equals(value.getScheme()) && value.getHost() != null
                    && value.getRawUserInfo() == null && value.getFragment() == null
                    && value.getPort() <= 65535 && value.equals(value.normalize()) ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equals(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    record Resource(URI uri, URI articleUri, String articleId, String title) {
    }
}
