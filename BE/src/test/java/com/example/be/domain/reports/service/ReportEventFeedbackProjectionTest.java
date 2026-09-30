package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportEventFeedbackProjectionTest {
    private final FeedbackStore store = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory snapshots = mock(ReportEventSnapshotFactory.class);
    private final ReportEventFeedbackProjection projection = new ReportEventFeedbackProjection(store, snapshots);

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
}
