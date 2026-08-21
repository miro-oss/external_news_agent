package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceRequest;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.service.AnalysisResult;
import com.example.be.domain.analysis.service.AnalysisContext;
import com.example.be.domain.analysis.service.StubArticleAnalyzer;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

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
    private final AgentQuotaService quotaService = mock(AgentQuotaService.class);
    private final LlmPlanService planService = mock(LlmPlanService.class);
    private final CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
    private final QuotaReservation reservation = new QuotaReservation(
            1L, 42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.FREE, BigDecimal.ONE);
    private final QuotaReservation evidenceReservation = new QuotaReservation(
            2L, 42L, "run:42:article:10:evidence:0:0",
            AgentTask.VERIFY_EVIDENCE, AgentPlan.FREE, BigDecimal.ONE);
    private final AgentAnalysisOrchestrator orchestrator =
            new AgentAnalysisOrchestrator(
                    properties, client, recorder, stub, quotaService, planService, resultWriter);

    @BeforeEach
    void reserveQuota() {
        when(quotaService.reserve(42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.FREE))
                .thenReturn(reservation);
        when(quotaService.reserve(
                eq(42L),
                eq("run:42:article:10:evidence:0:0"),
                eq(AgentTask.VERIFY_EVIDENCE),
                eq(AgentPlan.FREE)))
                .thenReturn(evidenceReservation);
        when(client.verifyEvidence(any())).thenReturn(evidenceResponse("grounded", List.of(1)));
    }

    @Test
    void mapsOneBasedAgentEvidenceToZeroBasedPublicContractAndRecordsMockRun() {
        when(client.analyze(any())).thenReturn(response(List.of(1)));

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.FREE));

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

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals(AnalysisSource.LLM, result.analysisSource());
        assertEquals("gemini", result.metadata().provider());
        assertEquals("gemini-2.5-flash", result.metadata().model());
        assertEquals("analyze.ko.v1", result.metadata().promptVersion());
        assertEquals(120L, result.metadata().inputTokens());
        assertEquals(30L, result.metadata().outputTokens());
        assertEquals(new BigDecimal("0.001"), result.metadata().costUsd());
        assertFalse(result.metadata().truncated());
        ArgumentCaptor<AgentEvidenceRequest> captor =
                ArgumentCaptor.forClass(AgentEvidenceRequest.class);
        verify(client).verifyEvidence(captor.capture());
        assertEquals("핵심 주장", captor.getValue().claim());
        assertEquals("근거 문장.", captor.getValue().sentences().getFirst().text());
    }

    @Test
    void removesEvidenceWhenVerifierRejectsRealProviderBullet() {
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));
        when(client.verifyEvidence(any())).thenReturn(evidenceResponse("ungrounded", List.of()));

        AnalysisResult result = orchestrator.analyze(
                new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals("ungrounded", result.keyPoints().getFirst().groundedness());
        assertEquals(List.of(), result.keyPoints().getFirst().evidence());
        assertEquals(BigDecimal.ZERO,
                result.analysisSections().getFirst().bullets().getFirst().confidence());
    }

    @Test
    void usesFallbackReservationKeyAndFreePlanWhenPaidQuotaIsExhausted() {
        QuotaReservation fallback = new QuotaReservation(
                2L, 42L, "run:42:article:10:fallback-free",
                AgentTask.ANALYZE, AgentPlan.FREE, BigDecimal.ONE);
        when(quotaService.reserve(42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID))
                .thenThrow(new QuotaExceededException(AgentPlan.PAID, "exhausted"));
        when(planService.paidExhaustedAction()).thenReturn(PaidExhaustedAction.FALLBACK_FREE);
        when(quotaService.reserve(
                42L, "run:42:article:10:fallback-free", AgentTask.ANALYZE, AgentPlan.FREE))
                .thenReturn(fallback);
        when(client.analyze(any())).thenReturn(response(List.of(1), "제품/공정", false));

        orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.PAID));

        ArgumentCaptor<AgentAnalyzeRequest> captor = ArgumentCaptor.forClass(AgentAnalyzeRequest.class);
        verify(client).analyze(captor.capture());
        assertEquals(AgentPlan.FREE, captor.getValue().plan());
        assertEquals("run:42:article:10:fallback-free", captor.getValue().idempotencyKey());
    }

    @Test
    void recordsFailureAndFallsBackToStubWhenEvidenceDoesNotExist() {
        AnalysisResult stubResult = mock(AnalysisResult.class);
        Article article = article();
        when(client.analyze(any())).thenReturn(response(List.of(2)));
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("EVIDENCE_MISSING"), any(), any(), any(),
                any(LocalDateTime.class));
    }

    @Test
    void returnsSuccessfulAnalysisEvenWhenAuditRecordingFails() {
        when(client.analyze(any())).thenReturn(response(List.of(1)));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(recorder).recordSuccess(eq(42L), eq(10L), any(), any(), any());

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article(), AgentPlan.FREE));

        assertEquals("한국어 요약", result.summary());
    }

    @Test
    void fallsBackEvenWhenFailureAuditRecordingFails() {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenThrow(new IllegalStateException("invalid response"));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(recorder).recordFailure(
                        eq(42L), eq(10L), any(), any(), any(), any(), any(), any());
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result);
    }

    @Test
    void rejectsUnknownCategoryAndFallsBackToStub() {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenReturn(response(List.of(1), "반도체"));
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("SCHEMA_VIOLATION"), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStructuredResponses")
    void rejectsInvalidStructuredFieldsAndFallsBackToStub(
            String caseName,
            AgentAnalyzeResponse invalidResponse
    ) {
        Article article = article();
        AnalysisResult stubResult = mock(AnalysisResult.class);
        when(client.analyze(any())).thenReturn(invalidResponse);
        when(stub.analyze(article)).thenReturn(stubResult);

        AnalysisResult result = orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE));

        assertSame(stubResult, result, caseName);
        verify(recorder).recordFailure(
                eq(42L), eq(10L), any(), eq("SCHEMA_VIOLATION"), any(), any(), any(), any());
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

    private static AgentAnalyzeResponse response(List<Integer> evidenceIds) {
        return response(evidenceIds, "제품/공정");
    }

    private static AgentAnalyzeResponse response(List<Integer> evidenceIds, String category) {
        return response(evidenceIds, category, true);
    }

    private static AgentAnalyzeResponse response(List<Integer> evidenceIds,
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

    private static AgentEvidenceResponse evidenceResponse(String status, List<Integer> acceptedIds) {
        return new AgentEvidenceResponse(
                status,
                acceptedIds,
                "검증 결과",
                new AgentEvidenceResponse.Meta(
                        "gemini",
                        "gemini-2.5-flash",
                        "evidence.ko.v1",
                        10L,
                        5L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        false,
                        false));
    }

    private static Stream<Arguments> invalidStructuredResponses() {
        AgentAnalyzeResponse valid = response(List.of(1));
        AgentAnalyzeResponse.Section validSection = valid.sections().getFirst();
        AgentAnalyzeResponse.Bullet validBullet = validSection.bullets().getFirst();
        return Stream.of(
                Arguments.of("entities null", copy(valid, valid.sections(), null, valid.meta())),
                Arguments.of("negative token", copy(
                        valid,
                        valid.sections(),
                        valid.entities(),
                        new AgentAnalyzeResponse.Meta(
                                "mock", "mock", "analyze.mock.v1", -1L, 0L,
                                BigDecimal.ZERO, BigDecimal.ZERO, true, false))),
                Arguments.of("confidence out of range", copy(
                        valid,
                        List.of(new AgentAnalyzeResponse.Section(
                                "핵심",
                                List.of(new AgentAnalyzeResponse.Bullet(
                                        validBullet.text(),
                                        validBullet.evidenceSentenceIds(),
                                        validBullet.groundedness(),
                                        new BigDecimal("1.1"))))),
                        valid.entities(),
                        valid.meta())),
                Arguments.of("blank heading", copy(
                        valid,
                        List.of(new AgentAnalyzeResponse.Section(" ", validSection.bullets())),
                        valid.entities(),
                        valid.meta())),
                Arguments.of("unknown groundedness", copy(
                        valid,
                        List.of(new AgentAnalyzeResponse.Section(
                                "핵심",
                                List.of(new AgentAnalyzeResponse.Bullet(
                                        validBullet.text(),
                                        validBullet.evidenceSentenceIds(),
                                        "unknown",
                                        validBullet.confidence())))),
                        valid.entities(),
                        valid.meta())),
                Arguments.of("empty sections", copy(
                        valid, List.of(), valid.entities(), valid.meta()))
        );
    }

    private static AgentAnalyzeResponse copy(
            AgentAnalyzeResponse source,
            List<AgentAnalyzeResponse.Section> sections,
            AgentAnalyzeResponse.Entities entities,
            AgentAnalyzeResponse.Meta meta
    ) {
        return new AgentAnalyzeResponse(
                source.sentences(),
                sections,
                source.summaryKo(),
                source.classification(),
                entities,
                meta);
    }
}
