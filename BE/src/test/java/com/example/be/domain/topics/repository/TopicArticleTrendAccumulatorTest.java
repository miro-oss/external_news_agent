package com.example.be.domain.topics.repository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicArticleTrendAccumulatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 9, 15);

    @Test
    void countsArticlesWithNoKeywordsInDenominatorAndNormalizesRepeatedNames() {
        var accumulator = new TopicArticleTrendAccumulator(NOW);
        accumulator.observe(NOW.minusDays(8), List.of("HBM4"));
        accumulator.observe(NOW.minusDays(2), List.of(" HBM4 ", "hbm4", "HBM4"));
        accumulator.observe(NOW.minusDays(1), List.of("HBM4"));
        accumulator.observe(NOW.minusHours(1), List.of());

        var result = accumulator.build();
        var trend = result.surgeKeywords().getFirst();
        assertEquals("HBM4", trend.keyword());
        assertEquals(2, trend.articleCount());
        assertEquals(1, trend.previousArticleCount());
        assertEquals(1, trend.deltaArticleCount());
        assertEquals(new BigDecimal("66.67"), result.relatedKeywords().getFirst().sharePercent());
    }

    @Test
    void usesExactRollingBoundariesAndExcludesNowAndFuture() {
        var accumulator = new TopicArticleTrendAccumulator(NOW);
        accumulator.observe(NOW.minusDays(14), List.of("HBM4"));
        accumulator.observe(NOW.minusDays(7), List.of("HBM4"));
        accumulator.observe(NOW.minusSeconds(1), List.of("HBM4"));
        accumulator.observe(NOW.minusDays(14).minusNanos(1), List.of("HBM4"));
        accumulator.observe(NOW, List.of("HBM4"));
        accumulator.observe(NOW.plusHours(1), List.of("HBM4"));

        var trend = accumulator.build().surgeKeywords().getFirst();
        assertEquals(2, trend.articleCount());
        assertEquals(1, trend.previousArticleCount());
        assertEquals(new BigDecimal("100.00"), accumulator.build().relatedKeywords().getFirst().sharePercent());
    }

    @Test
    void keepsZeroBaselineUnavailableAndRanksByIncreaseBeforeBurst() {
        var accumulator = new TopicArticleTrendAccumulator(NOW);
        accumulator.observe(NOW.minusDays(8), List.of("HBM4"));
        for (int i = 0; i < 3; i++) accumulator.observe(NOW.minusHours(i + 1), List.of("HBM4"));
        for (int i = 0; i < 5; i++) accumulator.observe(NOW.minusDays(2).plusHours(i), List.of("DDR5"));

        var result = accumulator.build();
        assertEquals(List.of("DDR5", "HBM4"), result.surgeKeywords().stream()
                .map(TopicTrendJdbcRepository.TopicTrendKeyword::keyword).toList());
        assertNull(result.surgeKeywords().getFirst().zScore());
        assertFalse(result.surgeKeywords().getFirst().burst());
        assertTrue(result.surgeKeywords().getLast().burst());
    }

    @Test
    void returnsCandidatesBeyondTwelveSoConfiguredKeywordsCannotStarveResults() {
        var accumulator = new TopicArticleTrendAccumulator(NOW);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 20; i++) names.add("product" + i);
        accumulator.observe(NOW.minusDays(2), names);
        accumulator.observe(NOW.minusDays(1), names);

        assertEquals(20, accumulator.build().surgeKeywords().size());
        assertEquals(20, accumulator.build().relatedKeywords().size());
    }

    @Test
    void burstUsesTheSameRoundedScoreAsTheResponse() {
        var accumulator = new TopicArticleTrendAccumulator(NOW);
        int[] previousDaily = {0, 0, 0, 1, 2, 4, 8};
        for (int day = 0; day < 7; day++) {
            for (int article = 0; article < previousDaily[day]; article++) {
                accumulator.observe(NOW.minusDays(14).plusDays(day).plusHours(1), List.of("HBM4"));
            }
        }
        for (int article = 0; article < 9; article++) {
            accumulator.observe(NOW.minusDays(2), List.of("HBM4"));
            accumulator.observe(NOW.minusDays(1), List.of("HBM4"));
        }
        var keyword = accumulator.build().surgeKeywords().getFirst();
        assertEquals(new BigDecimal("2.50"), keyword.zScore());
        assertTrue(keyword.burst());
    }

    @Test
    void excludesFlatDecliningAndSingleArticleKeywordsFromSurges() {
        var accumulator = new TopicArticleTrendAccumulator(NOW);
        for (int i = 0; i < 3; i++) accumulator.observe(NOW.minusDays(10).plusHours(i),
                i < 2 ? List.of("flat", "declining") : List.of("declining"));
        accumulator.observe(NOW.minusDays(2), List.of("flat", "declining", "single"));
        accumulator.observe(NOW.minusDays(1), List.of("flat", "declining"));

        assertTrue(accumulator.build().surgeKeywords().isEmpty());
        assertEquals(List.of("declining", "flat"), accumulator.build().relatedKeywords().stream()
                .map(TopicTrendJdbcRepository.TopicRelatedKeyword::keyword).toList());
    }
}
