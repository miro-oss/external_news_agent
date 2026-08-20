package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.agent.service.AgentReportOrchestrator;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportCreationServiceTest {

    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final AgentReportOrchestrator reportOrchestrator = mock(AgentReportOrchestrator.class);
    private final ReportPersistenceService persistenceService = mock(ReportPersistenceService.class);
    private final ReportCreationService service = new ReportCreationService(
            runRepository, findingRepository, reportRepository, reportOrchestrator, persistenceService);

    @Test
    void describesMissingRunInGenerationFailure() {
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.empty());
        when(runRepository.findReportContextById(42L)).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.generate(42L));

        assertEquals("보고서를 만들 수집 실행이 없습니다. runId=42", exception.getMessage());
    }

    @Test
    void createsOneReportAndConnectsItToLockedRun() {
        CollectionRun run = mock(CollectionRun.class);
        Finding finding = mock(Finding.class);
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.empty());
        when(runRepository.findReportContextById(42L)).thenReturn(Optional.of(run));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));
        when(reportOrchestrator.generate(
                org.mockito.ArgumentMatchers.eq(run),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReportDocument("보고서", "# 보고서", "stub-report-v1"));
        when(persistenceService.saveIfAbsent(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(17L);

        Long reportId = service.generate(42L);

        assertEquals(17L, reportId);
        verify(reportOrchestrator).generate(
                org.mockito.ArgumentMatchers.eq(run),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        verify(persistenceService).saveIfAbsent(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusesExistingReportWithoutGeneratingAgain() {
        CollectionRun run = mock(CollectionRun.class);
        NewsReport existing = NewsReport.builder().id(17L).run(run).build();
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.of(existing));
        when(persistenceService.attachExisting(42L, 17L)).thenReturn(17L);

        assertEquals(17L, service.generate(42L));

        verify(persistenceService).attachExisting(42L, 17L);
        verify(reportOrchestrator, never()).generate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        verify(runRepository, never()).findReportContextById(42L);
    }
}
