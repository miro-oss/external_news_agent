package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
