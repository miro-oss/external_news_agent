package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.feedback.model.FeedbackModels.*;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.reports.dto.req.ReportWatchItemPromotionRequest;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportWatchItemFilteredPromotionTest {
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final FeedbackStore feedback = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory snapshots = new ReportEventSnapshotFactory(mock(JdbcTemplate.class), new ObjectMapper());
    private final ReportEventFeedbackProjection projection = new ReportEventFeedbackProjection(feedback, snapshots,
            mock(DailyReportFeedbackExclusions.class));
    private final ReportWatchItemPromotionService service = new ReportWatchItemPromotionService(reports, findings, relevance, projection);
    private final ReportContent.WatchItem target = new ReportContent.WatchItem("공급 일정 확인",
            "공식 발표가 나면 일정을 다시 확인합니다.", List.of(12L, 13L));
    private final ReportContent.WatchItem remaining = new ReportContent.WatchItem("계약 이행 확인",
            "계약이 이행되는지 확인해야 합니다.", List.of(14L));
    private final ReportContent.WatchItem unbacked = new ReportContent.WatchItem("근거 없는 관찰", "표시하면 안 되는 항목", List.of());
    private final ReportContent stored = new ReportContent(List.of("원래 요약"),
            List.of(new ReportContent.ImportantEvent("기존 사건", "기존 내용", "", List.of(11L))),
            List.of(target, remaining, unbacked), List.of("원래 참고"));

    @ParameterizedTest
    @EnumSource(ReportScope.class)
    void promotedWatchSurvivesUnrelatedFullTextLossInWebAndDelivery(ReportScope scope) {
        var report = report(scope);
        useSources(Set.of());
        service.promote(17, request());

        useSources(Set.of(11L));

        var visible = visible(report);
        assertTrue(visible.filtered());
        var web = projection.project(report, visible, List.of());
        assertTrue(web.readingContent().structuredContent().importantEvents().contains(ReportWatchItemPromotions.event(target)));
        assertEquals(List.of(remaining), web.readingContent().structuredContent().watchItems());
        assertTrue(web.readingContent().markdownBody().contains(target.reason()));
        assertFalse(web.readingContent().markdownBody().contains(unbacked.topic()));
        assertEquals(web.readingContent(), projection.projectForDelivery(report, visible, List.of()).readingContent());
        assertSame(stored, report.getStructuredContent());
        assertEquals("원래 본문", report.getMarkdownBody());
    }

    @ParameterizedTest
    @EnumSource(ReportScope.class)
    void alreadyFilteredReportsAllowPromotionWithoutChangingExistingEventKeys(ReportScope scope) {
        var report = report(scope);
        useSources(Set.of(11L));
        var visible = visible(report);
        var revision = ReportReadingContent.original(report, visible);
        var before = snapshots.capture(report, visible);
        assertEquals(List.of(target, remaining), ReportReadingContent.from(report, visible).structuredContent().watchItems());
        assertTrue(revision.structuredContent().watchItems().isEmpty(), "the established feedback hash baseline stays unchanged");

        service.promote(17, request());
        service.promote(17, request());

        var after = snapshots.capture(report, visible);
        assertEquals(before.stream().map(item -> item.event().key()).toList(),
                after.subList(0, before.size()).stream().map(item -> item.event().key()).toList());
        assertEquals(target.topic(), after.getLast().event().title());
        assertEquals(target.sourceFindingIds(), after.getLast().event().sourceFindingIds());
        assertEquals(revision, ReportReadingContent.original(report, visible));
        assertEquals(1, report.getPromotedWatchItemKeys().size());
    }

    @ParameterizedTest
    @EnumSource(ReportScope.class)
    void losingAnyOwnSourceHidesTheWholePromotedWatchAndRejectsRetry(ReportScope scope) {
        var report = report(scope);
        useSources(Set.of());
        service.promote(17, request());

        useSources(Set.of(11L, 13L));

        var visible = visible(report);
        var web = projection.project(report, visible, List.of());
        assertFalse(web.readingContent().structuredContent().importantEvents().contains(ReportWatchItemPromotions.event(target)));
        assertEquals(List.of(remaining), web.readingContent().structuredContent().watchItems());
        assertFalse(web.readingContent().markdownBody().contains(target.topic()));
        assertFalse(web.readingContent().markdownBody().contains(target.reason()));
        assertEquals(web.readingContent(), projection.projectForDelivery(report, visible, List.of()).readingContent());
        assertEquals(GeneralErrorCode.CONFLICT,
                assertThrows(GeneralException.class, () -> service.promote(17, request())).getCode());
        assertSame(stored, report.getStructuredContent());
    }

    @ParameterizedTest
    @EnumSource(ReportScope.class)
    void submittedPromotionStaysExcludedAfterAnotherSourceChangesTheRevision(ReportScope scope) {
        var report = report(scope);
        useSources(Set.of());
        service.promote(17, request());
        var submitted = snapshots.capture(report, visible(report)).getLast();
        var review = new Feedback(1L, null, null, 17L, null, Category.SUMMARY_ERROR, "의견", false, "hash", Status.PENDING,
                null, null, LocalDateTime.of(2026, 11, 9, 12, 0), submitted, submitted.event().key());
        when(feedback.eventFeedback(17L)).thenReturn(List.of(review));

        useSources(Set.of(11L));

        var visible = visible(report);
        var web = projection.project(report, visible, List.of(review));
        assertFalse(web.readingContent().markdownBody().contains(target.topic()));
        assertFalse(web.readingContent().markdownBody().contains(target.reason()));
        assertEquals(List.of(remaining), web.readingContent().structuredContent().watchItems());
        assertTrue(web.excludedEvidenceFindingIds().containsAll(target.sourceFindingIds()));
        assertEquals(web.readingContent(), projection.projectForDelivery(report, visible, List.of(review)).readingContent());
        assertEquals(GeneralErrorCode.CONFLICT,
                assertThrows(GeneralException.class, () -> service.promote(17, request())).getCode());
    }

    private NewsReport report(ReportScope scope) {
        var report = NewsReport.builder().id(17L).reportScope(scope).reportStatus(ReportStatus.GENERATED)
                .run(scope == ReportScope.RUN ? CollectionRun.builder().id(42L).build() : null)
                .title("보고서").markdownBody("원래 본문").structuredContent(stored)
                .reflectedFindingIds(List.of(11L, 12L, 13L, 14L)).build();
        when(reports.findByIdForUpdate(17L)).thenReturn(Optional.of(report));
        when(relevance.filterFindings(any())).thenAnswer(call -> call.getArgument(0));
        return report;
    }

    private void useSources(Set<Long> hidden) {
        var all = List.of(11L, 12L, 13L, 14L).stream().map(id -> finding(id, hidden.contains(id))).toList();
        when(findings.findForReportByRunId(42L)).thenReturn(all);
        when(findings.findForReportByIdIn(any())).thenReturn(all);
    }

    private ReportFindings.Visible visible(NewsReport report) { return ReportFindings.loadVisible(report, findings, relevance); }

    private ReportWatchItemPromotionRequest request() {
        return new ReportWatchItemPromotionRequest(target.topic(), target.reason(), target.sourceFindingIds());
    }

    private Finding finding(long id, boolean hidden) {
        return Finding.builder().id(id).analysisSource(AnalysisSource.LLM)
                .sensitivity(FindingSensitivity.builder().score(BigDecimal.ZERO).build()).relevance(Relevance.IMPORTANT)
                .article(Article.builder().id(id + 100).title("기사 " + id).body(hidden ? null : "확보한 원문 " + id)
                        .fetchStatus(hidden ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT).build())
                .summary("확인한 요약 " + id).keyPoints(List.of(new FindingKeyPoint("확인한 주장 " + id, List.of(0), "grounded"))).build();
    }
}
