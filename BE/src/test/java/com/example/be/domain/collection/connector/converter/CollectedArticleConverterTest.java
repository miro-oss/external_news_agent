package com.example.be.domain.collection.connector.converter;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CollectedArticleConverterTest {

    @Test
    void parsesRfc2822PublishedAt() {
        assertEquals(OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.ofHours(9)),
                CollectedArticleConverter.toPublishedAt("Mon, 10 Aug 2026 09:00:00 +0900"));
    }

    @Test
    void parsesIsoPublishedAt() {
        assertEquals(OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.UTC),
                CollectedArticleConverter.toPublishedAt("2026-08-10T09:00:00Z"));
    }

    /**
     * 현재 시각으로 채우지 않는다. 발행일이 틀리면 "최근 기사" 필터가 조용히 망가진다.
     */
    @Test
    void leavesPublishedAtEmptyWhenUnreadable() {
        assertNull(CollectedArticleConverter.toPublishedAt("2026년 8월 10일"));
        assertNull(CollectedArticleConverter.toPublishedAt(" "));
        assertNull(CollectedArticleConverter.toPublishedAt(null));
    }

    @Test
    void takesSourceNameFromHost() {
        assertEquals("www.hankyung.com",
                CollectedArticleConverter.toSourceName("https://www.hankyung.com/article/2026081200001"));
    }

    @Test
    void returnsNullSourceNameForUnusableUrl() {
        assertNull(CollectedArticleConverter.toSourceName("h t t p://broken"));
        assertNull(CollectedArticleConverter.toSourceName(null));
    }
}
