package com.example.be.domain.collection.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicArticleHydrationTest {

    private static final String URL = "https://publisher.example/economy/101";
    private static final String TITLE = "한국은행, 기준금리 동결";
    private static final String FIRST = "한국은행이 기준금리를 동결했다고 발표했다. "
            + "금융통화위원회는 가계부채와 물가 추이를 함께 고려했다고 설명했다. "
            + "이번 결정은 이날 열린 정례 회의에서 위원들의 논의를 거쳐 이뤄졌다.";
    private static final String SECOND = "위원회는 다음 회의까지 금융시장과 소비 지표를 살펴볼 방침이다. "
            + "한국은행 관계자는 향후 정책을 결정하기 전에 새로 발표되는 지표를 검토하겠다고 말했다. "
            + "시장 참가자들은 결정 내용을 확인하며 채권 거래를 이어갔다.";

    @Test
    void recoversOnlyCurrentPublicArticleTextAndPreservesParagraphOrder() {
        String body = extract(page());

        assertEquals(FIRST + "\n\n" + SECOND, body);
        assertFalse(body.contains("사진 설명"));
        assertFalse(body.contains("추천 기사"));
        assertFalse(body.contains("광고 문구"));
    }

    @Test
    void visibleBodyTakesPrecedenceOverHydration() {
        String html = page().replace("<p></p>", "<p>한국은행이 기준금리를 동결했다.</p>");

        assertEquals("한국은행이 기준금리를 동결했다.", extract(html));
    }

    @Test
    void numericArticleIdsCanMatchTheSamePageAndRouteStringIds() {
        assertEquals(FIRST + "\n\n" + SECOND,
                extract(page().replace("\"id\": \"101\",\"title\"", "\"id\": 101,\"title\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "null", "\"true\"", "{}"})
    void requiresAnExplicitBooleanFreeDeclaration(String value) {
        assertNull(extract(page().replace("\"isAccessibleForFree\": true",
                "\"isAccessibleForFree\": " + value)));
    }

    @Test
    void aMissingFreeDeclarationDoesNotAuthorizeHydration() {
        assertNull(extract(page().replace("\"isAccessibleForFree\": true,", "")));
    }

    @Test
    void rejectsPaidArticleSectionsEvenWhenTheArticleIsLabeledFree() {
        String html = page().replace("\"isAccessibleForFree\": true,",
                "\"isAccessibleForFree\": true, \"hasPart\":[{\"isAccessibleForFree\":false}],");

        assertNull(extract(html));
    }

    @ParameterizedTest
    @ValueSource(strings = {"isPremium", "isPaid", "requiresSubscription", "requiresLogin", "paywall"})
    void rejectsExplicitAccessRestrictionsInTheCurrentArticle(String key) {
        String html = page().replace("\"canonical_url\":", "\"" + key + "\":true, \"canonical_url\":");

        assertNull(extract(html));
    }

    @Test
    void rejectsAVisibleLoginNoticeEvenWithAFreeDeclaration() {
        assertNull(extract(page().replace("<p></p>", "<p>기사 전문은 로그인 후 확인할 수 있습니다.</p>")));
        assertNull(extract(page().replace("<p></p>", "<form>기사 전문은 로그인 후 확인할 수 있습니다.</form>")));
        assertNull(extract(page().replace(FIRST, "기사 전문은 로그인 후 확인할 수 있습니다.")));
    }

    @Test
    void rejectsAPaywallElementOutsideTheBody() {
        assertNull(extract(page().replace("</body>", "<div class='paywall'>Subscribe</div></body>")));
    }

    @Test
    void anUnrelatedFreeArticleCannotAuthorizeTheCurrentPayload() {
        assertNull(extract(page().replace("\"url\": \"" + URL + "\"",
                "\"url\": \"https://publisher.example/economy/102\"")));
    }

    @Test
    void rejectsAContradictorySecondCurrentArticleAccessDeclaration() {
        String paidDeclaration = "<script type='application/ld+json'>{\"@type\":\"NewsArticle\","
                + "\"url\":\"" + URL + "\",\"headline\":\"" + TITLE + "\",\"isAccessibleForFree\":false}</script>";

        assertNull(extract(page().replace("</head>", paidDeclaration + "</head>")));
    }

    @Test
    void requiresCurrentPageCanonicalUrlAndMatchingPayloadIdentity() {
        assertNull(extract(page().replace("\"canonical_url\": \"/economy/101\"",
                "\"canonical_url\": \"/economy/102\"")));
        assertNull(extract(page().replace("\"id\": \"101\",\"title\"", "\"id\": \"102\",\"title\"")));
        assertNull(extract(page().replace("\"query\": {\"id\":\"101\"}", "\"query\": {\"id\":\"102\"}")));
        assertNull(ArticleContentExtractor.extract(page(), "https://other.example/economy/101", TITLE));
    }

    @Test
    void rejectsMissingCanonicalUrlAndTitleMismatches() {
        assertNull(extract(page().replace("<link rel='canonical' href='" + URL + "'>", "")));
        assertNull(extract(page().replace("\"headline\": \"" + TITLE + "\"", "\"headline\": \"다른 기사\"")));
        assertNull(extract(page().replace("\"title\": \"" + TITLE + "\"", "\"title\": \"다른 기사\"")));
    }

    @Test
    void doesNotUseDescriptionsCaptionsOrRecommendationPayloadsAsArticleBody() {
        assertNull(extract(page().replace("\"type\":\"text\"", "\"type\":\"image\"")));
        assertNull(extract(page().replace("\"contentArrange\":", "\"recommendedArticles\":")));
    }

    @Test
    void rejectsShortMetadataEvenWhenLabeledAsText() {
        String html = page().replace(FIRST, TITLE).replace(SECOND, "사진 설명입니다.");

        assertNull(extract(html));
    }

    @Test
    void malformedAndDuplicateHydrationScriptsFailClosed() {
        assertNull(extract(page().replace("{\"props\":", "{BROKEN:\"props\":")));
        assertNull(extract(page().replace("</head>", "<script id='__NEXT_DATA__' type='application/json'>{}</script></head>")));
    }

    @Test
    void nonArticleJsonAndOversizedPayloadsAreNotSearchedForText() {
        assertNull(extract(page().replace("\"@type\": \"NewsArticle\"", "\"@type\": \"WebPage\"")));
        assertNull(extract(page().replace("{\"props\":", "{\"padding\":\"" + "x".repeat(512 * 1024) + "\",\"props\":")));
    }

    @Test
    void paragraphMarkupIsParsedWithoutScriptsOrControlsBecomingText() {
        String html = page().replace(FIRST, "<strong>" + FIRST + "</strong><script>hiddenCode()</script>")
                .replace("</script>\"}", "<\\/script>\"}");
        String body = extract(html);

        assertTrue(body.startsWith(FIRST));
        assertFalse(body.contains("hiddenCode"));
    }

    private static String extract(String html) {
        return ArticleContentExtractor.extract(html, URL, TITLE);
    }

    private static String page() {
        return """
                <html><head><link rel='canonical' href='%s'>
                <meta property='og:title' content='%s'>
                <script type='application/ld+json'>
                {"@type": "NewsArticle", "isAccessibleForFree": true,
                 "url": "%s", "headline": "%s"}
                </script>
                <script id='__NEXT_DATA__' type='application/json'>
                {"props":{"pageProps":{"id":"101","articleView":{
                  "id": "101","title": "%s", "canonical_url": "/economy/101",
                  "description":"추천 기사 요약", "contentArrange":[
                    {"type":"image","content":"사진 설명"},
                    {"type":"text","content":"%s"},
                    {"type":"advertisement","content":"광고 문구"},
                    {"type":"text","content":"%s"}],
                  "related":[{"content":"추천 기사"}]
                }}}, "query": {"id":"101"}}
                </script></head><body><h1>%s</h1>
                <div itemprop='articleBody'><p></p></div></body></html>
                """.formatted(URL, TITLE, URL, TITLE, TITLE, FIRST, SECOND, TITLE);
    }
}
