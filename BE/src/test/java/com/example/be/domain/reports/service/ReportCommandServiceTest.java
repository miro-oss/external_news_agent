package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ReportCommandServiceTest {

    private final NewsReportRepository repository = mock(NewsReportRepository.class);
    private final ReportCommandService service = new ReportCommandService(repository);

    @Test
    void hidesCompletedReportWithoutDeletingStoredContent() {
        NewsReport report = NewsReport.builder().id(17L).title("수집 보고서")
                .markdownBody("저장된 본문").reportStatus(ReportStatus.FALLBACK).build();
        when(repository.findByIdForUpdate(17L)).thenReturn(Optional.of(report));

        var result = service.deleteReport(17L);

        assertEquals(17L, result.id());
        assertTrue(result.deleted());
        assertNotNull(report.getDeletedAt());
        assertEquals("저장된 본문", report.getMarkdownBody());
        assertEquals(ReportStatus.FALLBACK, report.getReportStatus());
        org.mockito.Mockito.verify(repository).findByIdForUpdate(17L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void retryKeepsOriginalDeletionTime() {
        LocalDateTime deletedAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        NewsReport report = NewsReport.builder().id(17L).deletedAt(deletedAt).build();
        when(repository.findByIdForUpdate(17L)).thenReturn(Optional.of(report));

        assertTrue(service.deleteReport(17L).deleted());
        assertEquals(deletedAt, report.getDeletedAt());
    }

    @Test
    void missingAndPendingReportsUseTheExistingNotFoundError() {
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.empty());
        when(repository.findByIdForUpdate(17L)).thenReturn(Optional.of(
                NewsReport.builder().id(17L).reportStatus(ReportStatus.PENDING).build()));

        assertThrows(ReportException.class, () -> service.deleteReport(99L));
        assertThrows(ReportException.class, () -> service.deleteReport(17L));
    }
}
