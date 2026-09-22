package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.example.be.domain.reports.comparison.ComparisonFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportChangesQueryServiceTest {
    private final TopicRelevancePolicy relevancePolicy = TopicRelevanceTestSupport.legacyPolicy();
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final ReportComparisonRepository comparisons = mock(ReportComparisonRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final ReportChangesQueryService query = new ReportChangesQueryService(reports, comparisons, findings, relevancePolicy);

    private void visible(ReportScope scope) {
        when(reports.findByIdAndReportStatusNot(101L, ReportStatus.PENDING)).thenReturn(Optional.of(
                NewsReport.builder().id(101L).reportScope(scope).reportDate(LocalDate.of(2026, 9, 10)).build()));
    }

    @Test void weeklyReportDoesNotFetchOrSchedulePreviousWeekComparisons() {
        visible(ReportScope.WEEKLY);
        assertEquals(ReportChanges.Status.NOT_APPLICABLE, query.get(101).status());
        verifyNoInteractions(comparisons, findings, relevancePolicy);
    }

    @Test void missingHiddenOrPendingReportUsesReport404() {
        assertThrows(ReportException.class, () -> query.get(101));
        verifyNoInteractions(comparisons);
    }

    @Test void runReportIsNotApplicableAndLegacyDailyDoesNotBackfillLiveEvidence() {
        visible(ReportScope.RUN);
        assertEquals(ReportChanges.Status.NOT_APPLICABLE, query.get(101).status());
        verifyNoInteractions(comparisons);
        visible(ReportScope.DAILY);
        assertEquals(ReportChanges.Status.UNAVAILABLE, query.get(101).status());
        verify(comparisons).findInput(101);
        verify(comparisons, never()).insert(any(), any(), any());
    }

    @Test void savedResultIsReadOnlyAndHiddenBaselineQuotesAreNeverReturned() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "과거 근거")), snapshot(side(1, 20, "현재 근거")));
        var ready = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(fullText(10), fullText(20)));
        assertEquals("과거 근거", query.get(101).items().getFirst().previous().claims().getFirst().evidence().getFirst().text());
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING)).thenReturn(Optional.empty());
        assertEquals(ReportChanges.Status.UNAVAILABLE, query.get(101).status());
        assertTrue(query.get(101).items().isEmpty());
        verify(comparisons, never()).claim(anyLong(), any());
        verify(comparisons, never()).insert(any(), any(), any());
    }

    @Test void staleRunningStateUsesPersistedFailureRatherThanStaleResultStatus() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "이전")), snapshot(side(1, 20, "현재")));
        var pending = new ReportComparisonEngine().prepare(work).result().withStatus(ReportChanges.Status.PENDING);
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.FAILED, work, pending)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        assertEquals(ReportChanges.Status.FAILED, query.get(101).status());
        assertEquals(ReportChanges.Status.FAILED.message, query.get(101).message());
    }

    @Test void topicRejectedEvidenceHidesStoredComparisonQuotes() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "무관한 과거 근거")), snapshot(side(1, 20, "현재 근거")));
        var ready = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(
                101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        var current = fullText(20);
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(fullText(10), current));
        when(relevancePolicy.filterFindings(anyList())).thenReturn(List.of(current));
        var result = query.get(101);
        assertEquals(ReportChanges.Status.UNAVAILABLE, result.status());
        assertTrue(result.items().isEmpty());
        assertTrue(result.notes().isEmpty());
        assertEquals("무관한 과거 근거", ready.items().getFirst().previous().claims().getFirst().text());
    }

    @Test void acceptanceForAnotherTopicCannotExposeEitherSideOfSavedComparison() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "과거 주제 A 근거")), snapshot(side(1, 20, "현재 주제 A 근거")));
        var ready = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(
                101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(fullText(10), fullText(20)));
        for (long rejectedSide : List.of(10L, 20L)) {
            long acceptedSide = rejectedSide == 10L ? 20L : 10L;
            when(relevancePolicy.relevantTopicIdsByFinding(anyList()))
                    .thenReturn(Map.of(rejectedSide, Set.of(2L), acceptedSide, Set.of(1L)));

            var result = query.get(101);

            assertEquals(ReportChanges.Status.UNAVAILABLE, result.status());
            assertTrue(result.items().isEmpty());
            assertTrue(result.notes().isEmpty());
        }
        assertEquals("과거 주제 A 근거", ready.items().getFirst().previous().claims().getFirst().text());
        assertEquals("현재 주제 A 근거", ready.items().getFirst().current().claims().getFirst().text());
    }

    @Test void comparisonAllowsLegacyAndMatchingTopicEvidenceButRejectsAssessedEmptyTopics() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "레거시 근거")), snapshot(side(1, 20, "승인된 주제 근거")));
        var ready = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(
                101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(fullText(10), fullText(20)));
        when(relevancePolicy.relevantTopicIdsByFinding(anyList())).thenReturn(Map.of(20L, Set.of(1L, 2L)));

        var visible = query.get(101);
        assertEquals(ReportChanges.Status.READY, visible.status());
        assertEquals("레거시 근거", visible.items().getFirst().previous().claims().getFirst().text());
        assertEquals("승인된 주제 근거", visible.items().getFirst().current().claims().getFirst().text());

        when(relevancePolicy.relevantTopicIdsByFinding(anyList())).thenReturn(Map.of(20L, Set.of()));
        var unavailable = query.get(101);
        assertEquals(ReportChanges.Status.UNAVAILABLE, unavailable.status());
        assertTrue(unavailable.items().isEmpty());
        assertTrue(unavailable.notes().isEmpty());
    }

    @Test void missingOriginalBodyHidesStoredComparisonWithoutRewritingEitherSnapshot() {
        visible(ReportScope.DAILY);
        var work = work(snapshot(side(1, 10, "이전 숨길 근거")), snapshot(side(1, 20, "현재 근거")));
        var ready = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(
                101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        Finding hidden = Finding.builder().id(10L).article(Article.builder().id(110L)
                .fetchStatus(FetchStatus.METADATA_ONLY).summary("요약만 있는 기사").build()).build();
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(hidden, fullText(20)));
        var result = query.get(101);
        assertEquals(ReportChanges.Status.UNAVAILABLE, result.status());
        assertTrue(result.items().isEmpty());
        assertTrue(result.notes().isEmpty());
        assertEquals("이전 숨길 근거", ready.items().getFirst().previous().claims().getFirst().text());
        verify(comparisons, never()).insert(any(), any(), any());
        // A currently available body is only a visibility check, never a replacement for saved quotes.
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(fullText(10), fullText(20)));
        assertEquals("이전 숨길 근거", query.get(101).items().getFirst().previous().claims().getFirst().text());
    }

    private Finding fullText(long id) {
        return Finding.builder().id(id).article(Article.builder().id(id + 100)
                .fetchStatus(FetchStatus.FULLTEXT).body("현재 갱신된 본문").build()).build();
    }
}
