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
    private final TopicWeeklyReportSelector selector = mock(TopicWeeklyReportSelector.class);
    private final TopicWeeklyReportCreationService service = new TopicWeeklyReportCreationService(reservations, orchestrator, fallback, persistence, selector);
    private final LocalDate monday = LocalDate.of(2026, 9, 21);

    @Test void duplicatePendingDoesNotRepeatProviderAndOwnerFailureCompletesStoredFallback() {
        var input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(), List.of(), 1L, "HBM");
        when(reservations.reserve(eq(1L), eq(monday), any())).thenReturn(
                new TopicWeeklyReportPersistenceService.Reservation(8L, false, false, input));
        var duplicate = service.generate(1L, monday);
        assertFalse(duplicate.created()); assertFalse(duplicate.reportReady()); verifyNoInteractions(orchestrator, persistence, selector);
        when(reservations.reserve(eq(1L), eq(monday), any())).thenReturn(
                new TopicWeeklyReportPersistenceService.Reservation(8L, true, false, input));
        when(orchestrator.generate(eq(8L), eq(input), any())).thenThrow(new IllegalStateException("provider failed"));
        var completed = service.generate(1L, monday);
        assertTrue(completed.created()); assertTrue(completed.reportReady());
        verify(persistence).complete(eq(8L), argThat(document -> document.title().startsWith("HBM · ")), any());
    }

    @Test void onlyReservationOwnerEvaluatesAndFreezesAnIntentionallyEmptySelection() {
        var input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(), List.of(), 1L, "AI");
        var finding = com.example.be.domain.analysis.entity.Finding.builder().id(1L).build();
        var prepared = new TopicWeeklyReportSelector.Prepared(1L, "AI", monday, List.of(finding), java.util.Map.of());
        var source = new WeeklyReportInput.DailySource(null, monday, "분석", "", null, List.of());
        when(reservations.reserve(eq(1L), eq(monday), any())).thenReturn(
                new TopicWeeklyReportPersistenceService.Reservation(8L, false, false, input, List.of(prepared)),
                new TopicWeeklyReportPersistenceService.Reservation(8L, true, false, input, List.of(prepared)));
        when(selector.selectWeek(List.of(prepared))).thenReturn(new TopicWeeklyReportSelector.WeekSelection(
                List.of(new TopicWeeklyReportSelector.Selection(source, List.of())), List.of(), List.of(1L)));
        service.generate(1L, monday);
        verifyNoInteractions(selector);
        when(orchestrator.generate(eq(8L), any(), any())).thenThrow(new IllegalStateException("provider failed"));
        service.generate(1L, monday);
        verify(selector).selectWeek(List.of(prepared));
        var ordering = inOrder(persistence, orchestrator);
        ordering.verify(persistence).freezeCompanySelection(eq(8L), eq(List.of()), eq(List.of(1L)),
                argThat(value -> value.sourceFindingIds().isEmpty()), eq(List.of()), eq(java.util.Set.of()), eq(false), any(), eq(List.of()));
        ordering.verify(orchestrator).generate(eq(8L), argThat(value -> value.sourceFindingIds().isEmpty()), any());
        ordering.verify(persistence).complete(eq(8L), any(), any());
    }

    @Test void passesTheExactSelectedFindingObjectsAlongsideTheFrozenWeeklyContent() {
        var original = com.example.be.domain.analysis.entity.Finding.builder().id(1L)
                .entities(new com.example.be.domain.analysis.entity.FindingEntities(List.of("삼성전자"), List.of(), List.of())).build();
        var prepared = new TopicWeeklyReportSelector.Prepared(1L, "AI", monday, List.of(original), java.util.Map.of());
        var source = new WeeklyReportInput.DailySource(null, monday, "분석 A", "삼성전자 기사 A", null, List.of(1L));
        var input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(source), List.of(), 1L, "AI");
        when(reservations.reserve(eq(1L), eq(monday), any())).thenReturn(
                new TopicWeeklyReportPersistenceService.Reservation(8L, true, false, input, List.of(prepared)));
        when(selector.selectWeek(List.of(prepared))).thenReturn(new TopicWeeklyReportSelector.WeekSelection(
                List.of(new TopicWeeklyReportSelector.Selection(source, List.of(original))), List.of()));
        when(orchestrator.generate(eq(8L), any(), any())).thenReturn(new ReportDocument("보고서", "원본 A", "test"));
        service.generate(1L, monday);
        verify(persistence).freezeCompanySelection(eq(8L), eq(List.of(1L)), eq(List.of()),
                argThat(value -> value.sources().getFirst() == source), eq(List.of()), eq(java.util.Set.of()), eq(false), any(),
                argThat(values -> values.size() == 1 && values.getFirst() == original));
    }
}
