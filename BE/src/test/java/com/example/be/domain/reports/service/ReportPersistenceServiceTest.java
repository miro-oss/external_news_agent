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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void reservesPendingReportBeforeAgentCall() {
        CollectionRun run = mock(CollectionRun.class);
        LocalDateTime generatedAt = LocalDateTime.of(2026, 8, 21, 9, 0);
        when(runRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(reportRepository.findByRunId(42L)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenReturn(NewsReport.builder().id(17L).build());

        ReportPersistenceService.Reservation reservation = service.reserve(42L, generatedAt);

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
        CollectionRun run = mock(CollectionRun.class);
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
        verify(run).attachReport(17L);
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
