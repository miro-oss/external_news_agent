package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisResult;
import com.example.be.domain.analysis.service.AnalysisContext;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAnalysisOrchestratorTest {

    private final AgentProperties properties = enabledProperties();
    private final AgentClient client = mock(AgentClient.class);
    private final AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    private final StubArticleAnalyzer stub = mock(StubArticleAnalyzer.class);
    private final AgentAnalysisOrchestrator orchestrator =
            new AgentAnalysisOrchestrator(properties, client, recorder, stub);

    @Test
    void mapsOneBasedAgentEvidenceToZeroBasedPublicContractAndRecordsMockRun() {
        when(client.analyze(any())).thenReturn(response(List.of(1)));

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article()));

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
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article));

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("EVIDENCE_MISSING"), any(), any(LocalDateTime.class));
    }

    @Test
    void returnsSuccessfulAnalysisEvenWhenAuditRecordingFails() {
        when(client.analyze(any())).thenReturn(response(List.of(1)));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(recorder).recordSuccess(eq(42L), eq(10L), any(), any(), any());

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article()));

        assertEquals("한국어 요약", result.summary());
    }

    @Test
    void fallsBackEvenWhenFailureAuditRecordingFails() {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenThrow(new IllegalStateException("invalid response"));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(recorder).recordFailure(eq(42L), eq(10L), any(), any(), any(), any());
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article));

        assertSame(stubResult, result);
    }

    @Test
    void rejectsUnknownCategoryAndFallsBackToStub() {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenReturn(response(List.of(1), "반도체"));
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article));

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("SCHEMA_VIOLATION"), any(), any());
    }

    private AgentProperties enabledProperties() {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setToken("test-agent-token");
        properties.setDefaultPlan(AgentPlan.FREE);
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
        return response(evidenceIds, "제품/공정");
    }

    private AgentAnalyzeResponse response(List<Integer> evidenceIds, String category) {
        return new AgentAnalyzeResponse(
                List.of("근거 문장."),
                List.of(new AgentAnalyzeResponse.Section(
                        "핵심",
                        List.of(new AgentAnalyzeResponse.Bullet(
                                "핵심 주장", evidenceIds, "grounded", BigDecimal.ONE)))),
                "한국어 요약",
                new AgentAnalyzeResponse.Classification(
                        "산업 동향 보도", "neutral", "low", "reference", category),
                new AgentAnalyzeResponse.Entities(List.of(), List.of(), List.of()),
                new AgentAnalyzeResponse.Meta(
                        "mock", "mock", "analyze.mock.v1", 0L, 0L,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }
}
