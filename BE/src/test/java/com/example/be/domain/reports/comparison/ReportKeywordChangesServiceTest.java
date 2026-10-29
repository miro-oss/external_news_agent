package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.keyword.ReportKeywordDTO;
import com.example.be.domain.reports.keyword.ReportKeywordService;
import com.example.be.domain.reports.keyword.ReportKeywordSnapshot;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static com.example.be.domain.reports.comparison.ComparisonFixtures.scopes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportKeywordChangesServiceTest {
    private final ReportKeywordService keywords = mock(ReportKeywordService.class);
    private final ReportComparisonCriteria criteria = mock(ReportComparisonCriteria.class);
    private final ReportKeywordChangesService service = new ReportKeywordChangesService(keywords, criteria);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    private static final ReportEventFeedbackProjection.View VIEW = new ReportEventFeedbackProjection.View(
            List.of(), new ReportReadingContent("", null), false);
    private static final List<ReportKeywordSnapshot.Definition> WORDS = IntStream.range(0, 8)
            .mapToObj(index -> new ReportKeywordSnapshot.Definition("k" + index, "키워드" + index)).toList();

    @Test void comparesEveryKeyBeforeLimitingAndUsesPercentagePointsForZeroBaseline() {
        var before = report(1);
        var now = report(2);
        var oldCounts = counts(1, 10.0);
        var newCounts = counts(1, 10.0);
        oldCounts.put("k7", new ReportKeywordService.KeywordCount("키워드7", 0, 0.0));
        newCounts.put("k7", new ReportKeywordService.KeywordCount("키워드7", 5, 50.0));
        aggregate(before, 10, oldCounts, false);
        aggregate(now, 10, newCounts, false);
        var result = service.project(before, now, work(snapshot(1), snapshot(2)), VIEW, VIEW, false);
        assertEquals(ReportChanges.KeywordStatus.AVAILABLE, result.status());
        assertEquals(6, result.items().size());
        assertEquals("키워드7", result.items().getFirst().keyword());
        assertEquals(0, result.items().getFirst().previousIssueCount());
        assertEquals(50.0, result.items().getFirst().changePercentagePoints());
        assertFalse(result.lowSample());
    }

    @Test void zeroDenominatorIsNotAnInfiniteIncreaseAndSmallSamplesAreExplicit() {
        var before = report(1);
        var now = report(2);
        aggregate(before, 0, counts(0, null), false);
        aggregate(now, 2, counts(1, 50.0), false);
        var work = work(snapshot(1), snapshot(2));
        var empty = service.project(before, now, work, VIEW, VIEW, false);
        assertEquals("EMPTY_SAMPLE", empty.reason());
        assertEquals(0, empty.previousDenominatorIssueCount());
        assertTrue(empty.items().isEmpty());
        aggregate(before, 4, counts(1, 25.0), false);
        var small = service.project(before, now, work, VIEW, VIEW, false);
        assertTrue(small.lowSample());
        assertEquals(25.0, small.items().getFirst().changePercentagePoints());
    }

    @Test void changedKeywordDefinitionOrAppliedCompanyVersionPreventsNumericComparison() {
        var before = report(1);
        var now = report(2);
        when(criteria.applied(before)).thenReturn(Set.of(new ReportComparisonCriteria.AppliedCriterion(4, 1, 1)));
        when(criteria.applied(now)).thenReturn(Set.of(new ReportComparisonCriteria.AppliedCriterion(4, 2, 1)));
        assertEquals("CONDITIONS_CHANGED", service.project(before, now, work(snapshot(1), snapshot(2)), VIEW, VIEW, false).reason());
        verifyNoInteractions(keywords);
        var changed = NewsReport.builder().id(3L).companySelectionRecorded(true).reportKeywordSnapshot(new ReportKeywordSnapshot(
                ReportKeywordSnapshot.RULE_VERSION, OffsetDateTime.now(), "기간", null,
                List.of(new ReportKeywordSnapshot.Definition("added", "추가")), List.of())).build();
        assertEquals("CONDITIONS_CHANGED", service.project(before, changed, work(snapshot(1), snapshot(2)), VIEW, VIEW, false).reason());
    }

    @Test void sameCriterionUnionCannotHidePolicyChangesWithinAWeek() {
        var before = report(1);
        var now = report(2);
        when(criteria.hasMixedSourceCriteria(now)).thenReturn(true);
        assertEquals("CONDITIONS_CHANGED", service.project(before, now, work(snapshot(1), snapshot(2)), VIEW, VIEW, false).reason());
        verifyNoInteractions(keywords);
    }

    @Test void uncertainCompanySelectionStoredInKeywordSnapshotIsNotACompatibleEmptyPolicySet() {
        var before = report(1);
        var known = report(2).getReportKeywordSnapshot();
        var now = NewsReport.builder().id(2L).companySelectionRecorded(true).reportKeywordSnapshot(
                new ReportKeywordSnapshot(known.ruleVersion(), known.capturedAt(), known.periodLabel(),
                        "COMPANY_SCOPE_UNVERIFIED", known.definitions(), known.candidates())).build();
        assertEquals("CONDITIONS_UNVERIFIED", service.project(before, now, work(snapshot(1), snapshot(2)), VIEW, VIEW, false).reason());
        verifyNoInteractions(keywords, criteria);
    }

    @Test void sourceConditionsMustCoverEveryFrozenKeywordRunAndTopic() {
        var before = report(1);
        var known = report(2).getReportKeywordSnapshot();
        for (var candidate : List.of(new ReportKeywordSnapshot.Candidate(2L, 2L, 999L, 1L, 3L, List.of("k1")),
                new ReportKeywordSnapshot.Candidate(2L, 2L, 2L, 999L, 3L, List.of("k1")))) {
            var now = NewsReport.builder().id(2L).companySelectionRecorded(true).reportKeywordSnapshot(
                    new ReportKeywordSnapshot(known.ruleVersion(), known.capturedAt(), known.periodLabel(), null,
                            known.definitions(), List.of(candidate))).build();
            assertEquals("CONDITIONS_UNVERIFIED", service.project(before, now, work(snapshot(1), snapshot(2)), VIEW, VIEW, false).reason());
        }
        verifyNoInteractions(keywords, criteria);
    }

    @Test void sourceConfigurationChangesIncompleteCollectionAndMissingPeriodHaveSeparateReasons() {
        var before = snapshot(1);
        var now = snapshot(2);
        var changed = new ReportComparisonSnapshot(1, List.of(), now.scopes(), now.period(),
                List.of(new ReportComparisonSnapshot.SourceCondition(2L, 1L, 1L, "changed", true)));
        assertEquals("CONDITIONS_CHANGED", ReportKeywordChangesService.incompatible(before, changed));
        var failed = new ReportComparisonSnapshot(1, List.of(), now.scopes(), now.period(),
                List.of(new ReportComparisonSnapshot.SourceCondition(2L, 1L, 1L, "same", false)));
        assertEquals("INCOMPLETE_PERIOD", ReportKeywordChangesService.incompatible(before, failed));
        var unknown = new ReportComparisonSnapshot(1, List.of(), now.scopes());
        assertEquals("CONDITIONS_UNVERIFIED", ReportKeywordChangesService.incompatible(before, unknown));
        var period = now.period();
        var missing = new ReportComparisonSnapshot(1, List.of(), now.scopes(), new ReportComparisonSnapshot.Period(
                period.scope(), period.topicId(), period.from(), period.through(), period.durationDays(),
                null, null, List.of(DATE)), now.sourceConditions());
        assertEquals("INCOMPLETE_PERIOD", ReportKeywordChangesService.incompatible(before, missing));
    }

    @Test void sourceSetChangesWithinEitherPeriodAreNotEquivalentToTheSameUnionOfSources() {
        var before = snapshot(1);
        var now = snapshot(2);
        var mixed = new ReportComparisonSnapshot(1, List.of(), now.scopes(), now.period(), List.of(
                new ReportComparisonSnapshot.SourceCondition(2L, 1L, 1L, "same", true),
                new ReportComparisonSnapshot.SourceCondition(3L, 1L, 2L, "same", true)));
        assertEquals("CONDITIONS_CHANGED", ReportKeywordChangesService.incompatible(before, mixed));
    }

    @Test void fulltextOrInheritedCandidateLossSuppressesNumbersEvenWhenFinalReportViewIsUnchanged() {
        var before = report(1);
        var now = report(2);
        aggregate(before, 10, counts(1, 10.0), false);
        aggregate(now, 9, counts(1, 11.1), true);
        var result = service.project(before, now, work(snapshot(1), snapshot(2)), VIEW, VIEW, false);
        assertEquals("FILTERED_INPUT", result.reason());
        assertTrue(result.items().isEmpty());
        assertNull(result.currentDenominatorIssueCount());
    }

    @Test void weeklyTopicIdentityDurationAndRunMetadataAreRequired() {
        var before = snapshot(1);
        var now = snapshot(2);
        var weekly = new ReportComparisonSnapshot(1, List.of(), now.scopes(), new ReportComparisonSnapshot.Period(
                ReportScope.WEEKLY, 44L, DATE, DATE.plusDays(6), 7, null, null, List.of()), now.sourceConditions());
        assertEquals("CONDITIONS_CHANGED", ReportKeywordChangesService.incompatible(before, weekly));
        var runBefore = run(1, DATE.atStartOfDay());
        var runNow = run(2, DATE.plusWeeks(1).atStartOfDay());
        assertNull(ReportKeywordChangesService.incompatible(runBefore, runNow));
        var noTime = run(2, null);
        assertEquals("CONDITIONS_UNVERIFIED", ReportKeywordChangesService.incompatible(runBefore, noTime));
    }

    @Test void unrecordedCompanySelectionIsUnknownAndMissingBaselineIsNotZero() {
        var before = NewsReport.builder().id(1L).reportKeywordSnapshot(report(1).getReportKeywordSnapshot()).build();
        assertEquals("CONDITIONS_UNVERIFIED", service.project(before, report(2), work(snapshot(1), snapshot(2)), VIEW, VIEW, false).reason());
        assertEquals("NO_BASELINE", service.project(null, report(2), null, null, VIEW, false).reason());
        verifyNoInteractions(keywords, criteria);
    }

    private void aggregate(NewsReport report, int denominator, Map<String, ReportKeywordService.KeywordCount> counts, boolean filtered) {
        var view = new ReportKeywordDTO.View(ReportKeywordDTO.Status.AVAILABLE, null, ReportKeywordSnapshot.RULE_VERSION,
                OffsetDateTime.now(), "기간", denominator, List.of(), 0);
        when(keywords.aggregate(report, VIEW, false)).thenReturn(new ReportKeywordService.FullAggregation(view, counts, filtered));
    }
    private static Map<String, ReportKeywordService.KeywordCount> counts(int count, Double share) {
        Map<String, ReportKeywordService.KeywordCount> result = new LinkedHashMap<>();
        WORDS.forEach(word -> result.put(word.key(), new ReportKeywordService.KeywordCount(word.label(), count, share)));
        return result;
    }
    private static NewsReport report(long id) {
        return NewsReport.builder().id(id).reportScope(ReportScope.DAILY).reportDate(DATE.plusDays(id))
                .companySelectionRecorded(true).reportKeywordSnapshot(new ReportKeywordSnapshot(
                        ReportKeywordSnapshot.RULE_VERSION, OffsetDateTime.now(), "기간", null, WORDS, List.of())).build();
    }
    private static ReportComparisonSnapshot snapshot(long id) {
        return new ReportComparisonSnapshot(1, List.of(), scopes("HBM"), new ReportComparisonSnapshot.Period(
                ReportScope.DAILY, null, DATE.plusDays(id), DATE.plusDays(id), 1, null, null, List.of()),
                List.of(new ReportComparisonSnapshot.SourceCondition(id, 1L, 1L, "same", true)));
    }
    private static ReportComparisonSnapshot run(long id, LocalDateTime at) {
        var source = snapshot(id);
        return new ReportComparisonSnapshot(1, List.of(), source.scopes(), new ReportComparisonSnapshot.Period(
                ReportScope.RUN, null, at == null ? null : at.toLocalDate(), at == null ? null : at.toLocalDate(),
                null, id, at, List.of()), source.sourceConditions());
    }
    private static ComparisonWork work(ReportComparisonSnapshot previous, ReportComparisonSnapshot current) {
        return new ComparisonWork(2L, DATE.plusDays(2), 1L, DATE.plusDays(1), previous, current, List.of());
    }
}
