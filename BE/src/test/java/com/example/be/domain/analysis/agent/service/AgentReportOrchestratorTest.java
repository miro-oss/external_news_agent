package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
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
import com.example.be.domain.reports.service.AgentReportOrchestrator;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportGenerator;
import com.example.be.domain.topics.entity.Topic;
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
    private final AgentReportOrchestrator orchestrator =
            new AgentReportOrchestrator(properties, client, recorder, fallback, observationRepository);

    @Test
    void excludesStubFromAgentRequestAndKeepsStructuredReportMetadata() {
        Finding llm = finding(501L, AnalysisSource.LLM, FetchStatus.FULLTEXT, "실제 LLM 요약");
        Finding stub = finding(502L, AnalysisSource.STUB, FetchStatus.FULLTEXT_BLOCKED, "STUB 요약");
        CollectionRunArticleRepository.ArticleFetchStatus paywalled =
                observation(900L, FetchStatus.FULLTEXT_BLOCKED);
        CollectionRunArticleRepository.ArticleFetchStatus robots =
                observation(901L, FetchStatus.ROBOTS_DISALLOWED);
        CollectionRunArticleRepository.ArticleFetchStatus failed =
                observation(902L, FetchStatus.FETCH_FAILED);
        when(observationRepository.findArticleFetchStatusesByRunId(42L)).thenReturn(List.of(
                paywalled, paywalled, robots, failed));
        when(client.report(any())).thenReturn(response(List.of(501L)));

        ReportDocument document = orchestrator.generate(
                run(),
                List.of(llm, stub),
                LocalDateTime.of(2026, 8, 21, 9, 3));

        assertEquals("# Agent 보고서", document.markdownBody());
        assertEquals("report.ko.v1", document.promptVersion());
        assertEquals("gemini", document.llmProvider());
        assertEquals(ReportStatus.GENERATED, document.status());

        ArgumentCaptor<AgentReportRequest> captor = ArgumentCaptor.forClass(AgentReportRequest.class);
        verify(client).report(captor.capture());
        AgentReportRequest request = captor.getValue();
        assertEquals(List.of(501L), request.findings().stream()
                .map(AgentReportRequest.FindingPayload::id)
                .toList());
        assertEquals(1, request.sourceStats().stubExcluded());
        assertEquals(2, request.sourceStats().blocked());
        assertEquals(1, request.sourceStats().paywalled());
        assertEquals(1, request.sourceStats().failed());
        assertEquals(3, request.sourceStats().collected());
        assertEquals(List.of("수집 제약: STUB 분석 1건 제외, 페이월 1건, 접근 제한 1건, 수집 실패 1건."),
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
                eq(42L), any(), eq("SCHEMA_VIOLATION"), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void disabledAgentUsesFallbackWithoutCallingAgent() {
        AgentProperties disabled = new AgentProperties();
        AgentReportOrchestrator disabledOrchestrator =
                new AgentReportOrchestrator(disabled, client, recorder, fallback, observationRepository);
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
        verify(recorder, never()).recordReportFailure(any(), any(), any(), any(), any(), any());
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

    private AgentProperties enabledProperties() {
        AgentProperties value = new AgentProperties();
        value.setEnabled(true);
        value.setToken("test-agent-token");
        value.setDefaultPlan(AgentPlan.FREE);
        return value;
    }

    private CollectionRun run() {
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
                .scannedCount(0)
                .items(List.of(item))
                .build();
    }

    private Finding finding(Long id,
                            AnalysisSource source,
                            FetchStatus fetchStatus,
                            String summary) {
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
                .keyPoints(List.of(new FindingKeyPoint("핵심", List.of(0), "grounded")))
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
