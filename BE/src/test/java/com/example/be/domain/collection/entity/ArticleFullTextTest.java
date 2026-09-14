package com.example.be.domain.collection.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleFullTextTest {
    @Test
    void successfulShortBodyIsUsable() {
        assertTrue(Article.builder().fetchStatus(FetchStatus.FULLTEXT).body("짧은 전문이다.").build().hasFullText());
    }

    @Test
    void successStatusDoesNotMakeAMissingOrWhitespaceOnlyBodyUsable() {
        assertFalse(Article.builder().fetchStatus(FetchStatus.FULLTEXT).build().hasFullText());
        for (String body : new String[]{"", " ", "\n\r\t", " \n\t "}) {
            assertFalse(Article.builder().fetchStatus(FetchStatus.FULLTEXT).body(body).build().hasFullText());
        }
    }

    @Test
    void metadataOrFailedFetchDoesNotBecomeUsableBecauseTextWasRetained() {
        for (FetchStatus status : FetchStatus.values()) {
            if (status != FetchStatus.FULLTEXT) {
                assertFalse(Article.builder().fetchStatus(status).body("이전 수집 본문").build().hasFullText());
            }
        }
        assertFalse(Article.builder().body("상태 없는 본문").build().hasFullText());
    }

    @Test
    void aSuccessfulLaterFetchCanMakeAPreservedRecordUsable() {
        Article article = Article.builder().fetchStatus(FetchStatus.METADATA_ONLY).summary("피드 요약").build();
        assertFalse(article.hasFullText());
        article.applyFullText("확보한 전문이다.", FetchStatus.FULLTEXT, LocalDateTime.now());
        assertTrue(article.hasFullText());
    }
}
