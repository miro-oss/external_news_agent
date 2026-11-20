package com.example.be.domain.topics.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Receives one observation per (topic, article, seven-day window), already deduplicated by SQL. */
final class TopicArticleTrendAccumulator {

    private final LocalDateTime previousWindowStart;
    private final LocalDateTime windowEndExclusive;
    private final Map<String, KeywordCounts> counts = new LinkedHashMap<>();
    private int recentArticleCount;

    TopicArticleTrendAccumulator(LocalDateTime now) {
        previousWindowStart = now.minusDays(14);
        windowEndExclusive = now;
    }

    void observe(LocalDateTime observedAt, Collection<String> names) {
        if (observedAt.isBefore(previousWindowStart) || !observedAt.isBefore(windowEndExclusive)) {
            return;
        }
        int day = (int) (Duration.between(previousWindowStart, observedAt).toHours() / 24);
        if (day >= 7) {
            recentArticleCount++;
        }
        Map<String, String> articleKeywords = new LinkedHashMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                String display = name.trim();
                articleKeywords.merge(display.toLowerCase(Locale.ROOT), display,
                        (left, right) -> left.compareTo(right) <= 0 ? left : right);
            }
        }
        articleKeywords.forEach((normalized, display) -> {
            KeywordCounts keyword = counts.computeIfAbsent(normalized, ignored -> new KeywordCounts(display));
            if (display.compareTo(keyword.display) < 0) keyword.display = display;
            keyword.daily[day]++;
        });
    }

    TopicTrendJdbcRepository.TopicTrendSnapshot build() {
        List<TopicTrendJdbcRepository.TopicTrendKeyword> surge = new ArrayList<>();
        List<TopicTrendJdbcRepository.TopicRelatedKeyword> related = new ArrayList<>();
        counts.values().forEach(keyword -> {
            int previous = Arrays.stream(keyword.daily, 0, 7).sum();
            int recent = Arrays.stream(keyword.daily, 7, 14).sum();
            if (recent < 2) return;
            related.add(new TopicTrendJdbcRepository.TopicRelatedKeyword(keyword.display, recent,
                    BigDecimal.valueOf(recent).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(recentArticleCount), 2, RoundingMode.HALF_UP)));
            if (recent > previous) {
                double average = previous / 7.0;
                double variance = Arrays.stream(keyword.daily, 0, 7)
                        .mapToDouble(value -> Math.pow(value - average, 2)).average().orElse(0);
                double peak = Arrays.stream(keyword.daily, 7, 14).max().orElse(0);
                Double score = variance > 0 ? (peak - average) / Math.sqrt(variance) : null;
                BigDecimal zScore = score == null ? null
                        : BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
                surge.add(new TopicTrendJdbcRepository.TopicTrendKeyword(keyword.display, recent, previous,
                        recent - previous, zScore,
                        zScore != null && zScore.compareTo(new BigDecimal("2.50")) >= 0));
            }
        });
        surge.sort(Comparator.comparingInt(TopicTrendJdbcRepository.TopicTrendKeyword::deltaArticleCount).reversed()
                .thenComparing(Comparator.comparingInt(TopicTrendJdbcRepository.TopicTrendKeyword::articleCount).reversed())
                .thenComparing(TopicTrendJdbcRepository.TopicTrendKeyword::keyword));
        related.sort(Comparator.comparingInt(TopicTrendJdbcRepository.TopicRelatedKeyword::articleCount).reversed()
                .thenComparing(TopicTrendJdbcRepository.TopicRelatedKeyword::keyword));
        return new TopicTrendJdbcRepository.TopicTrendSnapshot(List.copyOf(surge), List.copyOf(related));
    }

    private static final class KeywordCounts {
        private String display;
        private final int[] daily = new int[14];

        private KeywordCounts(String display) {
            this.display = display;
        }
    }
}
