package com.example.be.domain.collection.scoring;

import com.example.be.domain.collection.scoring.KeywordIdfJdbcRepository.CachedKeywordIdf;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyKeywordIdfCacheTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 12, 0);

    @Test
    void calculatesSmoothedIdfAndReusesItBeforeTwentyFourHours() {
        KeywordIdfJdbcRepository repository = mock(KeywordIdfJdbcRepository.class);
        DailyKeywordIdfCache cache = new DailyKeywordIdfCache(repository);
        when(repository.findAll("ko", List.of("반도체", "hbm4"))).thenReturn(List.of());
        when(repository.countDocuments("ko", NOW.minusDays(30))).thenReturn(9L);
        when(repository.countDocumentsContaining("ko", "반도체", NOW.minusDays(30))).thenReturn(9L);
        when(repository.countDocumentsContaining("ko", "hbm4", NOW.minusDays(30))).thenReturn(0L);

        Map<String, Double> first = cache.weightsAt("KO", List.of("반도체", "HBM4"), NOW);
        Map<String, Double> second = cache.weightsAt(
                "ko", List.of("반도체", "hbm4"), NOW.plusHours(23).plusMinutes(59));

        assertEquals(1.0d, first.get("반도체"), 0.000001);
        assertEquals(Math.log(10.0d) + 1.0d, first.get("hbm4"), 0.000001);
        assertEquals(first, second);
        verify(repository, times(1)).countDocuments("ko", NOW.minusDays(30));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CachedKeywordIdf>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void refreshesAgainAtExactlyTwentyFourHours() {
        KeywordIdfJdbcRepository repository = mock(KeywordIdfJdbcRepository.class);
        DailyKeywordIdfCache cache = new DailyKeywordIdfCache(repository);
        when(repository.findAll(eq("ko"), any())).thenReturn(List.of());
        when(repository.countDocuments(eq("ko"), any())).thenReturn(4L);
        when(repository.countDocumentsContaining(eq("ko"), eq("hbm4"), any())).thenReturn(1L);

        cache.weightsAt("ko", List.of("HBM4"), NOW);
        cache.weightsAt("ko", List.of("HBM4"), NOW.plusHours(24));

        verify(repository, times(2)).countDocuments(eq("ko"), any());
        verify(repository, times(2)).upsertAll(any());
    }

    @Test
    void usesFreshPersistedValueWithoutRecountingCorpus() {
        KeywordIdfJdbcRepository repository = mock(KeywordIdfJdbcRepository.class);
        DailyKeywordIdfCache cache = new DailyKeywordIdfCache(repository);
        CachedKeywordIdf persisted = new CachedKeywordIdf(
                "ko", "hbm4", 100, 4, 4.0d, NOW.minusHours(23));
        when(repository.findAll("ko", List.of("hbm4"))).thenReturn(List.of(persisted));

        Map<String, Double> weights = cache.weightsAt("ko", List.of("HBM4"), NOW);

        assertEquals(Map.of("hbm4", 4.0d), weights);
        verify(repository, never()).countDocuments(any(), any());
        verify(repository, never()).upsertAll(any());
    }

    @Test
    void usesOneAsSmoothedIdfForEmptyCorpus() {
        KeywordIdfJdbcRepository repository = mock(KeywordIdfJdbcRepository.class);
        DailyKeywordIdfCache cache = new DailyKeywordIdfCache(repository);
        when(repository.findAll("ko", List.of("hbm4"))).thenReturn(List.of());
        when(repository.countDocuments("ko", NOW.minusDays(30))).thenReturn(0L);
        when(repository.countDocumentsContaining("ko", "hbm4", NOW.minusDays(30))).thenReturn(0L);

        double idf = cache.weightsAt("ko", List.of("HBM4"), NOW).get("hbm4");

        assertEquals(1.0d, idf, 0.000001);
    }

    @Test
    void keepsLanguageCorporaSeparate() {
        KeywordIdfJdbcRepository repository = mock(KeywordIdfJdbcRepository.class);
        DailyKeywordIdfCache cache = new DailyKeywordIdfCache(repository);
        when(repository.findAll(any(), any())).thenReturn(List.of());
        when(repository.countDocuments("ko", NOW.minusDays(30))).thenReturn(9L);
        when(repository.countDocuments("en", NOW.minusDays(30))).thenReturn(99L);
        when(repository.countDocumentsContaining(eq("ko"), eq("hbm4"), any())).thenReturn(0L);
        when(repository.countDocumentsContaining(eq("en"), eq("hbm4"), any())).thenReturn(0L);

        double korean = cache.weightsAt("ko", List.of("HBM4"), NOW).get("hbm4");
        double english = cache.weightsAt("en", List.of("HBM4"), NOW).get("hbm4");

        assertEquals(Math.log(10.0d) + 1.0d, korean, 0.000001);
        assertEquals(Math.log(100.0d) + 1.0d, english, 0.000001);
    }

    @Test
    void fallsBackToEqualWeightsWhenCacheLookupFails() {
        KeywordIdfJdbcRepository repository = mock(KeywordIdfJdbcRepository.class);
        DailyKeywordIdfCache cache = new DailyKeywordIdfCache(repository);
        when(repository.findAll(any(), any())).thenThrow(new IllegalStateException("db unavailable"));

        Map<String, Double> weights = cache.weightsAt(null, List.of("HBM4", "반도체"), NOW);
        Map<String, Double> backedOff = cache.weightsAt(
                null, List.of("HBM4", "반도체"), NOW.plusMinutes(4));

        assertEquals(Map.of("hbm4", 1.0d, "반도체", 1.0d), weights);
        assertEquals(weights, backedOff);
        verify(repository, times(1)).findAll(any(), any());
        verify(repository, never()).upsertAll(any());
    }
}
