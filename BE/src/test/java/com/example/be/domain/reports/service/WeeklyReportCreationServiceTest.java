package com.example.be.domain.reports.service;

import com.example.be.domain.companyinterest.CompanyInterestSelectionTestSupport;
import com.example.be.domain.companyinterest.CompanyInterestSelection;

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
    private final CompanyInterestSelection interests = CompanyInterestSelectionTestSupport.passThrough();
    private final WeeklyReportCreationService service = new WeeklyReportCreationService(reservations, reports, agent, fallback, persistence, interests);
    private final LocalDate monday = LocalDate.of(2026, 9, 7);
    private final WeeklyReportInput input = new WeeklyReportInput(monday, monday.plusDays(6), List.of(), List.of());

    @Test
    void nonOwnerAndDeferredWeeksDoNotInvokeAgent() {
        when(reservations.reserve(eq(monday), any())).thenReturn(new WeeklyReportPersistenceService.Reservation(1L, false, input));
        assertEquals(1, service.generate(monday));
        when(reservations.reserve(eq(monday), any())).thenReturn(new WeeklyReportPersistenceService.Reservation(null, false, null));
        assertNull(service.generate(monday));
        verifyNoInteractions(agent, fallback, persistence, interests);
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
    void freezesCompanyRemovedSourcesBeforeGeneratingAWeeklyReport() {
        var first = new WeeklyReportInput.DailySource(10L, monday, "첫날", "", null, List.of(101L));
        var second = new WeeklyReportInput.DailySource(11L, monday.plusDays(1), "다음날", "", null, List.of(102L));
        var original = new WeeklyReportInput(monday, monday.plusDays(6), List.of(first, second), List.of());
        var selected = new WeeklyReportInput(monday, monday.plusDays(6), List.of(second), List.of());
        when(reservations.reserve(eq(monday), any())).thenReturn(new WeeklyReportPersistenceService.Reservation(1L, true, original));
        when(interests.selectWeekly(original, java.util.Set.of())).thenReturn(new CompanyInterestSelection.WeeklyResult(selected, List.of()));
        when(agent.generate(eq(1L), eq(selected), any())).thenReturn(new ReportDocument("주간", "내용", "model"));
        service.generate(monday);
        var ordering = inOrder(persistence, agent);
        ordering.verify(persistence).freezeCompanySelection(eq(1L), eq(List.of(102L)), eq(List.of(101L)), eq(selected), eq(List.of()), eq(java.util.Set.of()), eq(false), any());
        ordering.verify(agent).generate(eq(1L), eq(selected), any());
    }

    @Test
    void forwardsFrozenKeywordTopicsAndActualCriterionProvenanceWithoutAddingFindings() {
        var topics = java.util.Set.of(1L, 2L);
        when(reservations.reserve(eq(monday), any())).thenReturn(new WeeklyReportPersistenceService.Reservation(1L, true, input, topics));
        when(interests.selectWeekly(input, topics)).thenReturn(new CompanyInterestSelection.WeeklyResult(input, List.of(), java.util.Set.of(2L)));
        when(agent.generate(eq(1L), eq(input), any())).thenReturn(new ReportDocument("주간", "내용", "model"));
        service.generate(monday);
        verify(interests).selectWeekly(input, topics);
        verify(persistence).freezeCompanySelection(eq(1L), eq(List.of()), eq(List.of()), same(input), eq(List.of()), eq(java.util.Set.of(2L)), eq(false), any());
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
