package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.reports.service.AgentReportOrchestrator;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportGenerator;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentReportOrchestratorTest {

    private final AgentProperties properties = enabledProperties();
    private final AgentClient client = mock(AgentClient.class);
    private final AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    private final ReportGenerator fallback = mock(ReportGenerator.class);
    private final CollectionRunArticleRepository observationRepository = mock(CollectionRunArticleRepository.class);
    private final AgentQuotaService quotaService = mock(AgentQuotaService.class);
    private final LlmPlanService planService = mock(LlmPlanService.class);
    private final CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
    private final QuotaReservation reservation = new QuotaReservation(
            1L, 42L, "run:42:report", AgentTask.REPORT, AgentPlan.FREE, BigDecimal.ONE);
    private final AgentReportOrchestrator orchestrator =
            new AgentReportOrchestrator(
                    properties, client, recorder, fallback, observationRepository,
                    quotaService, planService, resultWriter);

    @BeforeEach
    void reserveQuota() {
        when(quotaService.reserve(42L, "run:42:report", AgentTask.REPORT, AgentPlan.FREE))
                .thenReturn(reservation);
    }

    @Test
    void excludesStubAndIncludesReusedFindingInAgentRequest() {
        Finding llm = finding(501L, AnalysisSource.LLM, FetchStatus.FULLTEXT, "실제 LLM 요약");
        Finding stub = finding(502L, AnalysisSource.STUB, FetchStatus.FULLTEXT_BLOCKED, "STUB 요약");
        Finding reused = finding(503L, AnalysisSource.REUSED, FetchStatus.FULLTEXT, "재사용 요약");
        CollectionRunArticleRepository.ArticleFetchStatus paywalled =
                observation(900L, FetchStatus.FULLTEXT_BLOCKED);
        CollectionRunArticleRepository.ArticleFetchStatus robots =
                observation(901L, FetchStatus.ROBOTS_DISALLOWED);
        CollectionRunArticleRepository.ArticleFetchStatus failed =
                observation(902L, FetchStatus.FETCH_FAILED);
        when(observationRepository.findArticleFetchStatusesByRunId(42L)).thenReturn(List.of(
                paywalled, paywalled, robots, failed));
        when(client.report(any())).thenReturn(response(List.of(501L, 503L)));

        ReportDocument document = orchestrator.generate(
                run(),
                List.of(llm, stub, reused),
                LocalDateTime.of(2026, 8, 21, 9, 3));

        assertEquals("# Agent 보고서", document.markdownBody());
        assertEquals("report.ko.v1", document.promptVersion());
        assertEquals("gemini", document.llmProvider());
        assertEquals(ReportStatus.GENERATED, document.status());

        ArgumentCaptor<AgentReportRequest> captor = ArgumentCaptor.forClass(AgentReportRequest.class);
        verify(client).report(captor.capture());
        AgentReportRequest request = captor.getValue();
        assertEquals(List.of(501L, 503L), request.findings().stream()
                .map(AgentReportRequest.FindingPayload::id)
                .toList());
        assertEquals(1, request.sourceStats().stubExcluded());
        assertEquals(2, request.sourceStats().blocked());
        assertEquals(1, request.sourceStats().paywalled());
        assertEquals(1, request.sourceStats().failed());
        assertEquals(3, request.sourceStats().collected());
        assertEquals(List.of("수집 제약: 임시 응답 분석 1건 제외, 페이월 1건, 접근 제한 1건, 수집 실패 1건."),
                request.sourceNotes());
        verify(recorder).recordReportSuccess(
                eq(42L), eq(request), any(), any(LocalDateTime.class));
        verify(fallback, never()).generate(anyList(), any(), any());
    }

    @Test
    void rejectsUnknownFindingReferenceAndUsesSafeFallback() {
        CollectionRun run = run();
        Finding llm = finding(501L, AnalysisSource.LLM, FetchStatus.FULLTEXT, "실제 LLM 요약");
        ReportDocument fallbackDocument = new ReportDocument("fallback", "# fallback", "safe");
        when(client.report(any())).thenReturn(response(List.of(999L)));
        when(fallback.generate(eq(List.of(llm)), any(), any())).thenReturn(fallbackDocument);

        ReportDocument document = orchestrator.generate(
                run,
                List.of(llm),
                LocalDateTime.of(2026, 8, 21, 9, 3));

        assertEquals(fallbackDocument, document);
        verify(recorder).recordReportFailure(
                eq(42L), any(), eq("SCHEMA_VIOLATION"), any(), any(), any(),
                any(LocalDateTime.class));
    }

    @Test
    void disabledAgentUsesFallbackWithoutCallingAgent() {
        AgentProperties disabled = new AgentProperties();
        AgentReportOrchestrator disabledOrchestrator =
                new AgentReportOrchestrator(
                        disabled, client, recorder, fallback, observationRepository,
                        quotaService, planService, resultWriter);
        Finding stub = finding(502L, AnalysisSource.STUB, FetchStatus.FULLTEXT, "STUB 요약");
        ReportDocument fallbackDocument = new ReportDocument("fallback", "# fallback", "safe");
        when(fallback.generate(eq(List.of(stub)), any(), any())).thenReturn(fallbackDocument);

        ReportDocument document = disabledOrchestrator.generate(
                run(),
                List.of(stub),
                LocalDateTime.of(2026, 8, 21, 9, 3));

        assertEquals(fallbackDocument, document);
        verify(client, never()).report(any());
        verify(recorder, never()).recordReportSuccess(any(), any(), any(), any());
        verify(recorder, never()).recordReportFailure(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsQuotaReservationWhenNoLlmFindingIsEligible() {
        Finding stub = finding(502L, AnalysisSource.STUB, FetchStatus.FULLTEXT, "STUB 요약");
        ReportDocument fallbackDocument = new ReportDocument("fallback", "# fallback", "safe");
        when(fallback.generate(eq(List.of(stub)), any(), any())).thenReturn(fallbackDocument);

        ReportDocument document = orchestrator.generate(
                run(), List.of(stub), LocalDateTime.of(2026, 8, 21, 9, 3));

        assertEquals(fallbackDocument, document);
        verify(quotaService, never()).reserve(any(), any(), any(), any());
        verify(client, never()).report(any());
    }

    @Test
    void fallsBackToFreePlanAndSettlesFallbackReservationWhenPaidQuotaIsExhausted() {
        Finding llm = finding(501L, AnalysisSource.LLM, FetchStatus.FULLTEXT, "실제 LLM 요약");
        QuotaReservation fallbackReservation = new QuotaReservation(
                2L, 42L, "run:42:report:fallback-free",
                AgentTask.REPORT, AgentPlan.FREE, BigDecimal.ONE);
        when(quotaService.reserve(42L, "run:42:report", AgentTask.REPORT, AgentPlan.PAID))
                .thenThrow(new QuotaExceededException(AgentPlan.PAID, "exhausted"));
        when(planService.paidExhaustedAction()).thenReturn(PaidExhaustedAction.FALLBACK_FREE);
        when(quotaService.reserve(
                42L, "run:42:report:fallback-free", AgentTask.REPORT, AgentPlan.FREE))
                .thenReturn(fallbackReservation);
        when(client.report(any())).thenReturn(response(List.of(501L)));

        orchestrator.generate(
                run(AgentPlan.PAID), List.of(llm), LocalDateTime.of(2026, 8, 21, 9, 3));

        ArgumentCaptor<AgentReportRequest> captor = ArgumentCaptor.forClass(AgentReportRequest.class);
        verify(client).report(captor.capture());
        assertEquals(AgentPlan.FREE, captor.getValue().plan());
        assertEquals("run:42:report:fallback-free", captor.getValue().idempotencyKey());
        verify(quotaService).completeSuccess(fallbackReservation, BigDecimal.ZERO);
    }

    @Test
    void storesMockAgentReportWithMockStatus() {
        Finding llm = finding(501L, AnalysisSource.LLM, FetchStatus.FULLTEXT, "실제 LLM 요약");
        when(client.report(any())).thenReturn(response(List.of(501L), true));

        ReportDocument document = orchestrator.generate(
                run(), List.of(llm), LocalDateTime.of(2026, 8, 21, 9, 3));

        assertEquals(ReportStatus.MOCK, document.status());
    }

    @Test
    void sendsAtMostFiftyLlmFindingsInPriorityOrder() {
        List<Finding> findings = LongStream.rangeClosed(1, 51)
                .mapToObj(id -> finding(id, AnalysisSource.LLM, FetchStatus.FULLTEXT, "요약 " + id))
                .toList();
        when(client.report(any())).thenReturn(response(List.of(1L)));

        orchestrator.generate(run(), findings, LocalDateTime.of(2026, 8, 21, 9, 3));

        ArgumentCaptor<AgentReportRequest> captor = ArgumentCaptor.forClass(AgentReportRequest.class);
        verify(client).report(captor.capture());
        assertEquals(50, captor.getValue().findings().size());
        assertEquals(1L, captor.getValue().findings().getFirst().id());
    }

    @Test
    void excludesUngroundedFindingFromReportCandidates() {
        Finding supported = finding(
                501L,
                AnalysisSource.LLM,
                FetchStatus.FULLTEXT,
                "지원되는 요약",
                List.of(new FindingKeyPoint("지원되는 주장", List.of(0), "grounded")));
        Finding unsupported = finding(
                502L,
                AnalysisSource.LLM,
                FetchStatus.FULLTEXT,
                "제외할 요약",
                List.of(new FindingKeyPoint("근거 없는 주장", List.of(0), "ungrounded")));
        when(client.report(any())).thenReturn(response(List.of(501L)));

        orchestrator.generate(
                run(), List.of(unsupported, supported), LocalDateTime.of(2026, 8, 21, 9, 3));

        ArgumentCaptor<AgentReportRequest> captor = ArgumentCaptor.forClass(AgentReportRequest.class);
        verify(client).report(captor.capture());
        assertEquals(List.of(501L), captor.getValue().findings().stream()
                .map(AgentReportRequest.FindingPayload::id)
                .toList());
    }

    @Test
    void stripsUngroundedKeyPointFromMixedFindingPayload() {
        Finding mixed = finding(
                501L,
                AnalysisSource.LLM,
                FetchStatus.FULLTEXT,
                "혼합 요약",
                List.of(
                        new FindingKeyPoint("지원되는 주장", List.of(0, 0), "grounded"),
                        new FindingKeyPoint("근거 없는 주장", List.of(1), "ungrounded")));
        when(client.report(any())).thenReturn(response(List.of(501L)));

        orchestrator.generate(run(), List.of(mixed), LocalDateTime.of(2026, 8, 21, 9, 3));

        ArgumentCaptor<AgentReportRequest> captor = ArgumentCaptor.forClass(AgentReportRequest.class);
        verify(client).report(captor.capture());
        AgentReportRequest.KeyPointPayload keyPoint =
                captor.getValue().findings().getFirst().keyPoints().getFirst();
        assertEquals("지원되는 주장", keyPoint.text());
        assertEquals(List.of(0), keyPoint.evidence());
        assertEquals("grounded", keyPoint.groundedness());
    }

    private AgentProperties enabledProperties() {
        AgentProperties value = new AgentProperties();
        value.setEnabled(true);
        value.setToken("test-agent-token");
        value.setDefaultPlan(AgentPlan.FREE);
        return value;
    }

    private CollectionRun run() {
        return run(AgentPlan.FREE);
    }

    private CollectionRun run(AgentPlan plan) {
        Topic topic = Topic.builder().id(3L).name("HBM").build();
        CollectionRunItem item = CollectionRunItem.builder()
                .topic(topic)
                .scannedCount(3)
                .newCount(2)
                .updatedCount(0)
                .build();
        return CollectionRun.builder()
                .id(42L)
                .startedAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .llmPlan(plan)
                .scannedCount(0)
                .items(List.of(item))
                .build();
    }

    private Finding finding(Long id,
                            AnalysisSource source,
                            FetchStatus fetchStatus,
                            String summary) {
        return finding(id, source, fetchStatus, summary,
                List.of(new FindingKeyPoint("핵심", List.of(0), "grounded")));
    }

    private Finding finding(Long id,
                            AnalysisSource source,
                            FetchStatus fetchStatus,
                            String summary,
                            List<FindingKeyPoint> keyPoints) {
        Topic topic = Topic.builder().id(3L).name("HBM").build();
        Article article = Article.builder()
                .id(id + 1000)
                .topic(topic)
                .title("기사 " + id)
                .canonicalUrl("https://example.com/" + id)
                .sourceName("Example")
                .fetchStatus(fetchStatus)
                .build();
        return Finding.builder()
                .id(id)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary(summary)
                .keyPoints(keyPoints)
                .intent("산업 동향 보도")
                .sentiment(Sentiment.NEUTRAL)
                .riskLevel(RiskLevel.HIGH)
                .relevance(Relevance.IMPORTANT)
                .category("제품/공정")
                .analysisSource(source)
                .build();
    }

    private AgentReportResponse response(List<Long> findingIds) {
        return response(findingIds, false);
    }

    private AgentReportResponse response(List<Long> findingIds, boolean mockResponse) {
        return new AgentReportResponse(
                "Agent 보고서",
                List.of("짧은 속보"),
                List.of(new AgentReportResponse.ImportantEvent(
                        "중요 기사", "요약", "즉시 확인 필요", findingIds)),
                List.of(),
                List.of("출처 참고"),
                "# Agent 보고서",
                new AgentReportResponse.Meta(
                        mockResponse ? "mock" : "gemini",
                        mockResponse ? "deterministic-report" : "configured-model",
                        "report.ko.v1",
                        100L,
                        20L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        mockResponse,
                        false));
    }

    private CollectionRunArticleRepository.ArticleFetchStatus observation(Long articleId, FetchStatus status) {
        CollectionRunArticleRepository.ArticleFetchStatus observation =
                mock(CollectionRunArticleRepository.ArticleFetchStatus.class);
        when(observation.getArticleId()).thenReturn(articleId);
        when(observation.getFetchStatus()).thenReturn(status);
        return observation;
    }
}
