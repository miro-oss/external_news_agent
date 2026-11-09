package com.example.be.domain.collection.scoring;

import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleRecencyPolicyTest {

    private final Topic technical = Topic.builder().queryText("AI 기술").build();
    private final OffsetDateTime started = OffsetDateTime.parse("2026-11-09T10:00:00+09:00");

    @Test
    void appliesAnInclusiveWindowToPublicationInstantsRegardlessOfOffset() {
        OffsetDateTime boundaryUtc = started.minusDays(30).withOffsetSameInstant(ZoneOffset.UTC);
        assertTrue(ArticleRecencyPolicy.isEligible(technical, boundaryUtc, started, 30));
        assertFalse(ArticleRecencyPolicy.isEligible(technical, boundaryUtc.minusSeconds(1), started, 30));
        assertFalse(ArticleRecencyPolicy.isEligible(technical, started.minusYears(5), started, 30));
        assertTrue(ArticleRecencyPolicy.isEligible(technical, started.minusDays(20), started, 30));
    }

    @Test
    void preservesUnknownDatesAndNontechnicalOrUnknownScopes() {
        assertTrue(ArticleRecencyPolicy.isEligible(technical, null, started, 30));
        assertTrue(ArticleRecencyPolicy.isEligible(technical, started.minusYears(5), null, 30));
        assertTrue(ArticleRecencyPolicy.isEligible(null, started.minusYears(5), started, 30));
        assertTrue(ArticleRecencyPolicy.isEligible(Topic.builder().queryText("AI 산업").build(),
                started.minusYears(5), started, 30));
    }
}
