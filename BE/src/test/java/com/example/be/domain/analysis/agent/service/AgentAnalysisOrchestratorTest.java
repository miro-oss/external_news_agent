package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.entity.AnalysisSource;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(AnalysisSource.STUB, result.analysisSource());
        assertEquals(BigDecimal.ONE,
                result.analysisSections().getFirst().bullets().getFirst().confidence());
        assertEquals(List.of("HBM4"), result.entities().products());
        verify(recorder).recordSuccess(eq(42L), eq(10L), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void marksRealProviderAnalysisAsLlmAndKeepsMetadata() {
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article()));

        assertEquals(AnalysisSource.LLM, result.analysisSource());
        assertEquals("gemini", result.metadata().provider());
        assertEquals("gemini-2.5-flash", result.metadata().model());
        assertEquals("analyze.ko.v1", result.metadata().promptVersion());
        assertEquals(120L, result.metadata().inputTokens());
        assertEquals(30L, result.metadata().outputTokens());
        assertEquals(new BigDecimal("0.001"), result.metadata().costUsd());
        assertFalse(result.metadata().truncated());
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
        return response(evidenceIds, category, true);
    }

    private AgentAnalyzeResponse response(List<Integer> evidenceIds,
                                          String category,
                                          boolean mock) {
        return new AgentAnalyzeResponse(
                List.of("근거 문장."),
                List.of(new AgentAnalyzeResponse.Section(
                        "핵심",
                        List.of(new AgentAnalyzeResponse.Bullet(
                                "핵심 주장", evidenceIds, "grounded", BigDecimal.ONE)))),
                "한국어 요약",
                new AgentAnalyzeResponse.Classification(
                        "산업 동향 보도", "neutral", "low", "reference", category),
                new AgentAnalyzeResponse.Entities(List.of("SK하이닉스"), List.of("HBM4"), List.of()),
                new AgentAnalyzeResponse.Meta(
                        mock ? "mock" : "gemini",
                        mock ? "mock" : "gemini-2.5-flash",
                        mock ? "analyze.mock.v1" : "analyze.ko.v1",
                        mock ? 0L : 120L,
                        mock ? 0L : 30L,
                        mock ? BigDecimal.ZERO : new BigDecimal("0.001"),
                        BigDecimal.ZERO,
                        mock,
                        false));
    }
}
