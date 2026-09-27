package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.WeeklyReportInput;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopicWeeklyReportCreationServiceTest {
    private final TopicWeeklyReportPersistenceService reservations = mock(TopicWeeklyReportPersistenceService.class);
    private final AgentWeeklyReportOrchestrator orchestrator = mock(AgentWeeklyReportOrchestrator.class);
    private final WeeklyReportGenerator fallback = new WeeklyReportGenerator();
    private final ReportPersistenceService persistence = mock(ReportPersistenceService.class);
    private final TopicWeeklyReportCreationService service = new TopicWeeklyReportCreationService(reservations, orchestrator, fallback, persistence);
    private final LocalDate monday = LocalDate.of(2026, 9, 21);

    @Test void duplicatePendingDoesNotRepeatProviderAndOwnerFailureCompletesStoredFallback() {
        var input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(), List.of(), 1L, "HBM");
        when(reservations.reserve(eq(1L), eq(monday), any())).thenReturn(
                new TopicWeeklyReportPersistenceService.Reservation(8L, false, false, input));
        var duplicate = service.generate(1L, monday);
        assertFalse(duplicate.created()); assertFalse(duplicate.reportReady()); verifyNoInteractions(orchestrator, persistence);
        when(reservations.reserve(eq(1L), eq(monday), any())).thenReturn(
                new TopicWeeklyReportPersistenceService.Reservation(8L, true, false, input));
        when(orchestrator.generate(eq(8L), eq(input), any())).thenThrow(new IllegalStateException("provider failed"));
        var completed = service.generate(1L, monday);
        assertTrue(completed.created()); assertTrue(completed.reportReady());
        verify(persistence).complete(eq(8L), argThat(document -> document.title().startsWith("HBM · ")), any());
    }
}
