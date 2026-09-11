package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static com.example.be.domain.reports.comparison.ComparisonFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportComparisonPersistenceTest {
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final ReportComparisonRepository comparisons = mock(ReportComparisonRepository.class);
    private final ReportComparisonPersistence persistence = new ReportComparisonPersistence(reports, comparisons);
    private final LocalDate date = LocalDate.of(2026, 9, 10);

    private NewsReport report(long id, LocalDate date, List<Long> reflected) {
        return NewsReport.builder().id(id).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.GENERATED)
                .reportDate(date).reflectedFindingIds(reflected).build();
    }

    @Test void enqueueFreezesChronologicalBaselineAndOnlyFinalReflectedInputs() {
        var current = report(101, date, List.of(20L));
        var baseline = report(100, date.minusDays(3), List.of(10L));
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(current));
        when(reports.findFirstByReportScopeAndReportDateBeforeAndReportStatusNotAndDeletedAtIsNullOrderByReportDateDescIdDesc(
                ReportScope.DAILY, date, ReportStatus.PENDING)).thenReturn(Optional.of(baseline));
        when(comparisons.findInput(101)).thenReturn(Optional.of(snapshot(side(1, 20, "현재"), side(2, 30, "반영되지 않음"))));
        when(comparisons.findInput(100)).thenReturn(Optional.of(snapshot(side(1, 10, "과거"))));
        persistence.enqueue(101);
        var work = ArgumentCaptor.forClass(ComparisonWork.class);
        verify(comparisons).insert(argThat(value -> value.status() == ReportChanges.Status.PENDING), work.capture(), any());
        assertEquals(100, work.getValue().baseReportId());
        assertEquals(date.minusDays(3), work.getValue().baseReportDate());
        assertEquals(1, work.getValue().current().issues().size());
        assertEquals(20, work.getValue().current().issues().getFirst().findingId());
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.PENDING, work.getValue(), null)));
        persistence.enqueue(101);
        verify(comparisons, times(1)).insert(any(), any(), any());
    }

    @Test void missingOrIncompleteHistoricalInputIsUnavailable() {
        var current = report(101, date, List.of(999L));
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(current));
        when(comparisons.findInput(101)).thenReturn(Optional.of(snapshot(side(1, 20, "현재"))));
        persistence.enqueue(101);
        verify(comparisons).insert(argThat(value -> value.status() == ReportChanges.Status.UNAVAILABLE), isNull(), any());
    }

    @Test void firstComparableReportHasNoBaseline() {
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(report(101, date, List.of(20L))));
        when(comparisons.findInput(101)).thenReturn(Optional.of(snapshot(side(1, 20, "현재"))));
        persistence.enqueue(101);
        verify(comparisons).insert(argThat(value -> value.status() == ReportChanges.Status.NO_BASELINE), isNull(), any());
    }

    @Test void claimChecksVisibilityAndCannotPublishBaselineDeletedDuringProviderCall() {
        var work = work(snapshot(side(1, 10, "과거")), snapshot(side(1, 20, "현재")));
        var result = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.claim(eq(101L), any())).thenReturn(true);
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.RUNNING, work, result)));
        when(reports.findById(101L)).thenReturn(Optional.of(report(101, date, List.of(20L))));
        assertTrue(persistence.claim(101).isEmpty());
        verify(comparisons).finish(eq(101L), argThat(value -> value.status() == ReportChanges.Status.UNAVAILABLE && value.items().isEmpty()), any());
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(report(101, date, List.of(20L))));
        persistence.finish(101, result);
        verify(comparisons, times(2)).finish(eq(101L), argThat(value -> value.status() == ReportChanges.Status.UNAVAILABLE && value.items().isEmpty()), any());
    }
}
