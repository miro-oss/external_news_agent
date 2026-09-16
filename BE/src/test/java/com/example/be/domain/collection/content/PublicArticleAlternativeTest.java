package com.example.be.domain.collection.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PublicArticleAlternativeTest {
    static final String ARTICLE = "https://publisher.example/article/123456";
    static final String ALTERNATIVE = "https://publisher.example/amp/article/123456";
    static final String TITLE = "지역 제조기업 공동 연구 협약 체결";
    static final String BODY = PublicArticleResourceTest.BODY;

    static String page() {
        return """
                <html><head><link rel="canonical" href="%s"><link rel="amphtml" href="%s">
                <meta property="og:title" content="%s"><meta property="og:type" content="article">
                %s</head><body><div id="root"></div></body></html>
                """.formatted(ARTICLE, ALTERNATIVE, TITLE, schema());
    }

    static String ampPage() {
        return """
                <html amp><head><link rel="canonical" href="%s">%s</head><body>
                <main><div class="article_content_end_top"><h2 class="titleline_title_end">%s</h2></div>
                <div class="article_content_end_middle"><div class="acem_text"><p>%s</p></div></div>
                <div class="related"><p>다른 기사와 관련된 내용이다. 이 문장은 전문에 포함되면 안 된다.</p></div>
                </main></body></html>
                """.formatted(ARTICLE, schema(), TITLE, BODY);
    }

    private static String schema() {
        return """
                <script type="application/ld+json">{"@type":"NewsArticle","@id":"123456",
                "headline":"%s","mainEntityOfPage":{"@id":"%s"}}</script>
                """.formatted(TITLE, ARTICLE);
    }

    private PublicArticleAlternative.Candidate find(String html) {
        return PublicArticleAlternative.find(html.getBytes(StandardCharsets.UTF_8), "UTF-8", ARTICLE + "?division=NAVER");
    }

    private String extract(String html) {
        return PublicArticleAlternative.extract(html.getBytes(StandardCharsets.UTF_8), "UTF-8", find(page()));
    }

    @Test
    void usesOnlyTheExplicitSameArticleAmpLinkAndVisibleBody() {
        var candidate = find(page());
        assertNotNull(candidate);
        assertEquals(ALTERNATIVE, candidate.uri().toString());
        assertEquals(ARTICLE, candidate.canonicalUri().toString());
        assertEquals(TITLE, candidate.title());
        String body = extract(ampPage());
        assertNotNull(body);
        assertTrue(body.contains("후속 회의"));
        assertFalse(body.contains("다른 기사"));
        assertFalse(body.contains(TITLE));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://publisher.example/amp/article/123456|https://other.example/amp/article/123456",
            "https://publisher.example/amp/article/123456|http://publisher.example/amp/article/123456",
            "https://publisher.example/amp/article/123456|https://publisher.example:8443/amp/article/123456",
            "https://publisher.example/amp/article/123456|https://publisher.example/amp/article/654321",
            "https://publisher.example/amp/article/123456|https://publisher.example/amp/article/123456?mode=full",
            "https://publisher.example/amp/article/123456|https://publisher.example/amp/article/123456#body",
            "https://publisher.example/amp/article/123456|https://publisher.example/amp/../amp/article/123456",
            "https://publisher.example/amp/article/123456|https://user@publisher.example/amp/article/123456",
            "https://publisher.example/amp/article/123456|//user@publisher.example/amp/article/123456",
            "https://publisher.example/amp/article/123456|https://127.0.0.1/amp/article/123456",
            "rel=\"amphtml\"|rel=\"alternate\"",
            "content=\"article\"|content=\"website\"",
            "\"@id\":\"123456\"|\"@id\":\"654321\""
    })
    void refusesUnprovenOrDifferentAlternatives(String before, String after) {
        assertNull(find(page().replace(before, after)));
    }

    @Test
    void neverGuessesMissingLinksOrAmbiguousCanonicalAndAmpLinks() {
        assertNull(find(page().replace("<link rel=\"amphtml\" href=\"" + ALTERNATIVE + "\">", "")));
        assertNull(find(page().replace("</head>", "<link rel='amphtml' href='" + ALTERNATIVE + "'></head>")));
        assertNull(find(page().replace("</head>", "<link rel='canonical' href='" + ARTICLE + "'></head>")));
        assertNull(PublicArticleAlternative.find(page().getBytes(StandardCharsets.UTF_8), "UTF-8",
                ARTICLE.replace("123456", "654321")));
        assertNull(find(page().replace("</head>", "<base href='https://other.example/'></head>")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user@publisher.example/article/123456", "//user@publisher.example/article/123456"})
    void rejectsUserInfoInCanonicalLinksBeforeHtmlResolutionCanDiscardIt(String url) {
        assertNull(find(page().replace("href=\"" + ARTICLE + "\"", "href=\"" + url + "\"")));
        assertNull(extract(ampPage().replace("href=\"" + ARTICLE + "\"", "href=\"" + url + "\"")));
    }

    @Test
    void resolvesUnambiguousRelativePublisherLinksAgainstTheRequestedPage() {
        assertNotNull(find(page().replace("href=\"" + ALTERNATIVE, "href=\"/amp/article/123456")));
        assertNotNull(extract(ampPage().replace("href=\"" + ARTICLE, "href=\"/article/123456")));
    }

    @Test
    void requiresCurrentPageTitleAndArticleSchemaToAgree() {
        assertNull(find(page().replace("content=\"" + TITLE, "content=\"다른 기사")));
        assertNull(find(page().replace(schema(), "")));
        assertNull(find(page().replace("</head>", schema() + "</head>")));
        assertNull(find(page().replace("\"mainEntityOfPage\":{\"@id\":\"" + ARTICLE,
                "\"mainEntityOfPage\":{\"@id\":\"https://other.example/wrong")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"isAccessibleForFree\":false", "\"isAccessibleForFree\":\"true\"",
            "\"isPaid\":true", "\"isPremium\":true", "\"requiresLogin\":true",
            "\"requiresSubscription\":true", "\"hasPart\":{\"isAccessibleForFree\":false}",
            "\"hasPart\":[{\"isAccessibleForFree\":false}]"})
    void rejectsAccessRestrictionsOnEitherPage(String restricted) {
        assertNull(find(page().replace("\"@type\":\"NewsArticle\"", "\"@type\":\"NewsArticle\"," + restricted)));
        assertNull(extract(ampPage().replace("\"@type\":\"NewsArticle\"", "\"@type\":\"NewsArticle\"," + restricted)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<div class='paywall'></div>", "<div data-paywall></div>",
            "<div class='login-required'></div>", "<div amp-access='subscriber'></div>",
            "<div subscriptions-section='content'></div>", "<p>구독 후 기사 전문을 읽으실 수 있습니다.</p>",
            "<p>로그인 후 기사 내용을 볼 수 있습니다.</p>", "<p>Subscribe to read the full article.</p>",
            "<p>로그인 <strong>후</strong> 기사 전문을 읽으실 수 있습니다.</p>",
            "<p>Subscribe <strong>to read</strong> the full article.</p>",
            "로그인 후 기사 전문을 읽으실 수 있습니다.",
            "<span>로그인 <strong>후</strong> 기사 전문을 읽으실 수 있습니다.</span>",
            "<p>회원 전용 콘텐츠입니다.</p>", "<p>구독자 전용 기사</p>",
            "<p>A subscription is required to read the article.</p>",
            "<p>This article is only for subscribers.</p>",
            "<p>이 기사는 등록된 회원에게만 제공됩니다.</p>", "<p>유료 구독자 전용입니다.</p>",
            "<p>이 기사를 읽으시려면 로그인하세요.</p>", "<p>기사 전문을 보려면 구독하세요.</p>",
            "<p>계속 읽으려면 회원 가입이 필요합니다.</p>", "<p>전체 기사는 로그인 후 제공됩니다.</p>",
            "<p>로그인하면 기사 전문을 읽을 수 있습니다.</p>", "<p>You must sign in before continuing.</p>",
            "<p>This content requires a subscription.</p>", "<p>To continue reading, please sign in.</p>",
            "<p>Already a subscriber? Log in.</p>"})
    void doesNotUseAmpToBypassAnAccessNotice(String notice) {
        assertNull(find(page().replace("<body>", "<body>" + notice)));
        assertNull(extract(ampPage().replace("<body>", "<body>" + notice)));
    }

    @Test
    void rejectsWrongAlternateCanonicalVisibleTitleAndIdentity() {
        assertNull(extract(ampPage().replace(ARTICLE, ARTICLE.replace("123456", "654321"))));
        assertNull(extract(ampPage().replace(">" + TITLE + "</h2>", ">다른 기사</h2>")));
        assertNull(extract(ampPage().replace("\"@id\":\"123456\"", "\"@id\":\"654321\"")));
        assertNull(extract(ampPage().replace("</head>", schema() + "</head>")));
        assertNull(extract(ampPage().replace("</main>", "<div class='article_content_end_middle'><div class='acem_text'>"
                + BODY + "</div></div></main>")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden", "aria-hidden='true'", "inert", "style='display: none'",
            "style='visibility:hidden'", "style='opacity:0'"})
    void rejectsHiddenBodyAndHiddenAncestors(String attribute) {
        assertNull(extract(ampPage().replace("class=\"acem_text\"", "class=\"acem_text\" " + attribute)));
        assertNull(extract(ampPage().replace("<main>", "<main " + attribute + ">")));
    }

    @Test
    void doesNotPromoteRelatedTextDescriptionsScriptsOrHiddenTextIntoBody() {
        assertNull(extract(ampPage().replace(BODY, "")));
        assertNull(extract(ampPage().replace(BODY, "<script>var hiddenBody = '" + BODY + "';</script>")));
        assertNull(extract(ampPage().replace(BODY, "<span hidden>" + BODY + "</span>")));
        assertNull(extract(ampPage().replace(BODY, "<span style='display:none'>" + BODY + "</span>")));
        assertNull(extract(ampPage().replace(BODY, "사진: 공장 전경")));
    }

    @Test
    void allowsReportingAboutSubscriptionsWithoutTreatingItAsAnAccessNotice() {
        assertNotNull(extract(ampPage().replace(BODY, BODY + " 메타는 새로운 구독 체계를 내놓았다고 밝혔다.")));
    }
}
