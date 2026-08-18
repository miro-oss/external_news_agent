package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private final ReportGenerator reportGenerator = mock(ReportGenerator.class);
    private final ReportCreationService service = new ReportCreationService(
            runRepository, findingRepository, reportRepository, reportGenerator);

    @Test
    void describesMissingRunInGenerationFailure() {
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.generate(42L));

        assertEquals("보고서를 만들 수집 실행이 없습니다. runId=42", exception.getMessage());
    }

    @Test
    void createsOneReportAndConnectsItToLockedRun() {
        CollectionRun run = mock(CollectionRun.class);
        Finding finding = mock(Finding.class);
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.empty());
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));
        when(reportGenerator.generate(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReportDocument("보고서", "# 보고서", "stub-report-v1"));
        when(reportRepository.save(org.mockito.ArgumentMatchers.any())).thenReturn(NewsReport.builder().id(17L).build());

        Long reportId = service.generate(42L);

        assertEquals(17L, reportId);
        ArgumentCaptor<NewsReport> captor = ArgumentCaptor.forClass(NewsReport.class);
        verify(reportRepository).save(captor.capture());
        assertEquals(run, captor.getValue().getRun());
        assertEquals("# 보고서", captor.getValue().getMarkdownBody());
        verify(run).attachReport(17L);
    }

    @Test
    void reusesExistingReportWithoutGeneratingAgain() {
        CollectionRun run = mock(CollectionRun.class);
        NewsReport existing = NewsReport.builder().id(17L).run(run).build();
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.of(existing));

        assertEquals(17L, service.generate(42L));

        verify(run).attachReport(17L);
        verify(reportGenerator, never()).generate(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        verify(reportRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
