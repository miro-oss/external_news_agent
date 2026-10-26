package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.feedback.model.FeedbackModels.*;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyReportFeedbackExclusionsTest {
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FeedbackStore feedback = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory snapshots = mock(ReportEventSnapshotFactory.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final ReportComparisonRepository comparisons = mock(ReportComparisonRepository.class);
    private final DailyReportFeedbackExclusions exclusions = new DailyReportFeedbackExclusions(
            reports, feedback, snapshots, findings, relevance, comparisons);
    private final LocalDate monday = LocalDate.of(2026, 9, 21);

    @BeforeEach void prepare() {
        when(relevance.filterFindings(anyList())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void sharesDailyValidationAcrossGlobalAndTopicWeeksAndMatchesDifferentRepresentatives() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        var original = snapshot(identity(11, 100, 1));
        when(comparisons.findInput(10)).thenReturn(Optional.of(original));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                ReportScope.DAILY, monday, monday.plusDays(6))).thenReturn(List.of(daily));
        when(feedback.eventReviewStates(Set.of(10L))).thenReturn(List.of(confirmed(10, "current")));
        NewsReport global = weekly(20L, null, monday, List.of(21L, 22L, 23L), snapshot(
                identity(21, 100, 1), identity(22, 101, 1), identity(23, 100, 2)));
        NewsReport topic = weekly(30L, 1L, monday, List.of(31L), snapshot(identity(31, 100, 1)));
        NewsReport otherTopic = weekly(40L, 2L, monday, List.of(41L), snapshot(identity(41, 100, 2)));

        assertEquals(Map.of(20L, Set.of(21L), 30L, Set.of(31L), 40L, Set.of()),
                exclusions.forReports(List.of(global, topic, otherTopic)));

        verify(reports, times(1)).findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                ReportScope.DAILY, monday, monday.plusDays(6));
        verify(feedback, times(1)).eventReviewStates(Set.of(10L));
        verify(feedback, never()).eventFeedback(anyLong());
        verify(snapshots, times(1)).capture(eq(daily), any());
        verify(comparisons, times(1)).findInput(10);
        assertEquals(List.of(11L), original.issues().stream().map(ReportComparisonSnapshot.Issue::findingId).toList());
        assertEquals(List.of(21L, 22L, 23L), global.getReflectedFindingIds());
    }

    @Test void selectionAppliesAConfirmedMondayIssueToTheWholeWeek() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                ReportScope.DAILY, monday, monday.plusDays(6))).thenReturn(List.of(daily));
        when(feedback.eventReviewStates(anyCollection())).thenReturn(List.of(confirmed(10, "current")));
        when(comparisons.findInput(10)).thenReturn(Optional.of(snapshot(identity(11, 100, 1))));
        var candidates = snapshot(identity(21, 100, 1), identity(22, 100, 2), identity(23, 101, 1));

        assertEquals(Set.of(21L), exclusions.forSelection(monday.plusDays(3), candidates));

        verify(reports).findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                ReportScope.DAILY, monday, monday.plusDays(6));
        assertEquals(3, candidates.issues().size());
    }

    @Test void hiddenDailyDecisionStillAppliesAndSyntheticWeeklyReportNeedsNoId() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        daily.hide(monday.plusWeeks(1).atStartOfDay());
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(daily));
        when(feedback.eventReviewStates(anyCollection())).thenReturn(List.of(confirmed(10, "current")));
        when(comparisons.findInput(10)).thenReturn(Optional.of(snapshot(identity(11, 100, 1))));
        NewsReport pendingInput = weekly(null, null, monday, List.of(21L), snapshot(identity(21, 100, 1)));

        assertEquals(Set.of(21L), exclusions.forReport(pendingInput));
        assertNotNull(daily.getDeletedAt());
        assertEquals(List.of(11L), daily.getReflectedFindingIds());
    }

    @Test void everySubmittedDailyOpinionPropagatesOnlyToWeeklyDeliveryWithinTheSavedIssueAndTopic() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(daily));
        when(comparisons.findInput(10)).thenReturn(Optional.of(snapshot(identity(11, 100, 1))));
        var target = weekly(20L, null, monday, List.of(21L, 22L, 23L), snapshot(
                identity(21, 100, 1), identity(22, 101, 1), identity(23, 100, 2)));
        for (Category category : Category.values()) for (Status status : Status.values()) {
            var submitted = review(10, "current", category, status, null, null, null, null);
            when(feedback.eventFeedback(10)).thenReturn(List.of(submitted));
            assertEquals(Set.of(21L), exclusions.forDelivery(target));
            assertTrue(exclusions.forReport(target).isEmpty());
        }
    }

    @Test void deliveryUsesVerifiedFrozenInputWhenTheSourceDailyEventWasRevised() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any())).thenReturn(List.of(daily));
        when(comparisons.findInput(10)).thenReturn(Optional.of(snapshot(identity(11, 100, 1))));
        var target = weekly(20L, 1L, monday, List.of(21L, 22L), snapshot(identity(21, 100, 1), identity(22, 101, 1)));
        var submitted = review(10, "old", Category.PREFERENCE, Status.PROCESSING, null, null, null, null);
        var original = item("old", List.of(11L), List.of(new Topic(1, "주제", List.of(), List.of())));
        when(feedback.eventFeedback(10)).thenReturn(List.of(withInput(submitted, original)));
        assertEquals(Set.of(21L), exclusions.forDelivery(target));
        when(feedback.eventFeedback(10)).thenReturn(List.of(withInput(submitted, item("wrong", List.of(11L), List.of()))));
        assertTrue(exclusions.forDelivery(target).isEmpty());
        when(feedback.eventFeedback(10)).thenReturn(List.of(withInput(
                review(99, "old", Category.PREFERENCE, Status.PROCESSING, null, null, null, null), original)));
        assertTrue(exclusions.forDelivery(target).isEmpty());
    }

    @Test void legacyEvidenceUsesExactFindingIdsAndKeepsAnotherTopic() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        when(snapshots.capture(eq(daily), any())).thenReturn(List.of(item("current", List.of(11L), List.of(
                new Topic(1, "주제 1", List.of(), List.of())))));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(daily));
        when(feedback.eventReviewStates(anyCollection())).thenReturn(List.of(confirmed(10, "current")));
        NewsReport matching = weekly(20L, 1L, monday, List.of(11L, 12L), null);
        NewsReport otherTopic = weekly(30L, 2L, monday, List.of(11L), null);
        NewsReport global = weekly(40L, null, monday, List.of(11L, 12L), null);

        assertEquals(Map.of(20L, Set.of(11L), 30L, Set.of(), 40L, Set.of(11L)),
                exclusions.forReports(List.of(matching, otherTopic, global)));
    }

    @Test void missingTargetSnapshotFallsBackToExactIdentityWithoutGuessingAnIssue() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(daily));
        when(feedback.eventReviewStates(anyCollection())).thenReturn(List.of(confirmed(10, "current")));
        when(comparisons.findInput(10)).thenReturn(Optional.of(snapshot(identity(11, 100, 1))));

        assertEquals(Set.of(11L), exclusions.forReport(weekly(20L, 1L, monday, List.of(11L, 21L), null)));
        assertTrue(exclusions.forReport(weekly(30L, 2L, monday, List.of(11L), null)).isEmpty());
    }

    @Test void exactFindingMatchCannotOverrideDifferentKnownIssueOrTopic() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(daily));
        when(feedback.eventReviewStates(anyCollection())).thenReturn(List.of(confirmed(10, "current")));
        when(comparisons.findInput(10)).thenReturn(Optional.of(snapshot(identity(11, 100, 1))));

        assertTrue(exclusions.forReport(weekly(20L, 1L, monday, List.of(11L),
                snapshot(identity(11, 101, 1)))).isEmpty());
        assertTrue(exclusions.forReport(weekly(30L, 2L, monday, List.of(11L),
                snapshot(identity(11, 100, 2)))).isEmpty());
    }

    @Test void stalePendingUnconfirmedPersonalAndPreferenceReviewsNeverPropagate() {
        NewsReport daily = reviewedDaily(10, monday, 11);
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(daily));
        when(feedback.eventReviewStates(anyCollection())).thenReturn(List.of(
                review(10, "old-revision", Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null, null, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.PENDING, "CONFIRMED_ERROR", null, null, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.PROCESSING, "CONFIRMED_ERROR", null, null, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.FAILED, "CONFIRMED_ERROR", null, null, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.COMPLETED, "NOT_CONFIRMED", null, null, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.COMPLETED, "INSUFFICIENT_EVIDENCE", null, null, null),
                review(10, "current", Category.PREFERENCE, Status.COMPLETED, "CONFIRMED_ERROR", null, null, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", 3L, null, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null, 3L, null),
                review(10, "current", Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null, null, 3L)));

        assertTrue(exclusions.forReport(weekly(20L, null, monday, List.of(11L), null)).isEmpty());

        verify(snapshots).capture(eq(daily), any());
        verifyNoInteractions(comparisons);
    }

    @Test void completedDailyDatesAreBoundedAndOtherWeeksCannotAffectTheTarget() {
        NewsReport current = reviewedDaily(10, monday, 11);
        NewsReport earlier = daily(9, monday.minusDays(1), ReportStatus.GENERATED, List.of(11L));
        NewsReport later = daily(18, monday.plusWeeks(1), ReportStatus.GENERATED, List.of(11L));
        NewsReport pending = daily(12, monday.plusDays(2), ReportStatus.PENDING, List.of(11L));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                ReportScope.DAILY, monday, monday.plusDays(6))).thenReturn(List.of(earlier, current, pending, later));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(
                ReportScope.DAILY, monday.plusWeeks(1), monday.plusDays(13))).thenReturn(List.of());
        when(feedback.eventReviewStates(Set.of(10L))).thenReturn(List.of(confirmed(10, "current")));

        assertEquals(Map.of(20L, Set.of(11L), 30L, Set.of()), exclusions.forReports(List.of(
                weekly(20L, null, monday, List.of(11L), null),
                weekly(30L, null, monday.plusWeeks(1), List.of(11L), null))));

        verify(feedback).eventReviewStates(Set.of(10L));
        verify(snapshots, times(1)).capture(any(), any());
    }

    @Test void absentDecisionsNeverLoadDailyEvidence() {
        NewsReport daily = daily(10, monday, ReportStatus.GENERATED, List.of(11L));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(any(), any(), any()))
                .thenReturn(List.of(daily));

        assertTrue(exclusions.forReport(weekly(20L, null, monday, List.of(11L), null)).isEmpty());

        verifyNoInteractions(findings, relevance, snapshots, comparisons);
    }

    @Test void emptyAndNonWeeklyInputsDoNotReadTheDatabase() {
        assertTrue(exclusions.forReports(List.of()).isEmpty());
        assertTrue(exclusions.forReport(NewsReport.builder().id(1L).reportScope(ReportScope.RUN)
                .reflectedFindingIds(List.of(11L)).build()).isEmpty());
        assertTrue(exclusions.forReport(daily(2, monday, ReportStatus.GENERATED, List.of(11L))).isEmpty());
        assertTrue(exclusions.forReport(weekly(3L, null, monday, List.of(), null)).isEmpty());
        assertTrue(exclusions.forSelection(monday, null).isEmpty());
        assertTrue(exclusions.forSelection(monday, snapshot()).isEmpty());
        verifyNoInteractions(reports, feedback, findings, relevance, snapshots, comparisons);
    }

    private NewsReport reviewedDaily(long id, LocalDate date, long findingId) {
        NewsReport report = daily(id, date, ReportStatus.GENERATED, List.of(findingId));
        Article article = mock(Article.class);
        when(article.hasFullText()).thenReturn(true);
        Finding finding = Finding.builder().id(findingId).article(article).build();
        when(findings.findForReportByIdIn(List.of(findingId))).thenReturn(List.of(finding));
        when(snapshots.capture(eq(report), any())).thenReturn(List.of(item("current", List.of(findingId), List.of())));
        return report;
    }

    private NewsReport daily(long id, LocalDate date, ReportStatus status, List<Long> ids) {
        return NewsReport.builder().id(id).reportScope(ReportScope.DAILY).reportDate(date)
                .reportStatus(status).title("일일").markdownBody("저장 본문").reflectedFindingIds(ids).build();
    }

    private NewsReport weekly(Long id, Long topicId, LocalDate date, List<Long> ids, ReportComparisonSnapshot evidence) {
        var source = new WeeklyReportInput.DailySource(null, date, "입력", "저장 입력", null, ids, evidence);
        return NewsReport.builder().id(id).reportScope(ReportScope.WEEKLY).topicId(topicId)
                .reportDate(date).reportEndDate(date.plusDays(6)).reflectedFindingIds(ids)
                .weeklyInput(new WeeklyReportInput(date, date.plusDays(6), List.of(source), List.of(), topicId, "주제"))
                .build();
    }

    private ReportComparisonSnapshot snapshot(ReportComparisonSnapshot.Issue... issues) {
        return new ReportComparisonSnapshot(1, List.of(issues), List.of());
    }

    private ReportComparisonSnapshot.Issue identity(long findingId, long issueId, long topicId) {
        return new ReportComparisonSnapshot.Issue(findingId, "hash", "prompt", "2026-09-21T00:00:00",
                new ReportChanges.Side(issueId, topicId, "제목", "요약", List.of()));
    }

    private Item item(String key, List<Long> ids, List<Topic> topics) {
        return new Item(null, null, null, List.of(), null, null, null, null, null, null, null,
                new EventContext(key, 0, "오류 이벤트", "오류 요약", "오류 영향", ids, topics, List.of(), null));
    }

    private Feedback confirmed(long reportId, String key) {
        return review(reportId, key, Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null, null, null);
    }

    private Feedback review(long reportId, String key, Category category, Status status, String verdict,
                            Long capabilityId, Long recipientId, Long itemId) {
        return new Feedback(1, capabilityId, recipientId, reportId, itemId, category, "의견", false,
                "request", status, verdict, null, monday.plusWeeks(1).atStartOfDay(), null, key);
    }
    private Feedback withInput(Feedback review, Item input) {
        return new Feedback(review.id(), review.capabilityId(), review.recipientId(), review.reportId(), review.itemId(),
                review.category(), review.comment(), review.allowPersonalization(), review.requestHash(), review.status(),
                review.verdict(), review.diagnosis(), review.createdAt(), input, review.eventKey());
    }
}
