package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.notifications.service.ReportNotificationAutomationService;
import com.example.be.domain.reports.comparison.ReportCompleted;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportCreationServiceTest {

    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final AgentReportOrchestrator reportOrchestrator = mock(AgentReportOrchestrator.class);
    private final ReportPersistenceService persistenceService = mock(ReportPersistenceService.class);
    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final ReportCreationService service = new ReportCreationService(
            runRepository, findingRepository, reportOrchestrator, persistenceService, reportRepository);

    @Test
    void describesMissingRunInGenerationFailure() {
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReportPersistenceService.Reservation(17L, true));
        when(runRepository.findReportContextById(42L)).thenReturn(java.util.Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.generate(42L));

        assertEquals("보고서를 만들 수집 실행이 없습니다. runId=42", exception.getMessage());
    }

    @Test
    void createsOneReportAndConnectsItToLockedRun() {
        CollectionRun run = mock(CollectionRun.class);
        Finding finding = mock(Finding.class);
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReportPersistenceService.Reservation(17L, true));
        when(runRepository.findReportContextById(42L)).thenReturn(java.util.Optional.of(run));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));
        when(reportOrchestrator.generate(
                org.mockito.ArgumentMatchers.eq(run),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReportDocument("보고서", "# 보고서", "stub-report-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(17L);

        Long reportId = service.generate(42L);

        assertEquals(17L, reportId);
        verify(reportOrchestrator).generate(
                org.mockito.ArgumentMatchers.eq(run),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        verify(persistenceService).complete(
                org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runWithoutNewArticlesReturnsNoReportWithoutGeneratingOrCompletingOne() {
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReportPersistenceService.Reservation(null, false));

        assertNull(service.generate(42L));

        verifyNoInteractions(runRepository, findingRepository, reportOrchestrator);
        verify(persistenceService, never()).complete(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusesExistingReportWithoutGeneratingAgain() {
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReportPersistenceService.Reservation(17L, false));

        assertEquals(17L, service.generate(42L));

        verify(reportOrchestrator, never()).generate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        verify(runRepository, never()).findReportContextById(42L);
    }

    @Test
    void regeneratesExistingReportFromStoredFindings() {
        CollectionRun run = mock(CollectionRun.class);
        Finding finding = mock(Finding.class);
        NewsReport report = mock(NewsReport.class);
        when(report.getId()).thenReturn(17L);
        when(reportRepository.findByRunId(42L)).thenReturn(java.util.Optional.of(report));
        when(runRepository.findReportContextById(42L)).thenReturn(java.util.Optional.of(run));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));
        ReportDocument document = new ReportDocument("복구 보고서", "# 복구 보고서", "stub-report-v1");
        when(reportOrchestrator.generate(
                org.mockito.ArgumentMatchers.eq(run),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(document);
        when(persistenceService.replace(
                org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq(document),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(17L);

        assertEquals(17L, service.recover(42L));

        verify(persistenceService).replace(
                org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq(document),
                org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingReportCompletionOnlyDeliversAndPublishesOutsideRecovery(boolean recovery) {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).reportStatus(ReportStatus.PENDING).build();
        var observations = mock(CollectionRunArticleRepository.class);
        var notifications = mock(ReportNotificationAutomationService.class);
        var events = mock(ApplicationEventPublisher.class);
        var persistence = new ReportPersistenceService(
                runRepository, reportRepository, notifications, observations, events);
        var creation = new ReportCreationService(
                runRepository, findingRepository, reportOrchestrator, persistence, reportRepository);
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(runRepository.findReportContextById(42L)).thenReturn(Optional.of(run));
        when(observations.existsByRunIdAndChangeTypeIn(42L, List.of(ChangeType.NEW))).thenReturn(true);
        when(reportRepository.save(any())).thenReturn(report);
        when(reportRepository.findRunIdById(17L)).thenReturn(Optional.of(42L));
        when(reportRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of());
        var document = new ReportDocument("완료 보고서", "# 완료 보고서", "configured-model");
        when(reportOrchestrator.generate(eq(run), eq(List.of()), any())).thenReturn(document);

        assertEquals(17L, recovery ? creation.recover(42L) : creation.generate(42L));

        assertEquals(17L, run.getReportId());
        assertEquals("# 완료 보고서", report.getMarkdownBody());
        assertEquals(document.status(), report.getReportStatus());
        verify(reportRepository).save(any());
        if (recovery) {
            verifyNoInteractions(notifications, events);
        } else {
            verify(notifications).enqueueCompletedReport(report);
            verify(events).publishEvent(new ReportCompleted(17L));
        }
    }

    @Test
    void recoverySkipsMissingReportWithoutNewArticles() {
        when(persistenceService.reserve(eq(42L), any()))
                .thenReturn(new ReportPersistenceService.Reservation(null, false));

        assertNull(service.recover(42L));

        verifyNoInteractions(runRepository, findingRepository, reportOrchestrator);
        verify(persistenceService, never()).replace(any(), any(), any());
        verify(persistenceService, never()).complete(any(), any(), any());
    }

    @Test
    void recoveryDoesNotTakeOverAConcurrentReservation() {
        when(persistenceService.reserve(eq(42L), any()))
                .thenReturn(new ReportPersistenceService.Reservation(17L, false));

        assertEquals(17L, service.recover(42L));

        verifyNoInteractions(runRepository, findingRepository, reportOrchestrator);
        verify(persistenceService, never()).replace(any(), any(), any());
    }
}
