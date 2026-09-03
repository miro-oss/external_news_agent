package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyReportCreationServiceTest {
    private final DailyReportJdbcRepository daily = mock(DailyReportJdbcRepository.class);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final DailyReportSelector selector = mock(DailyReportSelector.class);
    private final DailyReportPersistenceService reservation = mock(DailyReportPersistenceService.class);
    private final ReportPersistenceService persistence = mock(ReportPersistenceService.class);
    private final AgentReportOrchestrator orchestrator = mock(AgentReportOrchestrator.class);
    private final ReportGenerator fallback = mock(ReportGenerator.class);
    private final DailyReportCreationService service = new DailyReportCreationService(
            daily, reports, findings, selector, reservation, persistence, orchestrator, fallback);
    private final LocalDate date = LocalDate.of(2026, 8, 1);

    @Test
    void existingOrContendedReservationDoesNotInvokeAgentAgain() {
        when(reports.findByReportScopeAndReportDate(ReportScope.DAILY, date))
                .thenReturn(Optional.of(NewsReport.builder().id(77L).build()));
        assertEquals(77L, service.generate(date));
        verifyNoInteractions(selector, orchestrator, daily);

        when(reports.findByReportScopeAndReportDate(ReportScope.DAILY, date)).thenReturn(Optional.empty());
        when(daily.findDueDates(date, date.plusDays(1))).thenReturn(List.of(date));
        when(reservation.reserve(eq(date), anyList(), anyList(), any()))
                .thenReturn(new ReportPersistenceService.Reservation(77L, false));
        assertEquals(77L, service.generate(date));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void defersInProgressOrEmptyDayAndRejectsToday() {
        assertNull(service.generate(date));
        verifyNoInteractions(selector, reservation, orchestrator);
        assertThrows(IllegalArgumentException.class,
                () -> service.generate(LocalDate.now(ApiTimeZone.ZONE)));
    }

    @Test
    void settlesUnexpectedGenerationFailureWithDailyFallback() {
        when(daily.findDueDates(date, date.plusDays(1))).thenReturn(List.of(date));
        when(daily.findSourceRunIds(date)).thenReturn(List.of(1L, 2L));
        when(daily.sourceStats(date)).thenReturn(ReportSourceStats.empty());
        when(reservation.reserve(eq(date), eq(List.of(1L, 2L)), anyList(), any()))
                .thenReturn(new ReportPersistenceService.Reservation(77L, true));
        when(orchestrator.generateDaily(eq(77L), eq(date), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        when(fallback.generate(anyList(), any(), any())).thenReturn(
                new ReportDocument("기존 제목", "# 기존 제목\n\n근거 없음", "safe-fallback-report-v1"));
        service.generate(date);
        ArgumentCaptor<ReportDocument> document = ArgumentCaptor.forClass(ReportDocument.class);
        verify(persistence).complete(eq(77L), document.capture(), any());
        assertEquals("2026-08-01 일일 통합 뉴스 보고서", document.getValue().title());
        assertTrue(document.getValue().markdownBody().contains("수집 2회"));
        assertFalse(document.getValue().markdownBody().contains("기존 제목"));
    }

    @Test
    void interruptedReservationRecoversWithoutAnotherLlmCall() {
        LocalDateTime cutoff = date.plusDays(1).atTime(1, 0);
        NewsReport pending = NewsReport.builder().id(77L).reportScope(ReportScope.DAILY).reportDate(date)
                .sourceRunIds(List.of(1L, 2L)).reportStatus(ReportStatus.PENDING).build();
        when(reports.findByReportScopeAndReportStatusAndGeneratedAtBefore(
                ReportScope.DAILY, ReportStatus.PENDING, cutoff)).thenReturn(List.of(pending));
        when(fallback.generate(anyList(), any(), any())).thenReturn(new ReportDocument("복구", "근거 없음", "fallback"));
        service.recoverInterrupted(cutoff);
        verify(persistence).complete(eq(77L), any(), any());
        verifyNoInteractions(orchestrator, reservation);
    }
}
