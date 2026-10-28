package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
                .reportDate(date).generatedAt(date.plusDays(1).atStartOfDay()).reflectedFindingIds(reflected).build();
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

    @Test void delayedAndRecoveredEnqueueUsesCapturedTimeForMergeAndRefutationHistory() {
        LocalDate capturedDate = LocalDate.of(2020, 1, 10);
        var current = report(101, capturedDate, List.of(20L));
        var baseline = report(100, capturedDate.minusDays(1), List.of(10L));
        LocalDateTime capturedAt = current.getGeneratedAt();
        var existingMerge = new ComparisonWork.IdentityLink(1, 2, "MERGED");
        var laterMerge = new ComparisonWork.IdentityLink(99, 2, "MERGED");
        var laterRefutation = new ComparisonWork.IdentityLink(98, 2, "REFUTES");
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(current));
        when(reports.findFirstByReportScopeAndReportDateBeforeAndReportStatusNotAndDeletedAtIsNullOrderByReportDateDescIdDesc(
                ReportScope.DAILY, capturedDate, ReportStatus.PENDING)).thenReturn(Optional.of(baseline));
        when(comparisons.findInput(101)).thenReturn(Optional.of(snapshot(side(2, 20, "현재"))));
        when(comparisons.findInput(100)).thenReturn(Optional.of(snapshot(side(1, 10, "과거"))));
        // Model a delayed registration after both relationships were added to the live issue graph.
        when(comparisons.mergeParents(eq(2L), any())).thenAnswer(invocation ->
                invocation.<LocalDateTime>getArgument(1).isAfter(capturedAt)
                        ? List.of(existingMerge, laterMerge) : List.of(existingMerge));
        when(comparisons.refutedIssues(eq(2L), any())).thenAnswer(invocation ->
                invocation.<LocalDateTime>getArgument(1).isAfter(capturedAt)
                        ? List.of(laterRefutation) : List.of());

        persistence.enqueue(101);
        // Simulate missing-job recovery after the initial registration did not persist.
        persistence.enqueue(101);

        var work = ArgumentCaptor.forClass(ComparisonWork.class);
        verify(comparisons, times(2)).insert(any(), work.capture(), any());
        work.getAllValues().forEach(value -> assertEquals(List.of(existingMerge), value.links()));
        verify(comparisons, times(2)).mergeParents(2L, capturedAt);
        verify(comparisons, times(2)).mergeParents(1L, capturedAt);
        verify(comparisons, times(2)).refutedIssues(2L, capturedAt);
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

    @Test void runDoesNotSkipImmediateSameTopicExecutionWithoutReport() {
        var started = date.atTime(9, 0);
        var run = com.example.be.domain.collection.entity.CollectionRun.builder().id(90L).startedAt(started).build();
        var current = NewsReport.builder().id(101L).run(run).reportScope(ReportScope.RUN)
                .reportStatus(ReportStatus.GENERATED).reflectedFindingIds(List.of(20L)).build();
        var input = snapshot(side(1, 20, "현재"));
        input = new ReportComparisonSnapshot(input.version(), input.issues(), input.scopes(),
                ReportComparisonSnapshot.Period.from(current), List.of());
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(current));
        when(comparisons.findInput(101L)).thenReturn(Optional.of(input));
        when(comparisons.previousRuns(started, 90L)).thenReturn(List.of(
                new ReportComparisonRepository.RunCandidate(89L, null, started.minusWeeks(1), java.util.Set.of(1L), java.util.Set.of(1L), true),
                new ReportComparisonRepository.RunCandidate(88L, 98L, started.minusWeeks(2), java.util.Set.of(1L), java.util.Set.of(1L), true)));
        persistence.enqueue(101L);
        verify(comparisons).insert(argThat(result -> result.status() == ReportChanges.Status.NO_BASELINE
                && result.reportDate() == null && result.baseReportId() == null), isNull(), any());
        verify(reports, never()).findById(98L);
    }

    @Test void runUsesPreviousSavedTopicSetRatherThanAdjacentDifferentTopic() {
        var started = date.atTime(9, 0);
        var run = com.example.be.domain.collection.entity.CollectionRun.builder().id(90L).startedAt(started).build();
        var current = NewsReport.builder().id(101L).run(run).reportScope(ReportScope.RUN).generatedAt(started.plusMinutes(5))
                .reportStatus(ReportStatus.GENERATED).reflectedFindingIds(List.of(20L)).build();
        var baseRun = com.example.be.domain.collection.entity.CollectionRun.builder().id(88L).startedAt(started.minusWeeks(1)).build();
        var base = NewsReport.builder().id(100L).run(baseRun).reportScope(ReportScope.RUN)
                .reportStatus(ReportStatus.GENERATED).reflectedFindingIds(List.of(10L)).build();
        var input = snapshot(side(1, 20, "현재"));
        input = new ReportComparisonSnapshot(input.version(), input.issues(), input.scopes(),
                ReportComparisonSnapshot.Period.from(current), List.of());
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(current));
        when(reports.findById(100L)).thenReturn(Optional.of(base));
        when(comparisons.findInput(101L)).thenReturn(Optional.of(input));
        when(comparisons.findInput(100L)).thenReturn(Optional.of(snapshot(side(1, 10, "이전"))));
        when(comparisons.previousRuns(started, 90L)).thenReturn(List.of(
                new ReportComparisonRepository.RunCandidate(89L, 99L, started.minusDays(1), java.util.Set.of(2L), java.util.Set.of(2L), true),
                new ReportComparisonRepository.RunCandidate(88L, 100L, started.minusWeeks(1), java.util.Set.of(1L), java.util.Set.of(1L), true)));
        persistence.enqueue(101L);
        verify(comparisons).insert(argThat(result -> result.status() == ReportChanges.Status.PENDING
                && result.baseReportId().equals(100L) && result.reportDate() == null && result.baseReportDate() == null), any(), any());
        verify(reports, never()).findById(99L);
    }

    @Test void topicWeeklyBaselineCannotUseIntegratedWeeklyAndDeduplicatesOnlyFinalInputs() {
        var current = NewsReport.builder().id(101L).reportScope(ReportScope.WEEKLY).topicId(1L).reportDate(date)
                .reportEndDate(date.plusDays(6)).generatedAt(date.plusWeeks(1).atStartOfDay())
                .reportStatus(ReportStatus.GENERATED).reflectedFindingIds(List.of(20L, 30L)).build();
        var base = NewsReport.builder().id(100L).reportScope(ReportScope.WEEKLY).topicId(1L).reportDate(date.minusWeeks(1))
                .reportStatus(ReportStatus.GENERATED).reflectedFindingIds(List.of(10L)).build();
        when(reports.findByIdForUpdate(101L)).thenReturn(Optional.of(current));
        when(reports.findPreviousComparisonReports(eq(ReportScope.WEEKLY), eq(1L), eq(date), any())).thenReturn(List.of(base));
        when(comparisons.findInput(101L)).thenReturn(Optional.of(snapshot(side(1, 20, "초반"), side(1, 30, "최종"), side(1, 40, "미반영"))));
        when(comparisons.findInput(100L)).thenReturn(Optional.of(snapshot(side(1, 10, "전주"))));
        persistence.enqueue(101L);
        var work = ArgumentCaptor.forClass(ComparisonWork.class);
        verify(comparisons).insert(any(), work.capture(), any());
        assertEquals(List.of(30L), work.getValue().current().issues().stream().map(ReportComparisonSnapshot.Issue::findingId).toList());
    }
}
