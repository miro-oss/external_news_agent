package com.example.be.news.agent;

import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisResult;
import com.example.be.domain.analysis.service.StubArticleAnalyzer;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentArticleAnalyzerTest {

    private final AgentProperties properties = enabledProperties();
    private final AgentClient client = mock(AgentClient.class);
    private final AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    private final StubArticleAnalyzer stub = mock(StubArticleAnalyzer.class);
    private final AgentArticleAnalyzer analyzer = new AgentArticleAnalyzer(properties, client, recorder, stub);

    @Test
    void mapsOneBasedAgentEvidenceToZeroBasedPublicContractAndRecordsMockRun() {
        when(client.analyze(any())).thenReturn(response(List.of(1)));

        AnalysisResult result = analyzer.analyze(42L, article());

        assertEquals("한국어 요약", result.summary());
        assertEquals(List.of(0), result.keyPoints().getFirst().evidence());
        assertEquals(0, result.sections().getFirst().index());
        assertEquals(Sentiment.NEUTRAL, result.sentiment());
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertEquals(Relevance.REFERENCE, result.relevance());
        verify(recorder).recordSuccess(eq(42L), eq(10L), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void recordsFailureAndFallsBackToStubWhenEvidenceDoesNotExist() {
        AnalysisResult stubResult = mock(AnalysisResult.class);
        Article article = article();
        when(client.analyze(any())).thenReturn(response(List.of(2)));
        when(stub.analyze(42L, article)).thenReturn(stubResult);

        AnalysisResult result = analyzer.analyze(42L, article);

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("EVIDENCE_MISSING"), any(), any(LocalDateTime.class));
    }

    private AgentProperties enabledProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setDefaultPlan("FREE");
        return properties;
    }

    private Article article() {
        return Article.builder()
                .id(10L)
                .topic(Topic.builder()
                        .name("HBM")
                        .queryText("HBM")
                        .requiredKeywords(List.of("HBM"))
                        .optionalKeywords(List.of())
                        .excludedKeywords(List.of())
                        .build())
                .canonicalUrl("https://example.com/10")
                .title("기사 제목")
                .body("근거 문장.")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
    }

    private AgentAnalyzeResponse response(List<Integer> evidenceIds) {
        return new AgentAnalyzeResponse(
                List.of("근거 문장."),
                List.of(new AgentAnalyzeResponse.Section(
                        "핵심",
                        List.of(new AgentAnalyzeResponse.Bullet(
                                "핵심 주장", evidenceIds, "grounded", BigDecimal.ONE)))),
                "한국어 요약",
                new AgentAnalyzeResponse.Classification(
                        "산업 동향 보도", "neutral", "low", "reference", "제품/공정"),
                new AgentAnalyzeResponse.Entities(List.of(), List.of(), List.of()),
                new AgentAnalyzeResponse.Meta(
                        "mock", "mock", "analyze.mock.v1", 0L, 0L,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }
}
