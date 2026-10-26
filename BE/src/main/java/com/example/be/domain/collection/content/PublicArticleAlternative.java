package com.example.be.domain.collection.content;

import com.example.be.global.config.PublicDestinationPolicy;
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

/** Reads a single publisher-linked AMP rendition of the same visible, public article. */
final class PublicArticleAlternative {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ARTICLE_PATH = Pattern.compile("/article/([1-9][0-9]{5,19})");
    private static final String BODY_SELECTOR = ".article_content_end_middle .acem_text";
    private static final String ACCESS_SELECTOR = ".paywall, [data-paywall], .subscription-required, .login-required, "
            + "[amp-access], [amp-access-hide], [subscriptions-section], [subscriptions-actions]";
    private static final List<String> RESTRICTED_FLAGS = List.of(
            "isPremium", "isPaid", "requiresSubscription", "requiresLogin", "paywall");
    private static final Pattern ACCESS_NOTICE = Pattern.compile(
            "(?:로그인|회원\\s*가입|구독)(?:을|를)?\\s*(?:후|하셔야|해야|하면|하시면|이\\s*필요)[^.!?。]{0,100}(?:기사|내용|전문|읽|볼|보실)"
                    + "|(?:이\\s*)?(?:기사|콘텐츠)(?:는|은)\\s*(?:유료|회원\\s*전용|구독자\\s*전용)"
                    + "|(?:기사|콘텐츠|내용)(?:는|은)[^.!?。]{0,60}(?:회원|구독자)[^.!?。]{0,40}(?:전용|제공)"
                    + "|(?:유료\\s*)?(?:회원|구독자)\\s*전용"
                    + "|(?:기사|전문|콘텐츠|계속)[^.!?。]{0,40}(?:읽으(?:시)?려면|보(?:시)?려면|이용(?:하시)?려면)[^.!?。]{0,60}(?:로그인|구독|회원\\s*가입)"
                    + "|(?:기사\\s*전문|전체\\s*기사|전문)(?:을|은|를|는)?\\s*(?:로그인|구독|회원\\s*가입)"
                    + "|(?:log\\s*in|sign\\s*in|subscribe|register)\\b[^.!?。]{0,100}(?:read|access|continue|unlock|full\\s+article)"
                    + "|you\\s+(?:must|need\\s+to)\\s+(?:log\\s*in|sign\\s*in|subscribe|register)"
                    + "|(?:subscription|membership)\\s+is\\s+required\\s+to\\s+(?:read|view|access)"
                    + "|(?:article|content)\\s+is\\s+(?:only\\s+)?(?:for|available\\s+to)\\s+(?:registered\\s+)?(?:members|subscribers)"
                    + "|(?:article|content)\\s+requires\\s+(?:a\\s+)?(?:subscription|membership)"
                    + "|to\\s+(?:continue|read|view|access|unlock)[^.!?。]{0,100}(?:log\\s*in|sign\\s*in|subscribe|register)"
                    + "|already\\s+(?:a\\s+)?(?:subscriber|member)\\??\\s*(?:log\\s*in|sign\\s*in)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HIDDEN_STYLE = Pattern.compile(
            "(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden|content-visibility\\s*:\\s*hidden|opacity\\s*:\\s*0(?:[;\\s]|$))",
            Pattern.CASE_INSENSITIVE);

    private PublicArticleAlternative() {
    }

    static Candidate find(byte[] html, String charsetName, String currentUrl) {
        try {
            URI current = uri(currentUrl);
            if (current == null || html == null || html.length == 0) {
                return null;
            }
            Document page = Jsoup.parse(new ByteArrayInputStream(html), charsetName, currentUrl);
            URI canonical = canonical(page, current);
            var links = page.select("link[rel=amphtml]");
            if (canonical == null || !sameOrigin(current, canonical) || !current.getPath().equals(canonical.getPath())
                    || links.size() != 1 || !page.select("base[href]").isEmpty() || restricted(page)) {
                return null;
            }
            URI alternative = linkUri(links.first().attr("href"), current);
            if (!validPair(canonical, alternative)) {
                return null;
            }
            String title = articleTitle(page, canonical);
            var titles = page.select("meta[property=og:title]");
            if (title == null || titles.size() != 1 || !sameTitle(title, titles.first().attr("content"))
                    || page.select("meta[property=og:type][content=article]").size() != 1) {
                return null;
            }
            return new Candidate(alternative, canonical, title);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    static String extract(byte[] html, String charsetName, Candidate candidate) {
        try {
            if (candidate == null || !validPair(candidate.canonicalUri(), candidate.uri())
                    || html == null || html.length == 0) {
                return null;
            }
            Document page = Jsoup.parse(new ByteArrayInputStream(html), charsetName, candidate.uri().toString());
            var titles = page.select(".article_content_end_top .titleline_title_end");
            var bodies = page.select(BODY_SELECTOR);
            if (!candidate.canonicalUri().equals(canonical(page, candidate.uri()))
                    || !page.select("base[href]").isEmpty() || restricted(page)
                    || !sameTitle(candidate.title(), articleTitle(page, candidate.canonicalUri()))
                    || titles.size() != 1 || !sameTitle(candidate.title(), titles.first().text())
                    || hidden(titles.first()) || bodies.size() != 1 || hidden(bodies.first())) {
                return null;
            }
            // Feed only the verified visible body to the normal extractor. Scripts/metadata and related
            // articles elsewhere in AMP must not provide hydration data or a density fallback.
            Element body = bodies.first().clone();
            body.select("script, style, noscript, [hidden], [aria-hidden=true], [inert]").remove();
            body.select("[style]").stream().filter(element -> HIDDEN_STYLE.matcher(element.attr("style")).find())
                    .toList().forEach(Element::remove);
            Document visible = Document.createShell(candidate.canonicalUri().toString());
            visible.body().appendChild(body.attr("itemprop", "articleBody"));
            return ArticleContentExtractor.extract(visible.outerHtml().getBytes(StandardCharsets.UTF_8),
                    "UTF-8", candidate.canonicalUri().toString(), candidate.title());
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String articleTitle(Document page, URI canonical) {
        var id = ARTICLE_PATH.matcher(canonical.getPath());
        if (!id.matches()) {
            return null;
        }
        String title = null;
        int articles = 0;
        for (Element script : page.select("script[type=application/ld+json]")) {
            if (script.data().length() > 512 * 1024) {
                return null;
            }
            JsonNode node = JSON.readTree(script.data());
            if (!"NewsArticle".equals(string(node.path("@type")))) {
                continue;
            }
            if (++articles != 1 || restricted(node) || !id.group(1).equals(string(node.path("@id")))
                    || !canonical.equals(uri(string(node.path("mainEntityOfPage").path("@id"))))) {
                return null;
            }
            title = string(node.path("headline"));
        }
        return articles == 1 && title != null && !title.isBlank() ? title : null;
    }

    private static boolean restricted(Document page) {
        return !page.select(ACCESS_SELECTOR).isEmpty() || ACCESS_NOTICE.matcher(page.body().text()).find();
    }

    private static boolean restricted(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (restricted(child)) {
                    return true;
                }
            }
        } else if (node.isObject()) {
            JsonNode free = node.path("isAccessibleForFree");
            if ((!free.isMissingNode() && (!free.isBoolean() || !free.booleanValue()))
                    || RESTRICTED_FLAGS.stream().map(node::path).anyMatch(value -> !value.isMissingNode()
                    && !value.isNull() && !(value.isBoolean() && !value.booleanValue()))) {
                return true;
            }
            return restricted(node.path("hasPart"));
        }
        return false;
    }

    private static boolean hidden(Element element) {
        for (Element current = element; current != null; current = current.parent()) {
            if (current.hasAttr("hidden") || "true".equalsIgnoreCase(current.attr("aria-hidden"))
                    || current.hasAttr("inert") || HIDDEN_STYLE.matcher(current.attr("style")).find()) {
                return true;
            }
        }
        return false;
    }

    private static URI canonical(Document page, URI current) {
        var links = page.select("link[rel=canonical]");
        URI value = links.size() == 1 ? linkUri(links.first().attr("href"), current) : null;
        return value != null && value.getRawQuery() == null && ARTICLE_PATH.matcher(value.getPath()).matches()
                ? value : null;
    }

    private static URI linkUri(String href, URI current) {
        try {
            // Jsoup's abs:href can discard user-info. Validate the raw URI before resolving it.
            URI raw = URI.create(href);
            if (href.isBlank() || raw.getRawUserInfo() != null || !raw.equals(raw.normalize())) {
                return null;
            }
            return uri(current.resolve(raw).toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean validPair(URI canonical, URI alternative) {
        return canonical != null && alternative != null && canonical.equals(uri(canonical.toString()))
                && alternative.equals(uri(alternative.toString())) && sameOrigin(canonical, alternative)
                && canonical.getRawQuery() == null && alternative.getRawQuery() == null
                && ARTICLE_PATH.matcher(canonical.getPath()).matches()
                && alternative.getPath().equals("/amp" + canonical.getPath());
    }

    private static URI uri(String value) {
        try {
            URI uri = URI.create(value);
            PublicDestinationPolicy.validate(uri);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawFragment() == null
                    && uri.equals(uri.normalize()) ? uri : null;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                && (left.getPort() == -1 ? 443 : left.getPort()) == (right.getPort() == -1 ? 443 : right.getPort());
    }

    private static String string(JsonNode value) {
        return value.isString() ? value.asString() : "";
    }

    private static boolean sameTitle(String left, String right) {
        return left != null && right != null && !left.isBlank() && normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    record Candidate(URI uri, URI canonicalUri, String title) {
    }
}
