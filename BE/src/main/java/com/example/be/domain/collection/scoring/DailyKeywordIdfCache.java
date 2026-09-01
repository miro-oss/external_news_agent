package com.example.be.domain.collection.scoring;

import com.example.be.domain.collection.scoring.KeywordIdfJdbcRepository.CachedKeywordIdf;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 최근 30일 기사 corpus를 기준으로 언어별 IDF를 최대 하루 한 번 다시 계산한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyKeywordIdfCache implements KeywordIdfWeights {

    static final int CORPUS_DAYS = 30;
    static final int REFRESH_HOURS = 24;
    static final int FAILURE_RETRY_MINUTES = 5;
    static final String UNKNOWN_LANGUAGE = "und";

    private final KeywordIdfJdbcRepository repository;
    private final Map<CacheKey, CachedKeywordIdf> memory = new ConcurrentHashMap<>();
    private final Map<CacheKey, LocalDateTime> retryAfter = new ConcurrentHashMap<>();

    @Override
    public Map<String, Double> weights(String language, Collection<String> keywords) {
        return weightsAt(language, keywords, LocalDateTime.now(ApiTimeZone.ZONE));
    }

    synchronized Map<String, Double> weightsAt(String language,
                                               Collection<String> keywords,
                                               LocalDateTime now) {
        String normalizedLanguage = normalizeLanguage(language);
        List<String> normalizedKeywords = normalizeKeywords(keywords);
        if (normalizedKeywords.isEmpty()) {
            return Map.of();
        }
        if (normalizedKeywords.stream().allMatch(keyword -> isBackedOff(
                new CacheKey(normalizedLanguage, keyword), now))) {
            return currentWeights(normalizedLanguage, normalizedKeywords);
        }

        try {
            loadPersisted(normalizedLanguage, normalizedKeywords);
            List<String> staleKeywords = normalizedKeywords.stream()
                    .filter(keyword -> isStale(
                            memory.get(new CacheKey(normalizedLanguage, keyword)), now))
                    .toList();
            if (!staleKeywords.isEmpty()) {
                refresh(normalizedLanguage, staleKeywords, now);
            }
            return currentWeights(normalizedLanguage, normalizedKeywords);
        } catch (RuntimeException exception) {
            log.warn("키워드 IDF 갱신에 실패해 캐시된 값 또는 균등 가중치를 사용한다. "
                            + "language={} keywords={} error={}",
                    normalizedLanguage, normalizedKeywords.size(), exception.getMessage());
            normalizedKeywords.forEach(keyword -> retryAfter.put(
                    new CacheKey(normalizedLanguage, keyword),
                    now.plusMinutes(FAILURE_RETRY_MINUTES)));
            return currentWeights(normalizedLanguage, normalizedKeywords);
        }
    }

    private void loadPersisted(String language, List<String> keywords) {
        List<String> missing = keywords.stream()
                .filter(keyword -> !memory.containsKey(new CacheKey(language, keyword)))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        repository.findAll(language, missing)
                .forEach(value -> memory.put(new CacheKey(value.language(), value.keyword()), value));
    }

    private void refresh(String language, List<String> keywords, LocalDateTime now) {
        LocalDateTime collectedAfter = now.minusDays(CORPUS_DAYS);
        long documentCount = repository.countDocuments(language, collectedAfter);
        List<CachedKeywordIdf> refreshed = keywords.stream()
                .map(keyword -> refreshedValue(
                        language, keyword, documentCount, collectedAfter, now))
                .toList();
        repository.upsertAll(refreshed);
        refreshed.forEach(value -> {
            CacheKey key = new CacheKey(value.language(), value.keyword());
            memory.put(key, value);
            retryAfter.remove(key);
        });
    }

    private CachedKeywordIdf refreshedValue(String language,
                                            String keyword,
                                            long documentCount,
                                            LocalDateTime collectedAfter,
                                            LocalDateTime now) {
        long frequency = Math.min(
                documentCount,
                repository.countDocumentsContaining(language, keyword, collectedAfter));
        double idf = Math.log((documentCount + 1.0d) / (frequency + 1.0d)) + 1.0d;
        return new CachedKeywordIdf(language, keyword, documentCount, frequency, idf, now);
    }

    private Map<String, Double> currentWeights(String language, List<String> keywords) {
        Map<String, Double> result = new LinkedHashMap<>();
        keywords.forEach(keyword -> result.put(
                keyword,
                memory.getOrDefault(
                                new CacheKey(language, keyword),
                                defaultValue(language, keyword))
                        .idf()));
        return Map.copyOf(result);
    }

    private CachedKeywordIdf defaultValue(String language, String keyword) {
        return new CachedKeywordIdf(language, keyword, 0, 0, 1.0d, LocalDateTime.MIN);
    }

    private boolean isStale(CachedKeywordIdf value, LocalDateTime now) {
        return value == null || !value.refreshedAt().isAfter(now.minusHours(REFRESH_HOURS));
    }

    private boolean isBackedOff(CacheKey key, LocalDateTime now) {
        LocalDateTime retryAt = retryAfter.get(key);
        return retryAt != null && retryAt.isAfter(now);
    }

    static String normalizeLanguage(String language) {
        return StringUtils.hasText(language)
                ? language.trim().toLowerCase(Locale.ROOT)
                : UNKNOWN_LANGUAGE;
    }

    private List<String> normalizeKeywords(Collection<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream()
                .filter(StringUtils::hasText)
                .map(keyword -> keyword.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private record CacheKey(String language, String keyword) {
    }
}
