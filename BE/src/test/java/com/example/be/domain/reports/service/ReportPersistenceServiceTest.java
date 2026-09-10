package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
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
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final ReportPersistenceService service =
            new ReportPersistenceService(runRepository, reportRepository,
                    notificationAutomation, observationRepository, findingRepository);

    @ParameterizedTest
    @EnumSource(value = RunItemStatus.class, names = {"SUCCESS", "SKIPPED"})
    void skipsHealthyUnchangedOrNotModifiedCollectionWithoutReservingReport(RunItemStatus itemStatus) {
        CollectionRun run = runWithItem(itemStatus);
        if (itemStatus == RunItemStatus.SUCCESS) {
            run.getItems().getFirst().recordResult(itemStatus, 12, 0, 0);
        }
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));

        var reservation = service.reserve(42L, LocalDateTime.now(), false);

        assertFalse(reservation.owner());
        assertNull(reservation.reportId());
        assertNull(run.getReportId());
        verify(reportRepository, never()).save(any());
        verifyNoInteractions(notificationAutomation);
    }

    @Test
    void skipsSuccessfulCollectionThatReturnsNoArticles() {
        CollectionRun run = runWithItem(RunItemStatus.SUCCESS);
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));

        assertNull(service.reserve(42L, LocalDateTime.now(), false).reportId());

        verify(reportRepository, never()).save(any());
    }

    @Test
    void retainsReportForChangedObservationsBeforeRunCountersAreFinalized() {
        CollectionRun run = runWithItem(RunItemStatus.SUCCESS);
        prepareReservation(run);
        when(observationRepository.existsByRunIdAndChangeTypeIn(
                42L, List.of(ChangeType.NEW, ChangeType.UPDATED))).thenReturn(true);

        var reservation = service.reserve(42L, LocalDateTime.now(), false);

        assertTrue(reservation.owner());
        assertEquals(17L, reservation.reportId());
        assertEquals(0, run.getNewCount());
        assertEquals(0, run.getUpdatedCount());
        assertEquals(0, run.getItems().getFirst().getNewCount());
        assertEquals(0, run.getItems().getFirst().getUpdatedCount());
        assertEquals(17L, run.getReportId());
    }

    @Test
    void retainsReportWhenUnchangedArticleHasNewlyAvailableFullText() {
        prepareReservation(runWithItem(RunItemStatus.SUCCESS));

        var reservation = service.reserve(42L, LocalDateTime.now(), true);

        assertTrue(reservation.owner());
        assertEquals(17L, reservation.reportId());
    }

    @Test
    void retainsReportForCurrentRunFindingsWithoutChangedObservations() {
        prepareReservation(runWithItem(RunItemStatus.SUCCESS));
        when(findingRepository.existsByRunId(42L)).thenReturn(true);

        var reservation = service.reserve(42L, LocalDateTime.now(), false);

        assertTrue(reservation.owner());
        assertEquals(17L, reservation.reportId());
    }

    @ParameterizedTest
    @EnumSource(value = RunItemStatus.class, names = {"SUCCESS", "SKIPPED"}, mode = EnumSource.Mode.EXCLUDE)
    void retainsDiagnosticReportWhenCollectionDidNotCompleteCleanly(RunItemStatus itemStatus) {
        prepareReservation(runWithItem(itemStatus));

        var reservation = service.reserve(42L, LocalDateTime.now(), false);

        assertTrue(reservation.owner());
        assertEquals(17L, reservation.reportId());
    }

    @Test
    void retainsDiagnosticReportForWarningsEvenWhenItemsWereSkipped() {
        CollectionRun run = runWithItem(RunItemStatus.SKIPPED);
        run.addWarning(CollectionRunWarning.builder()
                .code(CollectionRunWarning.CODE_ROBOTS_DISALLOWED).message("수집 차단").build());
        prepareReservation(run);

        var reservation = service.reserve(42L, LocalDateTime.now(), false);

        assertTrue(reservation.owner());
        assertEquals(17L, reservation.reportId());
    }

    @Test
    void doesNotTreatRunWithoutCollectionItemsAsHealthyUnchangedCollection() {
        prepareReservation(CollectionRun.builder().id(42L).build());

        assertTrue(service.reserve(42L, LocalDateTime.now(), false).owner());
    }

    private CollectionRun runWithItem(RunItemStatus status) {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        run.addItem(CollectionRunItem.builder().status(status).build());
        return run;
    }

    private void prepareReservation(CollectionRun run) {
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.save(any())).thenReturn(NewsReport.builder().id(17L).build());
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

        ReportPersistenceService.Reservation reservation = service.reserve(42L, generatedAt, false);

        assertEquals(17L, reservation.reportId());
        assertTrue(reservation.owner());
        ArgumentCaptor<NewsReport> captor = ArgumentCaptor.forClass(NewsReport.class);
        verify(reportRepository).save(captor.capture());
        assertEquals(ReportStatus.PENDING, captor.getValue().getReportStatus());
        verify(run).attachReport(17L);
    }

    @Test
    void completesReservedReportWithAgentMetadata() {
        LocalDateTime generatedAt = LocalDateTime.of(2026, 8, 21, 9, 0);
        NewsReport report = NewsReport.builder()
                .id(17L)
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

        assertEquals("# 보고서", report.getMarkdownBody());
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

        ReportPersistenceService.Reservation reservation = service.reserve(42L, LocalDateTime.now(), false);

        assertEquals(17L, reservation.reportId());
        assertFalse(reservation.owner());
        verify(reportRepository, never()).save(any());
        assertEquals(17L, run.getReportId());
        verifyNoInteractions(observationRepository, findingRepository);
    }

    @Test
    void describesMissingRunInReservationFailure() {
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.reserve(42L, LocalDateTime.now(), false));

        assertEquals("보고서를 만들 수집 실행이 없습니다. runId=42", exception.getMessage());
    }
}
