package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private final ReportCreationService service = new ReportCreationService(
            runRepository, findingRepository, reportOrchestrator, persistenceService);

    @Test
    void describesMissingRunInGenerationFailure() {
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new ReportPersistenceService.Reservation(17L, true));
        when(runRepository.findReportContextById(42L)).thenReturn(java.util.Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.generate(42L, false));

        assertEquals("보고서를 만들 수집 실행이 없습니다. runId=42", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsOneReportAndConnectsItToLockedRun(boolean hasRefreshedArticles) {
        CollectionRun run = mock(CollectionRun.class);
        Finding finding = mock(Finding.class);
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(hasRefreshedArticles)))
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

        Long reportId = service.generate(42L, hasRefreshedArticles);

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
    void unchangedRunReturnsNoReportWithoutGeneratingOrCompletingOne() {
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new ReportPersistenceService.Reservation(null, false));

        assertNull(service.generate(42L, false));

        verifyNoInteractions(runRepository, findingRepository, reportOrchestrator);
        verify(persistenceService, never()).complete(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusesExistingReportWithoutGeneratingAgain() {
        when(persistenceService.reserve(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new ReportPersistenceService.Reservation(17L, false));

        assertEquals(17L, service.generate(42L, false));

        verify(reportOrchestrator, never()).generate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        verify(runRepository, never()).findReportContextById(42L);
    }
}
