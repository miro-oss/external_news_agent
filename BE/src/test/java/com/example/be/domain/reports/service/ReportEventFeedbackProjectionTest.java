package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportEventFeedbackProjectionTest {
    private final FeedbackStore store = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory snapshots = mock(ReportEventSnapshotFactory.class);
    private final DailyReportFeedbackExclusions dailyExclusions = mock(DailyReportFeedbackExclusions.class);
    private final ReportEventFeedbackProjection projection = new ReportEventFeedbackProjection(store, snapshots, dailyExclusions);

    @Test void inheritedDailyErrorsAndLocalReviewsRemoveWeeklyEventsWithoutRebindingSurvivors() {
        var inherited = event("다시 요약된 오류 이슈", "오류 주간 요약", 11L, 14L);
        var good = event("남길 사건", "남길 요약", 12L);
        var local = event("주간에서 검토한 오류", "주간 오류 요약", 13L);
        var content = new ReportContent(List.of("오류가 섞인 핵심 요약"), List.of(inherited, good, local),
                List.of(new ReportContent.WatchItem("오류 후속", "오류 이유", List.of(11L)),
                        new ReportContent.WatchItem("남길 후속", "남길 이유", List.of(12L))), List.of("오류 참고"));
        var report = report(content);
        var visible = visible(11L, 12L, 13L, 14L);
        when(dailyExclusions.forReport(report)).thenReturn(Set.of(11L));
        var originalGood = item('b', 1, good);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('a', 0, inherited), originalGood, item('c', 2, local)));
        var reviews = List.of(review('c', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null));

        var view = projection.project(report, visible, reviews);

        assertEquals(List.of(12L), view.findings().stream().map(Finding::getId).toList());
        assertEquals(List.of(good), view.readingContent().structuredContent().importantEvents());
        assertEquals(List.of("남길 요약"), view.readingContent().structuredContent().executiveSummary());
        assertFalse(view.readingContent().markdownBody().contains("오류"));
        assertTrue(view.excludedEvents());
        assertEquals(List.of(originalGood), projection.events(report, visible, reviews));
        assertEquals(1, originalGood.event().index());
        assertSame(content, report.getStructuredContent());
    }

    @Test void inheritedErrorAlsoRemovesEvidenceOnlyPresentInFallbackAndWatches() {
        var good = event("남길 사건", "남길 요약", 12L);
        var report = report(new ReportContent(List.of("과거 오류 요약"), List.of(good),
                List.of(new ReportContent.WatchItem("과거 오류 후속", "과거 오류 이유", List.of(11L))), List.of()));
        when(dailyExclusions.forReport(report)).thenReturn(Set.of(11L));

        var view = projection.project(report, visible(11L, 12L), List.of());

        assertEquals(List.of(12L), view.findings().stream().map(Finding::getId).toList());
        assertTrue(view.readingContent().structuredContent().watchItems().isEmpty());
        assertFalse(view.readingContent().markdownBody().contains("오류"));
        verifyNoInteractions(snapshots);
    }

    @Test void legacyWeeklyMarkdownIsRebuiltAfterInheritedExclusionAndCanStillBePersonalized() {
        var report = report(null);
        var visible = visible(11L, 12L);
        when(dailyExclusions.forReport(report)).thenReturn(Set.of(11L));

        var view = projection.project(report, visible, List.of());
        assertEquals(List.of(12L), view.findings().stream().map(Finding::getId).toList());
        assertTrue(view.excludedEvents());
        assertNull(view.readingContent().structuredContent());
        assertFalse(view.readingContent().markdownBody().contains("잘못된"));
        var personal = view.suppress(report, Set.of(12L));
        assertTrue(personal.findings().isEmpty());
        assertFalse(personal.readingContent().markdownBody().contains("잘못된"));
        verifyNoInteractions(snapshots);
    }

    @Test void inheritedErrorsPruneDroppedWatchCompanionsButKeepEvidenceUsedBySurvivingContent() {
        var good = event("정상 사건", "정상 요약", 13L);
        var goodWatch = new ReportContent.WatchItem("정상 관찰", "정상 이유", List.of(14L));
        var report = report(new ReportContent(List.of("과거 오류 요약"), List.of(good), List.of(
                new ReportContent.WatchItem("오류 관찰", "오류 이유", List.of(11L, 12L, 13L, 14L)),
                goodWatch), List.of()));
        when(dailyExclusions.forReport(report)).thenReturn(Set.of(11L));

        var view = projection.project(report, visible(11L, 12L, 13L, 14L, 15L), List.of());

        assertEquals(List.of(13L, 14L, 15L), view.findings().stream().map(Finding::getId).toList());
        assertEquals(List.of(goodWatch), view.readingContent().structuredContent().watchItems());
        assertEquals(List.of(good), view.readingContent().structuredContent().importantEvents());
        assertFalse(view.readingContent().markdownBody().contains("오류"));
    }

    @Test void personalSuppressionPrunesCompanionsOfDroppedWatchWithoutRevivingFallbackClaims() {
        var watch = new ReportContent.WatchItem("관찰", "관찰 이유", List.of(12L, 13L, 14L));
        var retainedWatch = new ReportContent.WatchItem("남길 관찰", "남길 이유", List.of(14L));
        var content = new ReportContent(List.of(), List.of(), List.of(watch, retainedWatch), List.of());
        var report = report(content);
        var view = new ReportEventFeedbackProjection.View(visible(12L, 13L, 14L, 15L).findings(),
                new ReportReadingContent("관찰", content), true);

        var personal = view.suppress(report, Set.of(12L));

        assertEquals(List.of(14L, 15L), personal.findings().stream().map(Finding::getId).toList());
        assertEquals(List.of(retainedWatch), personal.readingContent().structuredContent().watchItems());
        assertTrue(personal.readingContent().structuredContent().importantEvents().isEmpty());
    }

    @Test void batchReviewsGroupByReportWithoutLosingReviewOrder() {
        var first = review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null);
        var second = review('b', Category.WRONG_CLUSTER, Status.PENDING, null, null);
        var other = new Feedback(2L, null, null, 18L, null, first.category(), first.comment(), false,
                first.requestHash(), first.status(), first.verdict(), null, first.createdAt(), null, first.eventKey());
        when(store.eventReviewStates(List.of(17L, 18L, 19L))).thenReturn(List.of(first, other, second));

        assertEquals(Map.of(17L, List.of(first, second), 18L, List.of(other)),
                projection.reviewsByReport(List.of(17L, 18L, 19L)));

        verify(store, times(1)).eventReviewStates(List.of(17L, 18L, 19L));
        verify(store, never()).eventFeedback(anyLong());
        verifyNoInteractions(snapshots);
    }

    @Test void emptyBatchDoesNotReadFeedback() {
        assertTrue(projection.reviewsByReport(List.of()).isEmpty());
        verifyNoInteractions(store, snapshots);
    }

    @Test void onlyCompletedLocalFactErrorsHideAnEventAndItsExclusiveEvidence() {
        var bad = event("잘못된 사건", "잘못된 요약", 11L, 12L);
        var good = event("남길 사건", "남길 요약", 13L);
        var content = new ReportContent(List.of("잘못된 종합 요약"), List.of(bad, good), List.of(
                new ReportContent.WatchItem("잘못된 관찰", "잘못된 이유", List.of(12L)),
                new ReportContent.WatchItem("남길 관찰", "남길 이유", List.of(13L)),
                new ReportContent.WatchItem("출처 없는 관찰", "검증할 수 없는 이유", List.of())), List.of("잘못된 출처 설명"));
        var report = report(content);
        var visible = visible(11L, 12L, 13L, 14L);
        var originalBad = item('a', 0, bad);
        var originalGood = item('b', 1, good);
        when(snapshots.capture(report, visible)).thenReturn(List.of(originalBad, originalGood));
        var reviews = List.of(review('a', Category.WRONG_CLUSTER, Status.COMPLETED, "CONFIRMED_ERROR", null));

        var view = projection.project(report, visible, reviews);
        assertEquals(List.of(13L, 14L), view.findings().stream().map(Finding::getId).toList());
        assertEquals(List.of(good), view.readingContent().structuredContent().importantEvents());
        assertEquals(List.of("남길 요약"), view.readingContent().structuredContent().executiveSummary());
        assertEquals(List.of(content.watchItems().get(1)), view.readingContent().structuredContent().watchItems());
        assertTrue(view.readingContent().structuredContent().sourceNotes().isEmpty());
        assertFalse(view.readingContent().markdownBody().contains("잘못된"));
        assertFalse(view.readingContent().markdownBody().contains("출처 없는"));
        assertTrue(view.readingContent().markdownBody().contains("남길 사건"));
        assertTrue(view.readingContent().markdownBody().contains("남길 이유"));
        assertEquals(List.of(originalGood), projection.events(report, visible, reviews));
        assertEquals(1, originalGood.event().index(), "saved review identity keeps the original index");
        assertSame(content, report.getStructuredContent());
        assertEquals("잘못된 원본 마크다운", report.getMarkdownBody());
        assertEquals(List.of(11L, 12L, 13L, 14L), visible.findings().stream().map(Finding::getId).toList());
        verifyNoInteractions(store);
    }

    @Test void sharedEvidenceStaysWithAnotherValidEventButDoesNotKeepItsRejectedWatch() {
        var bad = event("잘못된 묶음", "잘못된 요약", 11L, 12L);
        var good = event("정상 사건", "정상 요약", 12L, 13L);
        var content = new ReportContent(List.of(), List.of(bad, good), List.of(
                new ReportContent.WatchItem("의심 관찰", "오류 사건을 반복", List.of(12L))), List.of());
        var report = report(content);
        var visible = visible(11L, 12L, 13L);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('a', 0, bad), item('b', 1, good)));
        var view = projection.project(report, visible,
                List.of(review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null)));
        assertEquals(List.of(12L, 13L), view.findings().stream().map(Finding::getId).toList());
        assertEquals(List.of(12L, 13L), view.readingContent().structuredContent().importantEvents().getFirst().sourceFindingIds());
        assertTrue(view.readingContent().structuredContent().watchItems().isEmpty());
        assertEquals(Set.of(11L, 12L), view.excludedEvidenceFindingIds());
    }

    @Test void everySubmittedLocalOpinionIsDeliveryOnlyRegardlessOfCategoryOrReviewState() {
        var rejected = event("의견 보낸 사건", "전달 제외 요약", 11L, 12L);
        var retained = event("남길 사건", "남길 요약", 12L, 13L);
        var content = new ReportContent(List.of("전달 제외 내용이 섞인 요약"), List.of(rejected, retained),
                List.of(new ReportContent.WatchItem("제외 후속", "후속 이유", List.of(12L))), List.of());
        var report = report(content);
        var visible = visible(11L, 12L, 13L);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('a', 0, rejected), item('b', 1, retained)));
        for (Category category : Category.values()) for (Status status : Status.values()) {
            var submitted = review('a', category, status, null, null);
            var delivered = projection.projectForDelivery(report, visible, List.of(submitted));
            assertEquals(List.of(retained), delivered.readingContent().structuredContent().importantEvents());
            assertEquals(List.of("남길 요약"), delivered.readingContent().structuredContent().executiveSummary());
            assertTrue(delivered.readingContent().structuredContent().watchItems().isEmpty());
            assertEquals(List.of(12L, 13L), delivered.findings().stream().map(Finding::getId).toList());
            assertEquals(Set.of(11L, 12L), delivered.excludedEvidenceFindingIds());
            assertSame(content, projection.project(report, visible, List.of(submitted)).readingContent().structuredContent());
            var personal = delivered.suppress(report, Set.of(13L));
            assertTrue(personal.findings().isEmpty());
            assertEquals(Set.of(11L, 12L, 13L), personal.excludedEvidenceFindingIds());
        }
        assertSame(content, report.getStructuredContent());
    }

    @Test void changedEventRevisionStillOmitsVerifiedOriginalEvidenceOnlyForDelivery() {
        var revised = event("문구가 바뀐 제출 사건", "새 문구", 11L);
        var retained = event("다른 사건", "다른 요약", 12L);
        var content = new ReportContent(List.of("원래 요약"), List.of(revised, retained), List.of(), List.of());
        var report = report(content);
        var visible = visible(11L, 12L);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('b', 0, revised), item('c', 1, retained)));
        var submitted = withInput(review('a', Category.PREFERENCE, Status.FAILED, null, null),
                item('a', 0, event("원래 제출 사건", "원래 요약", 11L)), 17L);

        var delivered = projection.projectForDelivery(report, visible, List.of(submitted));

        assertEquals(List.of(retained), delivered.readingContent().structuredContent().importantEvents());
        assertEquals(List.of(12L), delivered.findings().stream().map(Finding::getId).toList());
        assertEquals(Set.of(11L), delivered.excludedEvidenceFindingIds());
        assertSame(content, projection.project(report, visible, List.of(submitted)).readingContent().structuredContent());
    }

    @Test void staleInputMustBelongToTheSameLocalReportAndEvent() {
        var event = event("그대로 남길 사건", "요약", 11L);
        var content = new ReportContent(List.of("요약"), List.of(event), List.of(), List.of());
        var report = report(content);
        var visible = visible(11L);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('b', 0, event)));
        var local = review('a', Category.PREFERENCE, Status.COMPLETED, "PREFERENCE", null);
        for (var invalid : List.of(local,
                withInput(local, item('c', 0, event), 17L),
                withInput(local, item('a', 0, event), 18L),
                withInput(review('a', Category.PREFERENCE, Status.PENDING, null, 99L), item('a', 0, event), 17L))) {
            var delivered = projection.projectForDelivery(report, visible, List.of(invalid));
            assertSame(content, delivered.readingContent().structuredContent());
            assertTrue(delivered.excludedEvidenceFindingIds().isEmpty());
        }
    }

    @Test void personalSuppressionKeepsProjectedEventsWithoutRegeneratingRejectedSharedClaims() {
        var bad = event("잘못된 묶음", "잘못된 요약", 11L, 12L);
        var good = event("정상 사건", "정상 요약", 12L);
        var personal = event("개인 기준으로 숨길 사건", "개인 기준으로 숨길 요약", 13L);
        var content = new ReportContent(List.of("잘못된 종합 요약"), List.of(bad, good, personal), List.of(
                new ReportContent.WatchItem("숨길 관찰", "개인 기준", List.of(13L))), List.of());
        var report = report(content);
        var visible = visible(11L, 12L, 13L);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('a', 0, bad), item('b', 1, good), item('c', 2, personal)));
        var original = projection.project(report, visible,
                List.of(review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null)));

        assertSame(original, original.suppress(report, Set.of(999L)));
        var personalized = original.suppress(report, Set.of(13L));
        assertEquals(List.of(12L), personalized.findings().stream().map(Finding::getId).toList());
        assertEquals(List.of(good), personalized.readingContent().structuredContent().importantEvents());
        assertEquals(List.of("정상 요약"), personalized.readingContent().structuredContent().executiveSummary());
        assertTrue(personalized.readingContent().structuredContent().watchItems().isEmpty());
        assertFalse(personalized.readingContent().markdownBody().contains("잘못된"));
        assertFalse(personalized.readingContent().markdownBody().contains("개인 기준"));
        assertEquals(List.of(good, personal), original.readingContent().structuredContent().importantEvents());
        assertSame(content, report.getStructuredContent());

        var empty = personalized.suppress(report, Set.of(12L));
        assertTrue(empty.findings().isEmpty());
        assertTrue(empty.readingContent().structuredContent().importantEvents().isEmpty());
        assertTrue(empty.readingContent().structuredContent().executiveSummary().isEmpty());
        assertFalse(empty.readingContent().markdownBody().contains("정상 요약"));
    }

    @Test void personalSuppressionRemovesOrphanEvidenceFromRejectedAndNowUnsupportedEvents() {
        var bad = event("잘못된 묶음", "잘못된 요약", 11L, 12L);
        var good = event("정상 결합 사건", "정상 결합 요약", 12L, 13L);
        var content = new ReportContent(List.of("원래 요약"), List.of(bad, good), List.of(), List.of());
        var report = report(content);
        var visible = visible(11L, 12L, 13L, 14L);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('a', 0, bad), item('b', 1, good)));
        var original = projection.project(report, visible,
                List.of(review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null)));

        var personalized = original.suppress(report, Set.of(13L));

        assertTrue(personalized.readingContent().structuredContent().importantEvents().isEmpty());
        assertTrue(personalized.readingContent().structuredContent().executiveSummary().isEmpty());
        assertEquals(List.of(14L), personalized.findings().stream().map(Finding::getId).toList(),
                "only the unrelated finding remains available to fallback content");
        assertEquals(List.of(12L, 13L, 14L), original.findings().stream().map(Finding::getId).toList());
    }

    @Test void pendingFailedUnconfirmedPreferencesAndRecipientReviewsDoNotChangeStoredReading() {
        var content = new ReportContent(List.of("원래 요약"), List.of(event("사건", "요약", 11L)), List.of(), List.of("원래 참고"));
        var report = report(content);
        var visible = visible(11L);
        for (var review : List.of(
                review('a', Category.SUMMARY_ERROR, Status.PENDING, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.PROCESSING, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.FAILED, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "NOT_CONFIRMED", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "INSUFFICIENT_EVIDENCE", null),
                review('a', Category.PREFERENCE, Status.COMPLETED, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", 99L))) {
            assertFalse(projection.hasConfirmedErrors(List.of(review)));
            var view = projection.project(report, visible, List.of(review));
            assertSame(content, view.readingContent().structuredContent());
            assertEquals(report.getMarkdownBody(), view.readingContent().markdownBody());
            assertEquals(visible.findings(), view.findings());
        }
        verifyNoInteractions(snapshots, store);
    }

    @Test void anOldRevisionErrorDoesNotHideAReplacementEventAndAllRejectedLeavesNoOldSummary() {
        var content = new ReportContent(List.of("오류 종합"), List.of(event("오류 사건", "오류 요약", 11L)), List.of(), List.of("오류 참고"));
        var report = report(content);
        var visible = visible(11L);
        when(snapshots.capture(report, visible)).thenReturn(List.of(item('b', 0, content.importantEvents().getFirst())));
        var old = List.of(review('a', Category.OTHER, Status.COMPLETED, "CONFIRMED_ERROR", null));
        assertSame(content, projection.project(report, visible, old).readingContent().structuredContent());
        assertEquals(1, projection.events(report, visible, old).size());
        var current = List.of(review('b', Category.OTHER, Status.COMPLETED, "CONFIRMED_ERROR", null));
        var view = projection.project(report, visible, current);
        assertTrue(view.findings().isEmpty());
        assertTrue(view.readingContent().structuredContent().importantEvents().isEmpty());
        assertTrue(view.readingContent().structuredContent().executiveSummary().isEmpty());
        assertFalse(view.readingContent().markdownBody().contains("오류"));
        assertTrue(projection.events(report, visible, current).isEmpty());
    }

    private static NewsReport report(ReportContent content) {
        return NewsReport.builder().id(17L).title("보고서").markdownBody("잘못된 원본 마크다운").structuredContent(content).build();
    }
    private static ReportFindings.Visible visible(Long... ids) {
        return new ReportFindings.Visible(java.util.Arrays.stream(ids).map(id -> Finding.builder().id(id).build()).toList(), false);
    }
    private static ReportContent.ImportantEvent event(String title, String summary, Long... ids) {
        return new ReportContent.ImportantEvent(title, summary, "중요한 이유", List.of(ids));
    }
    private static Item item(char key, int index, ReportContent.ImportantEvent event) {
        return new Item(null, null, null, List.of(), null, null, null, null, null, null, null,
                new EventContext(String.valueOf(key).repeat(64), index, event.title(), event.summaryKo(), event.significance(),
                        event.sourceFindingIds(), List.of(), List.of(), null));
    }
    private static Feedback review(char key, Category category, Status status, String verdict, Long recipient) {
        return new Feedback(1L, null, recipient, 17L, null, category, "의견", false, "hash", status, verdict, "검토 설명",
                LocalDateTime.of(2026, 10, 1, 12, 0), null, String.valueOf(key).repeat(64));
    }
    private static Feedback withInput(Feedback review, Item input, long reportId) {
        return new Feedback(review.id(), review.capabilityId(), review.recipientId(), reportId, review.itemId(),
                review.category(), review.comment(), review.allowPersonalization(), review.requestHash(), review.status(),
                review.verdict(), review.diagnosis(), review.createdAt(), input, review.eventKey());
    }
}
