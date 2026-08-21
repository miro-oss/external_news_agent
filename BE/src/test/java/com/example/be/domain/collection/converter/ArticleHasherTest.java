package com.example.be.domain.collection.converter;

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
        assertNotEquals(ArticleHasher.urlHash(URL), ArticleHasher.urlHash(URL + "?utm_source=x"));
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
