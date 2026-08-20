package com.example.be.domain.reports.service;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportPersistenceServiceTest {

    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final ReportPersistenceService service =
            new ReportPersistenceService(runRepository, reportRepository);

    @Test
    void mapsAgentMetadataAndConnectsReportInsideLockedSection() {
        CollectionRun run = mock(CollectionRun.class);
        LocalDateTime generatedAt = LocalDateTime.of(2026, 8, 21, 9, 0);
        ReportDocument document = new ReportDocument(
                "보고서",
                "# 보고서",
                "configured-model",
                "report.ko.v1",
                "gemini",
                100L,
                20L,
                new BigDecimal("0.001"),
                BigDecimal.ZERO,
                ReportStatus.GENERATED);
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenReturn(NewsReport.builder().id(17L).build());

        assertEquals(17L, service.saveIfAbsent(42L, document, generatedAt));

        ArgumentCaptor<NewsReport> captor = ArgumentCaptor.forClass(NewsReport.class);
        verify(reportRepository).save(captor.capture());
        NewsReport saved = captor.getValue();
        assertEquals("# 보고서", saved.getMarkdownBody());
        assertEquals("report.ko.v1", saved.getPromptVersion());
        assertEquals("gemini", saved.getLlmProvider());
        assertEquals(100L, saved.getInputTokens());
        assertEquals(ReportStatus.GENERATED, saved.getReportStatus());
        verify(run).attachReport(17L);
    }

    @Test
    void reusesReportCreatedByConcurrentCaller() {
        CollectionRun run = mock(CollectionRun.class);
        NewsReport existing = NewsReport.builder().id(17L).run(run).build();
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.of(existing));

        assertEquals(17L, service.saveIfAbsent(
                42L,
                new ReportDocument("보고서", "# 보고서", "fallback"),
                LocalDateTime.now()));

        verify(reportRepository, never()).save(any());
        verify(run).attachReport(17L);
    }

    @Test
    void describesMissingRunInPersistenceFailure() {
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.empty());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.saveIfAbsent(
                        42L,
                        new ReportDocument("보고서", "# 보고서", "fallback"),
                        LocalDateTime.now()));

        assertEquals("보고서를 만들 수집 실행이 없습니다. runId=42", exception.getMessage());
    }
}
