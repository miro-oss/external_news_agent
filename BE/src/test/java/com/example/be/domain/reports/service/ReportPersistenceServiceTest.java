package com.example.be.domain.reports.service;

import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.notifications.service.ReportNotificationAutomationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportPersistenceServiceTest {

    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final ReportNotificationAutomationService notificationAutomation =
            mock(ReportNotificationAutomationService.class);
    private final CollectionRunArticleRepository observationRepository = mock(CollectionRunArticleRepository.class);
    private final ReportPersistenceService service =
            new ReportPersistenceService(runRepository, reportRepository,
                    notificationAutomation, observationRepository,
                    mock(org.springframework.context.ApplicationEventPublisher.class));

    @org.junit.jupiter.api.Test
    void lateRecoveryMustNotInvalidateTheAlreadyCompletedOriginalSnapshot() {
        NewsReport report = NewsReport.builder().id(101L).reportStatus(ReportStatus.GENERATED).build();
        when(reportRepository.findByIdForUpdate(101L)).thenReturn(java.util.Optional.of(report));
        service.completeRecovered(101L, new ReportDocument("복구", "실시간 근거", "fallback"), LocalDateTime.now());
        org.junit.jupiter.api.Assertions.assertTrue(report.isComparisonInputUsable());
        verifyNoInteractions(notificationAutomation);
    }

    @ParameterizedTest
    @EnumSource(RunItemStatus.class)
    void skipsRunWithoutNewArticlesRegardlessOfCollectionStatus(RunItemStatus itemStatus) {
        CollectionRun run = runWithItem(itemStatus);
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));

        var reservation = service.reserve(42L, LocalDateTime.now());

        assertFalse(reservation.owner());
        assertNull(reservation.reportId());
        assertNull(run.getReportId());
        verify(reportRepository, never()).save(any());
        verifyNoInteractions(notificationAutomation);
    }

    @Test
    void skipsUpdatedOnlyCollectionWithWarnings() {
        CollectionRun run = runWithItem(RunItemStatus.SUCCESS);
        run.getItems().getFirst().recordResult(RunItemStatus.SUCCESS, 167, 0, 4);
        run.addWarning(CollectionRunWarning.builder()
                .code(CollectionRunWarning.CODE_LLM_QUOTA_EXHAUSTED).message("분석 예산 부족").build());
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(observationRepository.existsByRunIdAndChangeTypeIn(
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(invocation -> invocation.<java.util.Collection<ChangeType>>getArgument(1)
                        .contains(ChangeType.UPDATED));

        var reservation = service.reserve(42L, LocalDateTime.now());

        assertFalse(reservation.owner());
        assertNull(reservation.reportId());
        verify(reportRepository, never()).save(any());
        verifyNoInteractions(notificationAutomation);
    }

    @Test
    void retainsReportForNewObservationBeforeRunCountersAreFinalized() {
        CollectionRun run = runWithItem(RunItemStatus.SUCCESS);
        prepareReservation(run);

        var reservation = service.reserve(42L, LocalDateTime.now());

        assertTrue(reservation.owner());
        assertEquals(17L, reservation.reportId());
        assertEquals(0, run.getNewCount());
        assertEquals(0, run.getUpdatedCount());
        assertEquals(0, run.getItems().getFirst().getNewCount());
        assertEquals(0, run.getItems().getFirst().getUpdatedCount());
        assertEquals(17L, run.getReportId());
    }

    @Test
    void retainsReportForNewObservationDespiteFailedSourceAndWarnings() {
        CollectionRun run = runWithItem(RunItemStatus.SUCCESS);
        run.addItem(CollectionRunItem.builder().status(RunItemStatus.FAILED).build());
        run.addWarning(CollectionRunWarning.builder()
                .code(CollectionRunWarning.CODE_FEED_UNREADABLE).message("일부 소스 수집 실패").build());
        prepareReservation(run);

        var reservation = service.reserve(42L, LocalDateTime.now());

        assertTrue(reservation.owner());
        assertEquals(17L, reservation.reportId());
    }

    @Test
    void skipsRunWithoutCollectionItemsOrNewObservations() {
        when(runRepository.findByIdForUpdate(42L))
                .thenReturn(Optional.of(CollectionRun.builder().id(42L).build()));

        assertNull(service.reserve(42L, LocalDateTime.now()).reportId());

        verify(reportRepository, never()).save(any());
    }

    private CollectionRun runWithItem(RunItemStatus status) {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        run.addItem(CollectionRunItem.builder().status(status).build());
        return run;
    }

    private void prepareReservation(CollectionRun run) {
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.save(any())).thenReturn(NewsReport.builder().id(17L).build());
        when(observationRepository.existsByRunIdAndChangeTypeIn(42L, List.of(ChangeType.NEW))).thenReturn(true);
    }

    @Test
    void completedRunAndDailyReportsCannotBeOverwrittenByLateCompletion() {
        for (var scope : com.example.be.domain.reports.entity.ReportScope.values()) {
            NewsReport report = NewsReport.builder().id(17L).reportScope(scope).title("완료된 보고서")
                    .reportStatus(ReportStatus.GENERATED).markdownBody("저장된 본문").build();
            when(reportRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(report));
            assertEquals(17L, service.complete(17L, new ReportDocument("뒤늦은 결과", "대체 본문", "fallback"),
                    LocalDateTime.now()));
            assertEquals("저장된 본문", report.getMarkdownBody());
            assertEquals(ReportStatus.GENERATED, report.getReportStatus());
        }
    }

    @Test
    void reservesPendingReportBeforeAgentCall() {
        CollectionRun run = mock(CollectionRun.class);
        LocalDateTime generatedAt = LocalDateTime.of(2026, 8, 21, 9, 0);
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenReturn(NewsReport.builder().id(17L).build());
        when(observationRepository.existsByRunIdAndChangeTypeIn(42L, List.of(ChangeType.NEW))).thenReturn(true);

        ReportPersistenceService.Reservation reservation = service.reserve(42L, generatedAt);

        assertEquals(17L, reservation.reportId());
        assertTrue(reservation.owner());
        ArgumentCaptor<NewsReport> captor = ArgumentCaptor.forClass(NewsReport.class);
        verify(reportRepository).save(captor.capture());
        assertEquals(ReportStatus.PENDING, captor.getValue().getReportStatus());
        verify(run).attachReport(17L);
    }

    @ParameterizedTest
    @EnumSource(ReportScope.class)
    void completesReservedReportWithAgentMetadata(ReportScope scope) {
        LocalDateTime generatedAt = LocalDateTime.of(2026, 8, 21, 9, 0);
        NewsReport report = NewsReport.builder()
                .id(17L)
                .reportScope(scope)
                .reportDate(scope == ReportScope.DAILY ? generatedAt.toLocalDate().minusDays(1) : null)
                .title("보고서 생성 중")
                .markdownBody("생성 중")
                .modelName("pending-report-v1")
                .reportStatus(ReportStatus.PENDING)
                .generatedAt(generatedAt)
                .build();
        ReportDocument document = new ReportDocument(
                "보고서", "# 보고서", "configured-model", "report.ko.v1", "gemini",
                100L, 20L, new BigDecimal("0.001"), BigDecimal.ZERO, ReportStatus.GENERATED,
                java.util.List.of(501L), java.util.List.of(502L));
        when(reportRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(report));

        assertEquals(17L, service.complete(17L, document, generatedAt));

        String expectedTitle = scope == ReportScope.DAILY ? "2026-08-20 일일 통합 뉴스 보고서" : "보고서";
        assertEquals(expectedTitle, report.getTitle());
        assertEquals("# " + expectedTitle, report.getMarkdownBody());
        assertEquals("report.ko.v1", report.getPromptVersion());
        assertEquals("gemini", report.getLlmProvider());
        assertEquals(100L, report.getInputTokens());
        assertEquals(ReportStatus.GENERATED, report.getReportStatus());
        assertTrue(report.isCoverageRecorded());
        assertEquals(java.util.List.of(501L), report.getReflectedFindingIds());
        assertEquals(java.util.List.of(502L), report.getExcludedFindingIds());
    }

    @Test
    void concurrentCallerReusesReservationWithoutCallingAgent() {
        CollectionRun run = runWithItem(RunItemStatus.SUCCESS);
        NewsReport existing = NewsReport.builder()
                .id(17L)
                .run(run)
                .reportStatus(ReportStatus.PENDING)
                .build();
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.of(existing));

        ReportPersistenceService.Reservation reservation = service.reserve(42L, LocalDateTime.now());

        assertEquals(17L, reservation.reportId());
        assertFalse(reservation.owner());
        verify(reportRepository, never()).save(any());
        assertEquals(17L, run.getReportId());
        verifyNoInteractions(observationRepository);
    }

    @Test
    void describesMissingRunInReservationFailure() {
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.reserve(42L, LocalDateTime.now()));

        assertEquals("보고서를 만들 수집 실행이 없습니다. runId=42", exception.getMessage());
    }
}
