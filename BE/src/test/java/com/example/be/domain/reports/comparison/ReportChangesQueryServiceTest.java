package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.feedback.model.FeedbackModels;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final FeedbackStore feedbackStore = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory eventSnapshots = mock(ReportEventSnapshotFactory.class);
    private final ReportEventFeedbackProjection feedbackProjection = new ReportEventFeedbackProjection(feedbackStore, eventSnapshots);
    private final ReportChangesQueryService query = new ReportChangesQueryService(reports, comparisons, findings, relevancePolicy, feedbackProjection);

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
        var prepared = new ReportComparisonEngine().prepare(work).result();
        var ready = new ReportChanges(prepared.reportId(), prepared.reportDate(), prepared.baseReportId(), prepared.baseReportDate(),
                prepared.status(), prepared.message(), true, List.of("삭제된 이전 보고서의 과거 오류 사건 설명"), prepared.items());
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING))
                .thenReturn(Optional.of(NewsReport.builder().id(100L).build()));
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(fullText(10), fullText(20)));
        assertEquals("과거 근거", query.get(101).items().getFirst().previous().claims().getFirst().evidence().getFirst().text());
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING)).thenReturn(Optional.empty());
        assertEquals(ReportChanges.Status.UNAVAILABLE, query.get(101).status());
        assertTrue(query.get(101).items().isEmpty());
        assertTrue(query.get(101).notes().isEmpty());
        assertFalse(query.get(101).scopeChanged());
        assertEquals(List.of("삭제된 이전 보고서의 과거 오류 사건 설명"), ready.notes());
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

    @Test void confirmedCurrentOrFixedBaselineEventHidesTheSavedComparisonWithoutChangingStorage() {
        var fixture = comparisonWithEvents(false);
        for (long rejectedReport : List.of(101L, 100L)) {
            when(feedbackStore.eventFeedback(101L)).thenReturn(List.of());
            when(feedbackStore.eventFeedback(100L)).thenReturn(List.of());
            var event = rejectedReport == 101L ? fixture.currentEvent() : fixture.baseEvent();
            when(feedbackStore.eventFeedback(rejectedReport)).thenReturn(List.of(review(rejectedReport, event,
                    FeedbackModels.Status.COMPLETED, "CONFIRMED_ERROR")));

            var result = query.get(101);
            assertEquals(ReportChanges.Status.UNAVAILABLE, result.status());
            assertEquals(ReportChanges.Status.UNAVAILABLE.message, result.message());
            assertFalse(result.scopeChanged());
            assertTrue(result.items().isEmpty());
            assertTrue(result.notes().isEmpty());
            assertEquals(100L, result.baseReportId());
            assertEquals("과거 오류 근거", fixture.ready().items().getFirst().previous().claims().getFirst().text());
            assertEquals("현재 오류 근거", fixture.ready().items().getFirst().current().claims().getFirst().text());
            assertEquals(1, fixture.current().getStructuredContent().importantEvents().size());
            assertEquals(1, fixture.base().getStructuredContent().importantEvents().size());
        }
        verify(comparisons, never()).insert(any(), any(), any());
        verify(comparisons, never()).finish(anyLong(), any(), any());
        verify(comparisons, never()).claim(anyLong(), any());
    }

    @Test void staleRevisionPendingAndUnconfirmedReviewsKeepSavedComparisonReady() {
        var fixture = comparisonWithEvents(false);
        var stale = eventItem('c', 0, fixture.current().getStructuredContent().importantEvents().getFirst());
        for (var review : List.of(
                review(101L, stale, FeedbackModels.Status.COMPLETED, "CONFIRMED_ERROR"),
                review(101L, fixture.currentEvent(), FeedbackModels.Status.PENDING, "CONFIRMED_ERROR"),
                review(101L, fixture.currentEvent(), FeedbackModels.Status.COMPLETED, "NOT_CONFIRMED"))) {
            when(feedbackStore.eventFeedback(101L)).thenReturn(List.of(review));
            var result = query.get(101);
            assertEquals(ReportChanges.Status.READY, result.status());
            assertEquals(fixture.ready().items(), result.items());
        }
        // Only the stale confirmed candidate requires recapturing the original event identities.
        verify(eventSnapshots, times(1)).capture(eq(fixture.current()), any());
        verify(eventSnapshots, never()).capture(eq(fixture.base()), any());
    }

    @Test void rejectedSharedEvidenceEventStillInvalidatesTheComparisonEvenWithNoHiddenFindings() {
        var fixture = comparisonWithEvents(true);
        var reviews = List.of(review(101L, fixture.currentEvent(), FeedbackModels.Status.COMPLETED, "CONFIRMED_ERROR"));
        when(feedbackStore.eventFeedback(101L)).thenReturn(reviews);
        var visible = new com.example.be.domain.reports.service.ReportFindings.Visible(List.of(fullText(20)), false);
        var view = feedbackProjection.project(fixture.current(), visible, reviews);
        assertEquals(1, view.findings().size(), "the valid surviving event still owns the same evidence");
        assertTrue(view.excludedEvents());
        assertEquals(ReportChanges.Status.UNAVAILABLE, query.get(101).status());
        assertTrue(query.get(101).items().isEmpty());
        assertEquals(2, fixture.current().getStructuredContent().importantEvents().size());
    }

    @Test void confirmedErrorsAlsoHideUncertaintyNotesForEveryPersistedJobState() {
        var fixture = comparisonWithEvents(false);
        var ready = fixture.ready();
        var saved = new ReportChanges(ready.reportId(), ready.reportDate(), ready.baseReportId(), ready.baseReportDate(),
                ready.status(), ready.message(), true, List.of("현재 오류 사건을 반복하는 불확실성 설명"), ready.items());
        when(feedbackStore.eventFeedback(101L)).thenReturn(List.of(review(101L, fixture.currentEvent(),
                FeedbackModels.Status.COMPLETED, "CONFIRMED_ERROR")));
        for (var state : ReportChanges.Status.values()) {
            when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, state, null, saved)));
            var result = query.get(101);
            assertEquals(ReportChanges.Status.UNAVAILABLE, result.status());
            assertFalse(result.scopeChanged());
            assertTrue(result.notes().isEmpty(), state.name());
            assertTrue(result.items().isEmpty(), state.name());
            assertEquals(100L, result.baseReportId());
        }
        assertEquals(List.of("현재 오류 사건을 반복하는 불확실성 설명"), saved.notes());
        assertTrue(saved.scopeChanged());
        assertEquals(ready.items(), saved.items());
        verify(comparisons, never()).finish(anyLong(), any(), any());
    }

    private EventComparisonFixture comparisonWithEvents(boolean sharedEvidence) {
        var currentBad = new ReportContent.ImportantEvent("현재 오류 사건", "현재 오류 요약", "오류 이유", List.of(20L));
        var currentGood = new ReportContent.ImportantEvent("현재 정상 사건", "현재 정상 요약", "정상 이유", List.of(20L));
        var baseBad = new ReportContent.ImportantEvent("과거 오류 사건", "과거 오류 요약", "오류 이유", List.of(10L));
        var current = NewsReport.builder().id(101L).reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2026, 9, 10))
                .reflectedFindingIds(List.of(20L)).structuredContent(new ReportContent(List.of(),
                        sharedEvidence ? List.of(currentBad, currentGood) : List.of(currentBad), List.of(), List.of())).build();
        var base = NewsReport.builder().id(100L).reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2026, 9, 9))
                .reflectedFindingIds(List.of(10L)).structuredContent(new ReportContent(List.of(), List.of(baseBad), List.of(), List.of())).build();
        var currentEvent = eventItem('b', 0, currentBad);
        var baseEvent = eventItem('a', 0, baseBad);
        var work = work(snapshot(side(1, 10, "과거 오류 근거")), snapshot(side(1, 20, "현재 오류 근거")));
        var ready = new ReportComparisonEngine().prepare(work).result();
        when(comparisons.find(101)).thenReturn(Optional.of(new ReportComparisonRepository.Job(101, ReportChanges.Status.READY, work, ready)));
        when(reports.findByIdAndReportStatusNot(101L, ReportStatus.PENDING)).thenReturn(Optional.of(current));
        when(reports.findByIdAndReportStatusNot(100L, ReportStatus.PENDING)).thenReturn(Optional.of(base));
        when(findings.findForReportByIdIn(anyCollection())).thenReturn(List.of(fullText(10), fullText(20)));
        when(eventSnapshots.capture(eq(current), any())).thenReturn(sharedEvidence
                ? List.of(currentEvent, eventItem('d', 1, currentGood)) : List.of(currentEvent));
        when(eventSnapshots.capture(eq(base), any())).thenReturn(List.of(baseEvent));
        return new EventComparisonFixture(current, base, currentEvent, baseEvent, ready);
    }

    private FeedbackModels.Item eventItem(char key, int index, ReportContent.ImportantEvent event) {
        return new FeedbackModels.Item(null, null, null, List.of(), null, null, null, null, null, null, null,
                new FeedbackModels.EventContext(String.valueOf(key).repeat(64), index, event.title(), event.summaryKo(), event.significance(),
                        event.sourceFindingIds(), List.of(), List.of(), null));
    }

    private FeedbackModels.Feedback review(long reportId, FeedbackModels.Item event, FeedbackModels.Status status, String verdict) {
        return new FeedbackModels.Feedback(1L, null, null, reportId, null, FeedbackModels.Category.SUMMARY_ERROR,
                "의견", false, "hash", status, verdict, "검토 설명", LocalDateTime.of(2026, 10, 1, 12, 0), event, event.event().key());
    }

    private record EventComparisonFixture(NewsReport current, NewsReport base, FeedbackModels.Item currentEvent,
                                          FeedbackModels.Item baseEvent, ReportChanges ready) { }

    private Finding fullText(long id) {
        return Finding.builder().id(id).article(Article.builder().id(id + 100)
                .fetchStatus(FetchStatus.FULLTEXT).body("현재 갱신된 본문").build()).build();
    }
}
