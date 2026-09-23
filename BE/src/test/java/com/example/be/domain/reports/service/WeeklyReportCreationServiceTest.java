package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeeklyReportCreationServiceTest {
    private final WeeklyReportPersistenceService reservations = mock(WeeklyReportPersistenceService.class);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final AgentWeeklyReportOrchestrator agent = mock(AgentWeeklyReportOrchestrator.class);
    private final WeeklyReportGenerator fallback = mock(WeeklyReportGenerator.class);
    private final ReportPersistenceService persistence = mock(ReportPersistenceService.class);
    private final WeeklyReportCreationService service = new WeeklyReportCreationService(reservations, reports, agent, fallback, persistence);
    private final LocalDate monday = LocalDate.of(2026, 9, 7);
    private final WeeklyReportInput input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(), List.of());

    @Test
    void nonOwnerAndDeferredWeeksDoNotInvokeAgent() {
        when(reservations.reserve(eq(monday), any())).thenReturn(new WeeklyReportPersistenceService.Reservation(1L, false, input));
        assertEquals(1, service.generate(monday));
        when(reservations.reserve(eq(monday), any())).thenReturn(new WeeklyReportPersistenceService.Reservation(null, false, null));
        assertNull(service.generate(monday));
        verifyNoInteractions(agent, fallback, persistence);
    }

    @Test
    void generationExceptionCompletesWithSavedInputFallback() {
        when(reservations.reserve(eq(monday), any())).thenReturn(new WeeklyReportPersistenceService.Reservation(1L, true, input));
        when(agent.generate(eq(1L), eq(input), any())).thenThrow(new IllegalStateException("provider unavailable"));
        ReportDocument document = new ReportDocument("주간", "내용", "fallback");
        when(fallback.generate(input)).thenReturn(document);
        service.generate(monday);
        verify(persistence).complete(eq(1L), same(document), any());
    }

    @Test
    void recoveryUsesOnlyStoredWeeklyInputAndNeverRecallsAgent() {
        var before = monday.plusWeeks(1).atStartOfDay();
        var pending = NewsReport.builder().id(1L).reportScope(ReportScope.WEEKLY)
                .reportStatus(ReportStatus.PENDING).weeklyInput(input).build();
        when(reports.findByReportScopeAndReportStatusAndGeneratedAtBefore(ReportScope.WEEKLY, ReportStatus.PENDING, before))
                .thenReturn(List.of(pending));
        when(fallback.generate(input)).thenReturn(new ReportDocument("주간", "내용", "fallback"));
        service.recoverInterrupted(before);
        verify(persistence).complete(eq(1L), any(), any());
        verifyNoInteractions(agent, reservations);
        verify(reports, never()).findById(any());
    }
}
