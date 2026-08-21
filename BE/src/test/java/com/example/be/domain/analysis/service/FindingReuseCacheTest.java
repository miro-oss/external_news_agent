package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.converter.ArticleHasher;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FindingReuseCacheTest {

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final FindingReuseCache cache = new FindingReuseCache(findingRepository);

    @Test
    void reusesOnlyLlmFindingWithSameAnalysisInputAndZerosUsage() {
        Article article = Article.builder()
                .id(10L)
                .title("HBM4 일정")
                .summary("요약")
                .body("확정된 기사 본문")
                .build();
        String inputHash = ArticleHasher.analysisInputHash(
                article.getTitle(), article.getSummary(), article.getBody());
        Finding source = Finding.builder()
                .changeType(ChangeType.UPDATED)
                .summary("한국어 요약")
                .keyPoints(List.of(new FindingKeyPoint("핵심", List.of(0), "grounded")))
                .intent("산업 동향")
                .sentiment(Sentiment.NEUTRAL)
                .riskLevel(RiskLevel.MEDIUM)
                .relevance(Relevance.IMPORTANT)
                .category("제품/공정")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "확정된 기사 본문")))
                .analysisSections(List.of())
                .entities(FindingEntities.empty())
                .promptVersion("analyze.ko.v1")
                .llmProvider("gemini")
                .llmModel("configured-model")
                .inputTokens(100L)
                .outputTokens(20L)
                .costUsd(new BigDecimal("0.001"))
                .credits(BigDecimal.ONE)
                .inputTruncated(true)
                .analysisInputHash(inputHash)
                .build();
        when(findingRepository.findFirstByArticleIdAndAnalysisSourceAndAnalysisInputHashOrderByIdDesc(
                10L, AnalysisSource.LLM, inputHash)).thenReturn(Optional.of(source));

        FindingReuseCache.Lookup lookup = cache.lookup(article);

        FindingReuseCache.CachedAnalysis cached = lookup.cached().orElseThrow();
        assertEquals(inputHash, lookup.analysisInputHash());
        assertEquals(ChangeType.UPDATED, cached.changeType());
        assertEquals(AnalysisSource.REUSED, cached.result().analysisSource());
        assertEquals("한국어 요약", cached.result().summary());
        assertEquals(0L, cached.result().metadata().inputTokens());
        assertEquals(BigDecimal.ZERO, cached.result().metadata().credits());
        assertTrue(cached.result().metadata().truncated());
    }

    @Test
    void excludesStubAndReusedFindingsFromCacheSources() {
        Article article = Article.builder().id(10L).title("기사").build();
        String inputHash = ArticleHasher.analysisInputHash("기사", null, null);
        when(findingRepository.findFirstByArticleIdAndAnalysisSourceAndAnalysisInputHashOrderByIdDesc(
                10L, AnalysisSource.LLM, inputHash)).thenReturn(Optional.empty());
        Finding stub = Finding.builder().changeType(ChangeType.NEW).analysisSource(AnalysisSource.STUB).build();
        when(findingRepository.findFirstByArticleIdOrderByIdDesc(10L)).thenReturn(Optional.of(stub));

        FindingReuseCache.Lookup lookup = cache.lookup(article);

        assertTrue(lookup.cached().isEmpty());
        assertEquals(ChangeType.NEW, lookup.previousChangeType().orElseThrow());
        verify(findingRepository).findFirstByArticleIdAndAnalysisSourceAndAnalysisInputHashOrderByIdDesc(
                10L, AnalysisSource.LLM, inputHash);
    }
}
