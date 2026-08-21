package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FindingReuseCacheTest {

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final AgentProperties properties = properties();
    private final FindingReuseCache cache = new FindingReuseCache(findingRepository, properties);

    @Test
    void reusesOnlyMatchingLlmContractAndZerosUsage() {
        Article article = article(10L, "확정된 기사 본문");
        String inputHash = FindingReuseCache.inputHash(article);
        Finding source = Finding.builder()
                .article(article)
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
                .llmModel("free-model")
                .inputTokens(100L)
                .outputTokens(20L)
                .costUsd(new BigDecimal("0.001"))
                .credits(BigDecimal.ONE)
                .inputTruncated(true)
                .analysisInputHash(inputHash)
                .build();
        when(findingRepository.findReusableSources(
                Set.of(10L),
                AnalysisSource.LLM,
                Set.of(inputHash),
                "analyze.ko.v1",
                "gemini",
                "free-model")).thenReturn(List.of(source));

        Map<Long, FindingReuseCache.Lookup> lookups = cache.lookupAll(List.of(article), AgentPlan.FREE);

        AnalysisResult cached = lookups.get(10L).cached().orElseThrow();
        assertEquals(inputHash, lookups.get(10L).analysisInputHash());
        assertEquals(AnalysisSource.REUSED, cached.analysisSource());
        assertEquals("한국어 요약", cached.summary());
        assertEquals(0L, cached.metadata().inputTokens());
        assertEquals(BigDecimal.ZERO, cached.metadata().credits());
        assertTrue(cached.metadata().truncated());
    }

    @Test
    void separatesFreeAndPaidProviderModelContracts() {
        Article article = article(10L, "본문");
        String inputHash = FindingReuseCache.inputHash(article);
        when(findingRepository.findReusableSources(
                Set.of(10L), AnalysisSource.LLM, Set.of(inputHash),
                "analyze.ko.v1", "mindlogic-claude", "paid-model"))
                .thenReturn(List.of());

        FindingReuseCache.Lookup lookup = cache.lookupAll(List.of(article), AgentPlan.PAID).get(10L);

        assertTrue(lookup.cached().isEmpty());
        verify(findingRepository).findReusableSources(
                Set.of(10L), AnalysisSource.LLM, Set.of(inputHash),
                "analyze.ko.v1", "mindlogic-claude", "paid-model");
    }

    @Test
    void disablesReuseWhenCurrentModelIsNotConfigured() {
        properties.setFreeModel("");
        Article article = article(10L, "본문");

        FindingReuseCache.Lookup lookup = cache.lookupAll(List.of(article), AgentPlan.FREE).get(10L);

        assertTrue(lookup.cached().isEmpty());
        verifyNoInteractions(findingRepository);
    }

    @Test
    void hashesTopicContractAsPartOfAgentInput() {
        Article original = article(10L, "본문");
        Article changedTopic = Article.builder()
                .id(10L)
                .title(original.getTitle())
                .summary(original.getSummary())
                .body(original.getBody())
                .canonicalUrl(original.getCanonicalUrl())
                .language(original.getLanguage())
                .topic(Topic.builder()
                        .name("HBM")
                        .queryText("HBM4")
                        .requiredKeywords(List.of("HBM4", "양산"))
                        .optionalKeywords(List.of())
                        .excludedKeywords(List.of())
                        .build())
                .build();

        assertTrue(!FindingReuseCache.inputHash(original)
                .equals(FindingReuseCache.inputHash(changedTopic)));
    }

    private AgentProperties properties() {
        AgentProperties value = new AgentProperties();
        value.setAnalysisPromptVersion("analyze.ko.v1");
        value.setFreeModel("free-model");
        value.setPaidModel("paid-model");
        return value;
    }

    private Article article(Long id, String body) {
        return Article.builder()
                .id(id)
                .title("HBM4 일정")
                .summary("요약")
                .body(body)
                .canonicalUrl("https://example.com/" + id)
                .language("ko")
                .topic(Topic.builder()
                        .name("HBM")
                        .queryText("HBM")
                        .requiredKeywords(List.of("HBM4"))
                        .optionalKeywords(List.of())
                        .excludedKeywords(List.of())
                        .build())
                .build();
    }
}
