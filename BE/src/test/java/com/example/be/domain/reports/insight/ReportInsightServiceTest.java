package com.example.be.domain.reports.insight;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.be.domain.analysis.agent.client.*;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.*;
import com.example.be.domain.analysis.agent.entity.*;
import com.example.be.domain.analysis.agent.quota.*;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.feedback.model.FeedbackModels.Category;
import com.example.be.domain.feedback.model.FeedbackModels.EventContext;
import com.example.be.domain.feedback.model.FeedbackModels.Feedback;
import com.example.be.domain.feedback.model.FeedbackModels.Item;
import com.example.be.domain.feedback.model.FeedbackModels.Status;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.example.be.domain.reports.insight.ReportInsightTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportInsightServiceTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ReportInsightService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    AgentProperties properties = new AgentProperties();
    ReportInsightSnapshotAssembler assembler = mock(ReportInsightSnapshotAssembler.class);
    ReportInsightPersistenceService persistence = mock(ReportInsightPersistenceService.class);
    AgentClient client = mock(AgentClient.class);
    AgentQuotaService quota = mock(AgentQuotaService.class);
    LlmPlanService plans = mock(LlmPlanService.class);
    AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    ReportInsightJobRepository jobs = mock(ReportInsightJobRepository.class);
    ReportInsightService service = new ReportInsightService(properties, assembler, persistence,
            new ReportInsightValidator(), client, quota, plans, recorder, new ReportInsightExecutionRecorder(recorder, quota, persistence), jobs);
    @BeforeEach void setup() {
        logs.start();
        logger.addAppender(logs);
        properties.setEnabled(true);
        when(recorder.recordReportInsightSuccess(any(), any(), any(), any(), any())).thenReturn(true);
        when(recorder.recordReportInsightFailure(any(), any(), any(), any(), any())).thenReturn(true);
        when(assembler.assemble(10L)).thenReturn(snapshot("a".repeat(64)));
        when(assembler.assembleForRead(10L)).thenReturn(snapshot("a".repeat(64)));
        when(persistence.findCached(anyLong(), anyString(), anyCollection())).thenReturn(List.of());
        when(plans.get()).thenReturn(new LlmSettingDTO.PlanResponse(AgentPlan.PAID, false, PaidExhaustedAction.STUB));
        when(quota.reserveReportInsight(any(), anyString(), any())).thenAnswer(call ->
                new QuotaReservation(1L, 20L, call.getArgument(1), AgentTask.INSIGHT, AgentPlan.PAID, BigDecimal.ONE));
        when(client.reportInsight(any())).thenAnswer(call -> responseFor(call.getArgument(0)));
        when(persistence.saveGenerated(any(), any())).thenAnswer(call -> {
            AgentReportInsightResponse response = call.getArgument(1);
            return List.of(row(Audience.valueOf(response.insights().getFirst().audience())));
        });
        when(persistence.toDto(any())).thenAnswer(call -> dto(((NewsReportInsight) call.getArgument(0)).getAudience()));
    }
    @AfterEach void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }
    @Test void eachMissingAudienceHasDistinctSingleAudienceReservationAndProviderExecution() {
        var result = service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER", "IT_INFRA")));
        assertFalse(result.cached());
        assertEquals(List.of(Audience.CHIP_MAKER, Audience.IT_INFRA), result.insights().stream().map(ReportInsightDTO.AudienceInsight::audience).toList());
        var requests = ArgumentCaptor.forClass(AgentReportInsightRequest.class);
        verify(client, times(2)).reportInsight(requests.capture());
        assertEquals(List.of("CHIP_MAKER"), requests.getAllValues().getFirst().audiences());
        assertEquals(List.of("IT_INFRA"), requests.getAllValues().getLast().audiences());
        assertNotEquals(requests.getAllValues().getFirst().idempotencyKey(), requests.getAllValues().getLast().idempotencyKey());
        verify(quota, times(2)).reserveReportInsight(eq(20L), anyString(), eq(AgentPlan.PAID));
        verify(quota, times(2)).completeSuccess(any(), eq(BigDecimal.ONE));
        verify(recorder, times(2)).recordReportInsightSuccess(eq(20L), any(), any(), any(), any());
    }
    @Test void getReadsExactCurrentHashWithoutProviderQuotaOrAuditWrites() {
        when(persistence.findCached(10L, "a".repeat(64), List.of(Audience.CHIP_MAKER))).thenReturn(List.of(row(Audience.CHIP_MAKER)));
        assertTrue(service.get(10L, "CHIP_MAKER").cached());
        verifyNoInteractions(client, quota, plans, recorder);
        when(assembler.assembleForRead(10L)).thenReturn(snapshot("b".repeat(64)));
        var missing = assertThrows(GeneralException.class, () -> service.get(10L, "CHIP_MAKER"));
        assertEquals("저장된 리포트 관점 인사이트가 없습니다.", missing.getMessage());
        verifyNoInteractions(client, quota, plans, recorder);
    }
    @Test void queuedOrRunningAutoAnalysisReturnsPendingWhileTerminalOrLegacyIsMissing() {
        when(jobs.isPending(10L, Audience.IT_INFRA)).thenReturn(true);
        var pending = assertThrows(GeneralException.class, () -> service.get(10L, "IT_INFRA"));
        assertEquals(GeneralErrorCode.CONFLICT, pending.getCode());
        assertEquals("동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요.", pending.getMessage());
        when(jobs.isPending(10L, Audience.IT_INFRA)).thenReturn(false);
        assertEquals(GeneralErrorCode.NOT_FOUND,
                assertThrows(GeneralException.class, () -> service.get(10L, "IT_INFRA")).getCode());
        verifyNoInteractions(client, quota, plans, recorder);
    }
    @Test void currentCacheWinsOverAutomaticPendingAndDisabledFeature() {
        when(jobs.isPending(10L, Audience.CHIP_MAKER)).thenReturn(true);
        when(persistence.findCached(10L, "a".repeat(64), List.of(Audience.CHIP_MAKER)))
                .thenReturn(List.of(row(Audience.CHIP_MAKER)));
        properties.setEnabled(false);
        assertTrue(service.get(10L, "CHIP_MAKER").cached());
        verifyNoInteractions(jobs, client, quota, plans, recorder);
    }
    @Test void disabledFeatureAndSchedulerReturnMissingInsteadOfUnendingPending() {
        when(jobs.isPending(10L, Audience.CHIP_MAKER)).thenReturn(true);
        properties.setEnabled(false);
        assertEquals(GeneralErrorCode.NOT_FOUND,
                assertThrows(GeneralException.class, () -> service.get(10L, "CHIP_MAKER")).getCode());
        properties.setEnabled(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "schedulingEnabled", false);
        assertEquals(GeneralErrorCode.NOT_FOUND,
                assertThrows(GeneralException.class, () -> service.get(10L, "CHIP_MAKER")).getCode());
        verifyNoInteractions(jobs, client, quota, plans, recorder);
    }
    @Test void manualCreateCannotOvertakeAutoJobButItsOwnerGeneratesNormally() {
        when(jobs.isPending(10L, Audience.CHIP_MAKER)).thenReturn(true);
        assertEquals(GeneralErrorCode.CONFLICT, assertThrows(GeneralException.class,
                () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER")))).getCode());
        verifyNoInteractions(client, quota, plans, recorder);
        assertFalse(service.createAutomatic(10L, Audience.CHIP_MAKER).cached());
        verify(client).reportInsight(any());
        verify(quota).completeSuccess(any(), any());
    }
    @Test void manualRetryDoesNotBlockAnotherAutomaticAudience() throws Exception {
        var manualStarted = new CountDownLatch(1);
        var releaseManual = new CountDownLatch(1);
        when(jobs.isPending(10L, Audience.IT_INFRA)).thenReturn(true);
        doAnswer(call -> {
            AgentReportInsightRequest request = call.getArgument(0);
            if (request.audiences().equals(List.of("CHIP_MAKER"))) {
                manualStarted.countDown();
                assertTrue(releaseManual.await(10, TimeUnit.SECONDS));
            }
            return responseFor(request);
        }).when(client).reportInsight(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var manual = executor.submit(() -> service.create(10L,
                    new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
            try {
                assertTrue(manualStarted.await(10, TimeUnit.SECONDS));
                var automatic = service.createAutomatic(10L, Audience.IT_INFRA);
                assertFalse(automatic.cached());
                assertEquals(Audience.IT_INFRA, automatic.insights().getFirst().audience());
                assertFalse(manual.isDone());
            } finally { releaseManual.countDown(); }
            assertFalse(manual.get(10, TimeUnit.SECONDS).cached());
        }
        verify(client, times(2)).reportInsight(any());
        verify(quota, times(2)).reserveReportInsight(eq(20L), anyString(), eq(AgentPlan.PAID));
        verify(quota, times(2)).completeSuccess(any(), eq(BigDecimal.ONE));
    }
    @Test void sameAudienceIsRejectedBeforeDuplicateReservationAndGuardIsReleasedAfterFailure() throws Exception {
        var automaticStarted = new CountDownLatch(1);
        var releaseAutomatic = new CountDownLatch(1);
        doAnswer(call -> {
            automaticStarted.countDown();
            assertTrue(releaseAutomatic.await(10, TimeUnit.SECONDS));
            throw new AgentClientException("PROVIDER_UNAVAILABLE", "실패");
        }).when(client).reportInsight(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var automatic = executor.submit(() -> assertThrows(GeneralException.class,
                    () -> service.createAutomatic(10L, Audience.CHIP_MAKER)));
            try {
                assertTrue(automaticStarted.await(10, TimeUnit.SECONDS));
                assertEquals(GeneralErrorCode.CONFLICT, assertThrows(GeneralException.class,
                        () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER")))).getCode());
                assertEquals(GeneralErrorCode.CONFLICT, assertThrows(GeneralException.class,
                        () -> service.createAutomatic(10L, Audience.CHIP_MAKER)).getCode());
                verify(quota, times(1)).reserveReportInsight(eq(20L), anyString(), eq(AgentPlan.PAID));
                verify(client, times(1)).reportInsight(any());
            } finally { releaseAutomatic.countDown(); }
            assertEquals(GeneralErrorCode.INTERNAL_SERVER_ERROR, automatic.get(10, TimeUnit.SECONDS).getCode());
        }
        doAnswer(call -> responseFor(call.getArgument(0))).when(client).reportInsight(any());
        assertFalse(service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))).cached());
        verify(quota, times(2)).reserveReportInsight(eq(20L), anyString(), eq(AgentPlan.PAID));
        verify(quota).completeObservedFailure(any(), any());
        verify(quota).completeSuccess(any(), eq(BigDecimal.ONE));
    }
    @Test void manualRetryRemainsAvailableWhenSchedulerIsDisabled() {
        when(jobs.isPending(10L, Audience.CHIP_MAKER)).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "schedulingEnabled", false);
        assertFalse(service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))).cached());
        verifyNoInteractions(jobs);
        verify(client).reportInsight(any());
    }
    @Test void cachedPerspectivePendingDoesNotBlockAnotherTerminalPerspectiveRetry() {
        when(persistence.findCached(eq(10L), anyString(), anyCollection())).thenReturn(List.of(row(Audience.CHIP_MAKER)));
        when(jobs.isPending(10L, Audience.CHIP_MAKER)).thenReturn(true);
        var result = service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER", "IT_INFRA")));
        assertFalse(result.cached());
        verify(jobs, never()).isPending(10L, Audience.CHIP_MAKER);
        verify(client).reportInsight(argThat(request -> request.audiences().equals(List.of("IT_INFRA"))));
    }
    @Test void allConfirmedErrorEvidenceReturnsReadNotFoundAndCreateConflictWithoutExecutionOrWrites() {
        var reportRepository = mock(NewsReportRepository.class);
        var findingRepository = mock(FindingRepository.class);
        var relevance = mock(TopicRelevancePolicy.class);
        var feedback = mock(FeedbackStore.class);
        var events = mock(ReportEventSnapshotFactory.class);
        var realAssembler = new ReportInsightSnapshotAssembler(reportRepository, findingRepository, relevance, new ObjectMapper(),
                new ReportEventFeedbackProjection(feedback, events));
        var realService = new ReportInsightService(properties, realAssembler, persistence, new ReportInsightValidator(),
                client, quota, plans, recorder, new ReportInsightExecutionRecorder(recorder, quota, persistence), jobs);
        var rejected = new ReportContent.ImportantEvent("오류 사건", "오류 요약", "판단 이유", List.of(50L));
        var report = NewsReport.builder().id(10L).title("일일 리포트").reportScope(ReportScope.DAILY)
                .reflectedFindingIds(List.of(50L)).reportStatus(ReportStatus.GENERATED)
                .structuredContent(new ReportContent(List.of("원본 요약"), List.of(rejected), List.of(), List.of())).build();
        var article = Article.builder().id(150L).title("기사").canonicalUrl("https://example.com/150")
                .topic(Topic.builder().id(1L).name("주제").build()).body("원문").fetchStatus(FetchStatus.FULLTEXT).build();
        var finding = Finding.builder().id(50L).article(article).analysisSource(AnalysisSource.LLM)
                .keyPoints(List.of(new FindingKeyPoint("검증된 주장", List.of(0), "grounded")))
                .sections(List.of(new FindingSection(0, "원문"))).build();
        when(reportRepository.findByIdAndReportStatusNot(10L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(findingRepository.findForReportByIdIn(List.of(50L))).thenReturn(List.of(finding));
        when(relevance.filterFindings(anyList())).thenAnswer(call -> call.getArgument(0));
        var original = realAssembler.assemble(10L);
        when(persistence.findCached(10L, original.inputHash(), List.of(Audience.CHIP_MAKER)))
                .thenReturn(List.of(row(Audience.CHIP_MAKER)));
        assertTrue(realService.get(10L, "CHIP_MAKER").cached());
        clearInvocations(persistence);
        String eventKey = "a".repeat(64);
        when(events.capture(eq(report), any(ReportFindings.Visible.class))).thenReturn(List.of(
                new Item(null, null, null, List.of(), null, null, null, null, null, null, null,
                        new EventContext(eventKey, 0, rejected.title(), rejected.summaryKo(), rejected.significance(),
                                rejected.sourceFindingIds(), List.of(), List.of(), null))));
        when(feedback.eventFeedback(10L)).thenReturn(List.of(new Feedback(1L, null, null, 10L, null,
                Category.SUMMARY_ERROR, "의견", false, "hash", Status.COMPLETED, "CONFIRMED_ERROR", "검토 진단",
                LocalDateTime.of(2026, 10, 1, 12, 0), null, eventKey)));

        var missing = assertThrows(GeneralException.class, () -> realService.get(10L, "CHIP_MAKER"));
        assertEquals(GeneralErrorCode.NOT_FOUND, missing.getCode());
        assertEquals("저장된 리포트 관점 인사이트가 없습니다.", missing.getMessage());
        var rejectedCreate = assertThrows(GeneralException.class,
                () -> realService.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        assertEquals(GeneralErrorCode.CONFLICT, rejectedCreate.getCode());
        assertEquals("이 리포트는 인사이트에 사용할 검증된 근거가 없습니다.", rejectedCreate.getMessage());
        verifyNoInteractions(persistence, client, quota, plans, recorder);
    }
    @Test void readKeepsMissingOrPendingReportNotFoundWithoutExecutionOrWrites() {
        when(assembler.assembleForRead(10L)).thenThrow(new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        var missing = assertThrows(ReportException.class, () -> service.get(10L, "CHIP_MAKER"));
        assertEquals(ReportErrorCode.REPORT_NOT_FOUND, missing.getCode());
        assertEquals("보고서를 찾을 수 없습니다.", missing.getMessage());
        verifyNoInteractions(persistence, client, quota, plans, recorder);
    }
    @Test void cachedCreateUsesNoQuotaAndRecordsOnlyCacheAudit() {
        when(persistence.findCached(10L, "a".repeat(64), List.of(Audience.CHIP_MAKER))).thenReturn(List.of(row(Audience.CHIP_MAKER)));
        assertTrue(service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))).cached());
        verifyNoInteractions(client, quota, plans);
        verify(recorder).recordReportInsightCacheHit(eq(20L), eq(10L), any(), any());
    }
    @Test void invalidAudiencePositiveIdAndMissingEvidenceFailBeforeQuota() {
        assertThrows(AudienceException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("OTHER"))));
        assertThrows(AudienceException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER", "chip_maker"))));
        assertEquals("reportId는 양수여야 합니다.", assertThrows(GeneralException.class, () -> service.get(0L, "CHIP_MAKER")).getMessage());
        when(assembler.assemble(10L)).thenThrow(new GeneralException(com.example.be.global.apiPayload.code.GeneralErrorCode.CONFLICT, "근거 없음"));
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        verifyNoInteractions(client, quota, plans, recorder);
    }
    @Test void overReservationResponseIsObservedFailureBeforePersistenceAndNeverSuccessAudit() {
        doReturn(response(response().insights().getFirst().assessments(), BigDecimal.TWO)).when(client).reportInsight(any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        var failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(quota).completeObservedFailure(any(), failure.capture());
        assertEquals("BUDGET_EXCEEDED", failure.getValue().getCode());
        assertEquals(BigDecimal.TWO, failure.getValue().getUsage().credits());
        assertDiagnostic("USAGE_CHECK", "BUDGET_EXCEEDED", AgentClientException.TimeoutPhase.NONE, true);
        verify(persistence, never()).saveGenerated(any(), any());
        verify(recorder, never()).recordReportInsightSuccess(any(), any(), any(), any(), any());
    }
    @Test void sourceRaceAndSettlementErrorsPreserveObservedUsageWithoutSuccessAudit() {
        doThrow(new IllegalStateException("근거 변경")).when(persistence).saveGenerated(any(), any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        verify(quota).completeObservedFailure(any(), argThat(failure -> failure.getUsage().credits().equals(BigDecimal.ONE)));
        verify(recorder, never()).recordReportInsightSuccess(any(), any(), any(), any(), any());
    }
    @Test void knownSchemaRepairFailureChargesAreNotReleasedThroughGenericFailurePath() {
        var failure = new AgentClientException("SCHEMA_VIOLATION", "repair 실패", null,
                new AgentClientException.Usage(100L, 50L, BigDecimal.ONE, BigDecimal.ONE));
        doThrow(failure).when(client).reportInsight(any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));
        verify(quota).completeObservedFailure(any(), same(failure));
        verify(quota, never()).completeFailure(any(), anyString());
    }
    @Test void agentFailureLogsBoundedMetadataAndKeepsPublicErrorAndUsageAccounting() {
        var failure = new AgentClientException("SCHEMA_VIOLATION", "private-response-payload",
                new IllegalStateException("private-cause-payload"),
                new AgentClientException.Usage(100L, 50L, BigDecimal.ONE, BigDecimal.ONE),
                AgentClientException.TimeoutPhase.NONE, null,
                new AgentClientException.ValidationFailure("REDUCE-001", 2, "OutputValidationError", 2,
                        List.of("report_fact_mismatch", "private-kind\nforged-log")));
        doThrow(failure).when(client).reportInsight(any());

        var publicError = assertThrows(GeneralException.class,
                () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));

        assertEquals(GeneralErrorCode.INTERNAL_SERVER_ERROR, publicError.getCode());
        assertEquals("리포트 관점 인사이트 생성에 실패했습니다.", publicError.getMessage());
        assertNull(publicError.getResult());
        assertNull(publicError.getCause());
        assertDiagnostic("AGENT_CALL", "SCHEMA_VIOLATION", AgentClientException.TimeoutPhase.NONE, true);
        var arguments = logs.list.stream().filter(event -> event.getMessage().contains("failureCode={}"))
                .findFirst().orElseThrow().getArgumentArray();
        assertEquals("REDUCE-001", arguments[6]);
        assertEquals(2, arguments[7]);
        assertEquals("OutputValidationError", arguments[8]);
        assertEquals(2, arguments[9]);
        assertEquals(List.of("report_fact_mismatch"), arguments[10]);
        verify(recorder).recordReportInsightFailure(eq(20L), any(), same(failure), any(), any());
        verify(quota).completeObservedFailure(any(), same(failure));
    }
    @ParameterizedTest @NullSource @ValueSource(strings = {"private-code-payload\nforged-log", "PRIVATE_CODE_PAYLOAD"})
    void unrecognizedAgentCodesCannotInjectDiagnostics(String code) {
        doThrow(new AgentClientException(code, "private-response-payload", null, null,
                AgentClientException.TimeoutPhase.READ)).when(client).reportInsight(any());

        assertThrows(GeneralException.class,
                () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));

        assertDiagnostic("AGENT_CALL", "UNKNOWN", AgentClientException.TimeoutPhase.READ, true);
    }
    @Test void invalidReturnedResponseIsDistinguishedFromAgentRejection() {
        doReturn(response(List.of(assessment(50, 3, 2, 2)), BigDecimal.ONE)).when(client).reportInsight(any());

        assertThrows(GeneralException.class,
                () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));

        assertDiagnostic("RESPONSE_VALIDATION", "SCHEMA_VIOLATION", AgentClientException.TimeoutPhase.NONE, true);
        verify(persistence, never()).saveGenerated(any(), any());
        verify(quota).completeObservedFailure(any(), argThat(failure -> BigDecimal.ONE.equals(failure.getUsage().credits())));
    }
    @Test void persistenceFailureIsDistinguishedWithoutLoggingDatabaseDetails() {
        doThrow(new IllegalStateException("private-database-payload")).when(persistence).saveGenerated(any(), any());

        assertThrows(GeneralException.class,
                () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));

        assertDiagnostic("PERSISTENCE", "PERSISTENCE_FAILED", AgentClientException.TimeoutPhase.NONE, true);
        verify(quota).completeObservedFailure(any(), argThat(failure -> BigDecimal.ONE.equals(failure.getUsage().credits())));
    }
    @Test void failedAuditStillLogsOriginalFailureAndPreservesReservation() {
        doThrow(new AgentClientException("PROVIDER_UNAVAILABLE", "private-response-payload"))
                .when(client).reportInsight(any());
        doThrow(new IllegalStateException("private-audit-payload"))
                .when(recorder).recordReportInsightFailure(any(), any(), any(), any(), any());

        assertThrows(GeneralException.class,
                () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER"))));

        assertDiagnostic("AGENT_CALL", "PROVIDER_UNAVAILABLE", AgentClientException.TimeoutPhase.NONE, false);
        verify(quota, never()).completeObservedFailure(any(), any());
        verify(quota, never()).completeFailure(any(), anyString());
    }
    @Test void earlierPerspectiveRemainsSavedWhenLaterPerspectiveFails() {
        doReturn(response()).doThrow(new AgentClientException("PROVIDER_UNAVAILABLE", "실패")).when(client).reportInsight(any());
        assertThrows(GeneralException.class, () -> service.create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER", "IT_INFRA"))));
        verify(persistence, times(1)).saveGenerated(any(), any());
        verify(quota, times(1)).completeSuccess(any(), any());
        verify(quota, times(1)).completeObservedFailure(any(), any());
    }
    private AgentReportInsightResponse responseFor(AgentReportInsightRequest request) {
        var original = response();
        var insight = original.insights().getFirst();
        return new AgentReportInsightResponse(List.of(new AgentReportInsightResponse.Insight(request.audiences().getFirst(),
                insight.headline(), insight.overview(), insight.assessments(), insight.implications(), insight.watchItems())), original.meta());
    }
    private void assertDiagnostic(String phase, String code, AgentClientException.TimeoutPhase timeout, boolean auditPersisted) {
        var diagnostics = logs.list.stream().filter(event -> event.getMessage().contains("failureCode={}")).toList();
        assertEquals(1, diagnostics.size());
        var arguments = diagnostics.getFirst().getArgumentArray();
        assertEquals(10L, arguments[0]);
        assertEquals(Audience.CHIP_MAKER, arguments[1]);
        assertEquals(phase, arguments[2].toString());
        assertEquals(code, arguments[3]);
        assertEquals(timeout, arguments[4]);
        assertEquals(auditPersisted, arguments[5]);
        for (var event : logs.list) {
            assertNull(event.getThrowableProxy());
            assertFalse(event.getFormattedMessage().toLowerCase(Locale.ROOT).contains("private"));
            assertFalse(event.getFormattedMessage().contains("forged-log"));
        }
    }
    private NewsReportInsight row(Audience audience) { return NewsReportInsight.builder().reportId(10L).audience(audience).inputHash("a".repeat(64)).build(); }
    private ReportInsightDTO.AudienceInsight dto(Audience audience) { return new ReportInsightDTO.AudienceInsight(audience,
            "제목", "high", List.of(), List.of(), List.of(), List.of(), List.of(), "openai", "model", OffsetDateTime.now()); }
}
