package com.example.be.domain.collection.converter;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleHasherTest {

    private static final String URL = "https://www.hankyung.com/article/2026081200001";

    /**
     * url_hash 컬럼이 VARCHAR2(64)다. 길이가 어긋나면 저장 시점에야 터진다.
     */
    @Test
    void producesSixtyFourHexCharacters() {
        String hash = ArticleHasher.urlHash(URL);

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void isStableForTheSameUrl() {
        assertEquals(ArticleHasher.urlHash(URL), ArticleHasher.urlHash(URL));
        assertEquals(ArticleHasher.urlHash(URL), ArticleHasher.urlHash("  " + URL + "  "));
    }

    @Test
    void removesTrackingParametersFragmentAndHostCase() {
        String tracked = "HTTPS://WWW.HANKYUNG.COM/article/2026081200001"
                + "?utm_source=newsletter&FBCLID=click-id#article";

        assertEquals(URL, ArticleHasher.normalizeUrl(tracked));
        assertEquals(ArticleHasher.urlHash(URL), ArticleHasher.urlHash(tracked));
    }

    @Test
    void preservesMeaningfulQueryParametersInTheirOriginalOrder() {
        String original = URL + "?articleId=7&view=full";
        String tracked = URL + "?utm_medium=email&articleId=7&view=full&utm_campaign=daily#top";

        assertEquals(original, ArticleHasher.normalizeUrl(tracked));
        assertEquals(ArticleHasher.urlHash(original), ArticleHasher.urlHash(tracked));
        assertNotEquals(ArticleHasher.urlHash(URL), ArticleHasher.urlHash(original));
    }

    @Test
    void removesTrackingNamesOnly() {
        String meaningful = URL + "?ref=fbclid&campaign=utm_source";

        assertEquals(meaningful, ArticleHasher.normalizeUrl(meaningful + "#section"));
    }

    @Test
    void removesCommonExactTrackingParameterNames() {
        for (String name : List.of(
                "_ga",
                "dclid",
                "fbclid",
                "gclid",
                "igshid",
                "mc_cid",
                "mc_eid",
                "msclkid",
                "spm",
                "yclid")) {
            assertEquals(URL, ArticleHasher.normalizeUrl(URL + "?" + name + "=tracking-id"));
        }
    }

    @Test
    void lowercasesRegistryAuthorityHostWithoutChangingUserInfoOrPort() {
        String url = "https://Reader@NEWS_FEED.EXAMPLE.COM:8443/A?utm_source=x";

        assertEquals("https://Reader@news_feed.example.com:8443/A", ArticleHasher.normalizeUrl(url));
    }

    @Test
    void keepsLegacyHashBehaviorForMalformedNonEmptyUrl() {
        String malformed = "https://example.com/article with space";

        assertEquals(malformed, ArticleHasher.normalizeUrl("  " + malformed + "  "));
    }

    @Test
    void preservesOpaqueUriBecauseItIsNotAnHttpUrl() {
        assertEquals("urn:article:42#section", ArticleHasher.normalizeUrl("urn:article:42#section"));
    }

    @Test
    void rejectsMissingUrl() {
        assertThrows(IllegalArgumentException.class, () -> ArticleHasher.urlHash(null));
        assertThrows(IllegalArgumentException.class, () -> ArticleHasher.urlHash(" "));
    }

    @Test
    void prefersBodyWhenPresent() {
        assertEquals(ArticleHasher.contentHash("제목", "요약", "본문"),
                ArticleHasher.contentHash("다른 제목", "다른 요약", "본문"));
    }

    /**
     * 본문이 없으면 제목·요약으로 지문을 만든다. null을 두면 비교할 게 없어 매 실행 UPDATED가 된다.
     */
    @Test
    void fallsBackToTitleAndSummaryFingerprint() {
        String first = ArticleHasher.contentHash("제목", "요약", null);

        assertEquals(first, ArticleHasher.contentHash("제목", "요약", null));
        assertNotEquals(first, ArticleHasher.contentHash("제목", "바뀐 요약", null));
        assertNotEquals(first, ArticleHasher.contentHash("바뀐 제목", "요약", null));
    }

    /**
     * 제목과 요약을 그냥 이어 붙이면 "AB"+"" 와 "A"+"B"가 같은 해시가 된다.
     */
    @Test
    void separatesTitleFromSummary() {
        assertNotEquals(ArticleHasher.contentHash("AB", "", null),
                ArticleHasher.contentHash("A", "B", null));
    }

    @Test
    void treatsMissingTitleAndSummaryAsEmpty() {
        assertEquals(ArticleHasher.contentHash(null, null, null), ArticleHasher.contentHash("", "", null));
    }

    @Test
    void analysisInputHashChangesWhenTitleSummaryOrBodyChanges() {
        String original = ArticleHasher.analysisInputHash("제목", "요약", "본문");

        assertNotEquals(original, ArticleHasher.analysisInputHash("바뀐 제목", "요약", "본문"));
        assertNotEquals(original, ArticleHasher.analysisInputHash("제목", "바뀐 요약", "본문"));
        assertNotEquals(original, ArticleHasher.analysisInputHash("제목", "요약", "바뀐 본문"));
        assertEquals(original, ArticleHasher.analysisInputHash(" 제목 ", " 요약 ", " 본문 "));
    }

    @Test
    void analysisInputHashDoesNotCollideWhenFieldContainsOldSeparator() {
        String separator = "\u001f";

        assertNotEquals(
                ArticleHasher.analysisInputHash("a" + separator + "b", "c", ""),
                ArticleHasher.analysisInputHash("a", "b", "c" + separator));
    }
}
