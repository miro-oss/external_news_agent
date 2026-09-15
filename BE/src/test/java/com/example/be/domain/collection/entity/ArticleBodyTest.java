package com.example.be.domain.collection.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ArticleBodyTest {

    @Test
    void hashesExactUtf8IncludingWhitespaceAndSupplementaryCharacters() {
        String text = "한글 본문\nEnglish 😀\r\n 끝 ";
        ArticleBody body = ArticleBody.of(text);

        assertEquals("db51c0a1504ff4ba64f67146fccb8b0fbc3b265534275a9044f6742be460006e", body.getBodyHash());
        assertEquals(text, body.getBody());
        assertNotEquals(body.getBodyHash(), ArticleBody.of(text.strip()).getBodyHash());
        assertNotEquals(body.getBodyHash(), ArticleBody.of(text.replace("\r\n", "\n")).getBodyHash());
        assertNotEquals(ArticleBody.of("가").getBodyHash(), ArticleBody.of("가").getBodyHash());
    }

    @Test
    void retainsAllOfALongKoreanBody() {
        String text = "한글😀 본문\n".repeat(10_000);
        ArticleBody body = ArticleBody.of(text);

        assertEquals("cad9eaa1fe79dc86dc9acf0dae94680f1abd05265de175b38fe166209aebc103", body.getBodyHash());
        assertEquals(text, body.getBody());
    }

    @Test
    void distinguishesMissingBodyFromAnEmptyBody() {
        assertNull(ArticleBody.of(null));
        assertEquals("", ArticleBody.of("").getBody());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ArticleBody.of("").getBodyHash());
    }

    @Test
    void correctingOneArticleRetainsOtherArticlesAndItsVersionOnTheOriginalBody() {
        ArticleBody original = ArticleBody.of("같은 원문");
        Article first = Article.builder().storedBody(original).fetchStatus(FetchStatus.FULLTEXT).build();
        Article second = Article.builder().storedBody(original).fetchStatus(FetchStatus.FULLTEXT).build();
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 12, 0);
        ArticleVersion version = ArticleVersion.snapshotOf(first, null, 1, now);
        ArticleBody corrected = ArticleBody.of("정정한 원문");

        first.applyStoredFullText(corrected, FetchStatus.FULLTEXT, now);

        assertSame(corrected, first.getStoredBody());
        assertSame(original, second.getStoredBody());
        assertSame(original, version.getStoredBody());
        assertEquals("같은 원문", second.getBody());
        assertEquals("같은 원문", version.getBody());
        assertEquals("정정한 원문", first.getBody());
    }

    @Test
    void metadataUpdatesAndFailedFetchesKeepTheExistingSharedReference() {
        ArticleBody original = ArticleBody.of("보존할 원문");
        Article article = Article.builder().storedBody(original).fetchStatus(FetchStatus.FULLTEXT).build();
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 12, 0);

        article.applyUpdate("수정 제목", "수정 요약", new String(original.getBody()), "metadata-hash",
                FetchStatus.METADATA_ONLY, null, now);
        assertSame(original, article.getStoredBody());
        for (FetchStatus status : FetchStatus.values()) {
            if (status != FetchStatus.FULLTEXT) {
                article.applyStoredFullText(null, status, now);
                assertSame(original, article.getStoredBody());
                assertEquals("metadata-hash", article.getContentHash());
            }
        }
    }
}
