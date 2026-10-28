package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.sources.entity.Source;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.be.domain.reports.comparison.ComparisonFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportComparisonCaptureTest {
    private final ReportComparisonRepository comparisons = mock(ReportComparisonRepository.class);
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final ReportComparisonCapture capture = new ReportComparisonCapture(comparisons, runs, findings);
    private final LocalDate date = LocalDate.of(2026, 10, 26);
    private final LocalDateTime now = date.plusWeeks(1).atTime(0, 5);

    @Test void dailyIncludesFailedExecutionWithoutReportOrFindingsInFrozenConditions() {
        var report = NewsReport.builder().id(11L).reportScope(ReportScope.DAILY).reportDate(date)
                .sourceRunIds(List.of(1L)).collectionContexts(scopes("HBM")).build();
        when(comparisons.periodRunIds(date.atStartOfDay(), date.plusDays(1).atStartOfDay())).thenReturn(List.of(1L, 2L));
        var successful = run(1, RunItemStatus.SUCCESS);
        var failed = run(2, RunItemStatus.FAILED);
        when(runs.findAllById(List.of(1L, 2L))).thenReturn(List.of(successful, failed));
        capture.daily(report, snapshot(side(1, 10, "근거")), now);
        var frozen = ArgumentCaptor.forClass(ReportComparisonSnapshot.class);
        verify(comparisons).captureInput(eq(report), frozen.capture(), eq(now));
        assertEquals(2, frozen.getValue().sourceConditions().size());
        assertFalse(frozen.getValue().sourceConditions().stream().filter(source -> source.runId() == 2L).findFirst().orElseThrow().complete());
        assertEquals(1, frozen.getValue().period().durationDays());
        assertEquals(2, frozen.getValue().scopes().size());
    }

    @Test void topicWeekIncludesAllPeriodExecutionsEvenWhenCandidateListIsEmpty() {
        var report = NewsReport.builder().id(11L).reportScope(ReportScope.WEEKLY).topicId(1L)
                .reportDate(date).reportEndDate(date.plusDays(6)).build();
        when(comparisons.periodRunIds(date.atStartOfDay(), date.plusWeeks(1).atStartOfDay())).thenReturn(List.of(2L));
        var partial = run(2, RunItemStatus.PARTIAL);
        when(runs.findAllById(List.of(2L))).thenReturn(List.of(partial));
        capture.topicWeek(report, List.of(), now);
        verify(comparisons).captureInput(eq(report), argThat(value -> value.period().durationDays() == 7
                && value.sourceConditions().size() == 1 && !value.sourceConditions().getFirst().complete()), eq(now));
    }

    @Test void weeklyKeepsFrozenSourceConditionsAndSelectsLatestDayAfterFinalSelection() {
        var old = snapshot(side(1, 40, "초반"));
        var newer = snapshot(side(1, 20, "후반"));
        old = old.withSourceConditions(List.of(new ReportComparisonSnapshot.SourceCondition(1L, 1L, 1L, "old", true)));
        newer = newer.withSourceConditions(List.of(new ReportComparisonSnapshot.SourceCondition(2L, 1L, 1L, "new", false)));
        var input = new WeeklyReportInput(date, date.plusDays(6), List.of(
                new WeeklyReportInput.DailySource(2L, date.plusDays(1), "후반", "", null, List.of(20L), newer),
                new WeeklyReportInput.DailySource(1L, date, "초반", "", null, List.of(40L), old)), List.of());
        var report = NewsReport.builder().id(11L).reportScope(ReportScope.WEEKLY).reportDate(date)
                .reportEndDate(date.plusDays(6)).weeklyInput(input).collectionContexts(scopes("HBM")).build();
        capture.weekly(report, now);
        var frozen = ArgumentCaptor.forClass(ReportComparisonSnapshot.class);
        verify(comparisons).captureInput(eq(report), frozen.capture(), eq(now));
        assertEquals(20L, frozen.getValue().reflected(List.of(40L, 20L)).latestPerIssue().issues().getFirst().findingId());
        assertEquals(40L, frozen.getValue().reflected(List.of(40L)).latestPerIssue().issues().getFirst().findingId());
        assertEquals(List.of("old", "new"), frozen.getValue().sourceConditions().stream()
                .map(ReportComparisonSnapshot.SourceCondition::fingerprint).toList());
        verifyNoInteractions(runs, findings);
    }

    @Test void legacySameTopicExecutionWithoutFindingsIsRetainedAsUnknown() {
        var legacy = mock(CollectionRunItem.class);
        when(legacy.getTopic()).thenReturn(com.example.be.domain.topics.entity.Topic.builder().id(1L).build());
        when(legacy.getSource()).thenReturn(Source.builder().id(1L).build());
        when(legacy.getStatus()).thenReturn(RunItemStatus.FAILED);
        var conditions = ReportComparisonSnapshot.sourceConditions(List.of(
                run(1, RunItemStatus.SUCCESS), CollectionRun.builder().id(2L).items(List.of(legacy)).build()), 1L);
        assertEquals(2, conditions.size());
        var unknown = conditions.stream().filter(condition -> condition.runId() == 2L).findFirst().orElseThrow();
        assertNull(unknown.topicId());
        assertNull(unknown.fingerprint());
        assertFalse(unknown.complete());
    }

    @Test void unavailableCaptureCannotInterruptOriginalReportReservation() {
        var run = CollectionRun.builder().id(1L).build();
        var report = NewsReport.builder().id(11L).run(run).reportCoverageSnapshot(new ReportCoverageSnapshot(
                ReportCoverageSnapshot.RULE_VERSION, null, "실행", List.of(),
                List.of(new ReportCoverageSnapshot.Binding(10L, 1L, 1L)))).build();
        when(findings.findForReportByIdIn(anyCollection())).thenThrow(new IllegalStateException("검증용 입력 실패"));
        assertDoesNotThrow(() -> capture.run(report, now));
        verify(comparisons, never()).captureInput(any(), any(), any());
    }

    private CollectionRun run(long id, RunItemStatus status) {
        var item = mock(CollectionRunItem.class);
        when(item.getTopicSnapshot()).thenReturn(scopes("HBM").getFirst().topics().getFirst());
        when(item.getSource()).thenReturn(Source.builder().id(1L).build());
        when(item.getStatus()).thenReturn(status);
        when(item.hasComparableSourceConfiguration()).thenReturn(true);
        when(item.getComparisonSourceFingerprint()).thenReturn("source-fingerprint");
        return CollectionRun.builder().id(id).items(List.of(item)).build();
    }
}
