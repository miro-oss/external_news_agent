package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.feedback.model.FeedbackModels.*;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.dto.req.ReportWatchItemPromotionRequest;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportWatchItemPromotionTest {
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final FeedbackStore store = mock(FeedbackStore.class);
    private final DailyReportFeedbackExclusions inherited = mock(DailyReportFeedbackExclusions.class);
    private final ReportEventSnapshotFactory snapshots = new ReportEventSnapshotFactory(mock(JdbcTemplate.class), new ObjectMapper());
    private final ReportEventFeedbackProjection projection = new ReportEventFeedbackProjection(store, snapshots, inherited);
    private final ReportWatchItemPromotionService service = new ReportWatchItemPromotionService(reports, findings, relevance, projection);
    private final ReportContent.WatchItem watch = new ReportContent.WatchItem("공급 일정 확인", "공식 발표가 나면 일정을 다시 확인합니다.", List.of(12L));
    private final ReportContent.WatchItem another = new ReportContent.WatchItem("계약 이행 확인", "계약이 이행되는지 확인해야 합니다.", List.of(13L));
    private final ReportContent original = new ReportContent(List.of("저장 요약"), List.of(
            new ReportContent.ImportantEvent("기존 사건", "기존 내용", "기존 의미", List.of(11L))), List.of(watch, another), List.of("저장 참고"));
    private final NewsReport report = NewsReport.builder().id(17L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.GENERATED)
            .title("보고서").markdownBody("저장 원본").structuredContent(original).reflectedFindingIds(List.of(11L, 12L, 13L)).build();
    private final List<Finding> all = List.of(finding(11), finding(12), finding(13));

    @BeforeEach void setup() {
        when(reports.findByIdForUpdate(17L)).thenReturn(Optional.of(report));
        when(findings.findForReportByIdIn(any())).thenReturn(all);
        when(relevance.filterFindings(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void promotionSurvivesReloadAndKeepsOriginalTextEvidenceAndRemainingWatch() {
        assertEquals(17L, service.promote(17, request(watch)).reportId());
        var view = current();
        assertEquals(List.of(original.importantEvents().getFirst(), ReportWatchItemPromotions.event(watch)), view.readingContent().structuredContent().importantEvents());
        assertEquals(List.of(another), view.readingContent().structuredContent().watchItems());
        assertEquals(List.of("저장 요약"), view.readingContent().structuredContent().executiveSummary());
        assertTrue(view.readingContent().markdownBody().contains("### 공급 일정 확인"));
        assertTrue(view.readingContent().markdownBody().contains(watch.reason()));
        assertTrue(view.readingContent().markdownBody().contains("저장 참고"));
        assertSame(original, report.getStructuredContent());
        assertEquals("저장 원본", report.getMarkdownBody());
        assertEquals(List.of(11L, 12L, 13L), report.getReflectedFindingIds());
        assertEquals(view.readingContent(), projection.projectForDelivery(report, visible(), List.of()).readingContent());
        var reloaded = NewsReport.builder().id(17L).structuredContent(original).markdownBody("저장 원본")
                .promotedWatchItemKeys(List.copyOf(report.getPromotedWatchItemKeys())).build();
        assertEquals(view.readingContent(), ReportReadingContent.from(reloaded, visible()));
        verify(reports).findByIdForUpdate(17L);
    }

    @Test void retriesAndSeveralSelectionsRemainUniqueAndPreserveExistingFeedbackKeys() {
        var before = snapshots.capture(report, visible());
        service.promote(17, request(another));
        var first = snapshots.capture(report, visible());
        service.promote(17, request(another));
        service.promote(17, request(watch));
        var after = snapshots.capture(report, visible());
        assertEquals(2, report.getPromotedWatchItemKeys().size());
        assertEquals(List.of("기존 사건", another.topic(), watch.topic()), after.stream().map(item -> item.event().title()).toList());
        assertEquals(before.getFirst().event().key(), after.getFirst().event().key());
        assertEquals(first.get(1).event().key(), after.get(1).event().key());
        assertEquals(watch.sourceFindingIds(), after.get(2).event().sourceFindingIds());
        assertTrue(current().readingContent().structuredContent().watchItems().isEmpty());
    }

    @Test void changedTextOrderAndUnavailableEvidenceAreRejectedBeforeSaving() {
        conflict(new ReportWatchItemPromotionRequest(watch.topic(), "변경된 내용", watch.sourceFindingIds()));
        conflict(new ReportWatchItemPromotionRequest(watch.topic(), watch.reason(), List.of(12L, 13L)));
        when(findings.findForReportByIdIn(any())).thenReturn(List.of(all.getFirst(), all.get(2)));
        conflict(request(watch));
        assertTrue(report.getPromotedWatchItemKeys().isEmpty());
    }

    @Test void inheritedAndSubmittedExclusionsCannotBePromotedOrRetried() {
        when(inherited.forReport(report)).thenReturn(Set.of(12L));
        conflict(request(watch));
        when(inherited.forReport(report)).thenReturn(Set.of());
        service.promote(17, request(watch));
        var promoted = snapshots.capture(report, visible()).getLast();
        when(store.eventFeedback(17L)).thenReturn(List.of(review(promoted)));
        conflict(request(watch));
        assertEquals(List.of(original.importantEvents().getFirst()), current().readingContent().structuredContent().importantEvents());
    }

    @Test void unrelatedPromotionDoesNotHideASurvivingEventWithSharedRejectedEvidence() {
        var rejected = new ReportContent.ImportantEvent("제외할 사건", "제외할 내용", "", List.of(11L));
        var survivor = new ReportContent.ImportantEvent("남길 사건", "남길 내용", "", List.of(11L, 13L));
        report.recordStructuredContent(new ReportContent(List.of(), List.of(rejected, survivor), List.of(watch), List.of()));
        var before = snapshots.capture(report, visible());
        when(store.eventFeedback(17L)).thenReturn(List.of(review(before.getFirst())));
        service.promote(17, request(watch));
        assertEquals(List.of(survivor, ReportWatchItemPromotions.event(watch)), current().readingContent().structuredContent().importantEvents());
        assertEquals(before.get(1).event().key(), projection.events(report, visible(), projection.reviews(17)).getFirst().event().key());
    }

    @Test void replacementCannotRebindASelectionToAnotherWatchAtTheSamePosition() {
        service.promote(17, request(watch));
        report.recordStructuredContent(new ReportContent(List.of(), List.of(), List.of(another), List.of()));
        assertTrue(current().readingContent().structuredContent().importantEvents().isEmpty());
        assertEquals(List.of(another), current().readingContent().structuredContent().watchItems());
        report.recordStructuredContent(new ReportContent(List.of(), List.of(), List.of(another, watch), List.of()));
        assertEquals(List.of(ReportWatchItemPromotions.event(watch)), current().readingContent().structuredContent().importantEvents());
    }

    @Test void invalidRequestsNeverAcquireAReportLock() {
        for (var request : List.of(new ReportWatchItemPromotionRequest("", watch.reason(), List.of(12L)),
                new ReportWatchItemPromotionRequest(watch.topic(), watch.reason(), List.of()),
                new ReportWatchItemPromotionRequest(watch.topic(), watch.reason(), List.of(12L, 12L)),
                new ReportWatchItemPromotionRequest(watch.topic(), watch.reason(), List.of(-1L)))) {
            assertEquals(GeneralErrorCode.BAD_REQUEST, assertThrows(GeneralException.class, () -> service.promote(17, request)).getCode());
        }
        assertEquals(GeneralErrorCode.BAD_REQUEST, assertThrows(GeneralException.class, () -> service.promote(0, request(watch))).getCode());
        verifyNoInteractions(reports);
    }

    @Test void legacyAndUnavailableReportsAreNotPromotable() {
        report.recordStructuredContent(null);
        conflict(request(watch));
        report.hide(LocalDateTime.now());
        assertEquals("REPORT404", assertThrows(GeneralException.class, () -> service.promote(17, request(watch))).getCode().getCode());
        assertEquals("REPORT404", assertThrows(GeneralException.class, () -> service.promote(999, request(watch))).getCode().getCode());
    }

    private ReportFindings.Visible visible() { return ReportFindings.loadVisible(report, findings, relevance); }
    private ReportEventFeedbackProjection.View current() { return projection.project(report, visible(), projection.reviews(17)); }
    private void conflict(ReportWatchItemPromotionRequest request) {
        assertEquals(GeneralErrorCode.CONFLICT, assertThrows(GeneralException.class, () -> service.promote(17, request)).getCode());
    }
    private static ReportWatchItemPromotionRequest request(ReportContent.WatchItem item) {
        return new ReportWatchItemPromotionRequest(item.topic(), item.reason(), item.sourceFindingIds());
    }
    private static Finding finding(long id) {
        return Finding.builder().id(id).article(Article.builder().id(id + 100).title("기사 " + id).body("확보한 기사 원문 " + id)
                .fetchStatus(FetchStatus.FULLTEXT).build()).summary("분석 " + id).build();
    }
    private static Feedback review(Item item) {
        return new Feedback(1L, null, null, 17L, null, Category.SUMMARY_ERROR, "의견", false, "hash", Status.PENDING,
                null, null, LocalDateTime.of(2026, 11, 9, 12, 0), item, item.event().key());
    }
}
