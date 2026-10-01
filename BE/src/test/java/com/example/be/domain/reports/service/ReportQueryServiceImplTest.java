package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.agent.investigation.IssueInvestigationJdbcRepository;
import com.example.be.domain.analysis.agent.investigation.InvestigationTrace;
import com.example.be.domain.analysis.entity.AudienceRelevance;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingPerspectiveTag;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.feedback.model.FeedbackModels;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.notifications.repository.DeliveryLogRepository;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportQueryServiceImplTest {
    private final TopicRelevancePolicy relevancePolicy = TopicRelevanceTestSupport.legacyPolicy();

    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final NewsIssueRepository newsIssueRepository = mock(NewsIssueRepository.class);
    private final DeliveryLogRepository deliveryLogRepository = mock(DeliveryLogRepository.class);
    private final IssueInvestigationJdbcRepository investigationRepository =
            mock(IssueInvestigationJdbcRepository.class);
    private final FeedbackStore feedbackStore = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory eventSnapshots = mock(ReportEventSnapshotFactory.class);
    private final ReportEventFeedbackProjection feedbackProjection =
            new ReportEventFeedbackProjection(feedbackStore, eventSnapshots);
    private final ReportQueryServiceImpl service = new ReportQueryServiceImpl(
            reportRepository, findingRepository, issueArticleRepository,
            newsIssueRepository, deliveryLogRepository,
            com.example.be.domain.analysis.service.SensitivityCalculator.defaults(),
            investigationRepository, mock(com.example.be.domain.collection.repository.CollectionRunArticleRepository.class), relevancePolicy,
            feedbackProjection);

    @Test
    void latestReturnsNullWhenNoReportExists() {
        when(reportRepository.findFirstByReportStatusNotAndDeletedAtIsNullOrderByGeneratedAtDescIdDesc(ReportStatus.PENDING))
                .thenReturn(Optional.empty());

        assertNull(service.getLatest(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsZeroCountsWhenRunHasNoFindings() {
        com.example.be.domain.collection.entity.CollectionRun run =
                com.example.be.domain.collection.entity.CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder()
                .id(17L)
                .run(run)
                .title("보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0))
                .build();
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(report)));
        when(findingRepository.countForReports(
                eq(List.of(42L)), any(java.math.BigDecimal.class))).thenReturn(List.of());

        var result = service.getReports(null, null, 0, 20);

        ReportResDTO.Summary summary = result.getContent().getFirst();
        assertEquals(0, summary.getFindingCount());
        assertEquals("NOT_SENT", summary.getDeliveryStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullListPageFetchesReviewStatesOnceWithoutLoadingFindingsSnapshotsOrReportBodies() {
        List<NewsReport> reports = LongStream.rangeClosed(1, 100).mapToObj(id -> spy(NewsReport.builder()
                .id(id).run(CollectionRun.builder().id(1000L + id).build()).title("보고서 " + id)
                .generatedAt(LocalDateTime.of(2026, 10, 1, 10, 0)).build())).toList();
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(reports));
        when(feedbackStore.eventReviewStates(reports.stream().map(NewsReport::getId).toList()))
                .thenReturn(List.of());

        var result = service.getReports(null, null, 0, 100);

        assertEquals(100, result.getContent().size());
        verify(feedbackStore, times(1)).eventReviewStates(reports.stream().map(NewsReport::getId).toList());
        verify(feedbackStore, never()).eventFeedback(org.mockito.ArgumentMatchers.anyLong());
        verify(findingRepository, never()).findForReportByRunId(any());
        verify(findingRepository, never()).findForReportByIdIn(anyCollection());
        verifyNoInteractions(eventSnapshots);
        reports.forEach(report -> {
            verify(report, never()).getMarkdownBody();
            verify(report, never()).getStructuredContent();
            verify(report, never()).getWeeklyInput();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyListPageDoesNotReadFeedbackOrSnapshots() {
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertTrue(service.getReports(null, null, 0, 20).getContent().isEmpty());

        verifyNoInteractions(feedbackStore, eventSnapshots);
        verify(findingRepository, never()).findForReportByRunId(any());
        verify(findingRepository, never()).findForReportByIdIn(anyCollection());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReviewStatesOnlyRecalculateTheReportWithCompletedConfirmedFactErrors() {
        var stored = new ReportContent(List.of("원본 요약"), List.of(
                new ReportContent.ImportantEvent("잘못된 이슈", "오류 요약", "영향", List.of(1L)),
                new ReportContent.ImportantEvent("남은 이슈", "정상 요약", "영향", List.of(2L))), List.of(), List.of());
        List<NewsReport> reports = LongStream.rangeClosed(1, 8).mapToObj(id -> spy(NewsReport.builder()
                .id(id).reportScope(ReportScope.DAILY).reflectedFindingIds(List.of(1L, 2L))
                .title("보고서 " + id).structuredContent(stored).markdownBody("저장된 본문")
                .generatedAt(LocalDateTime.of(2026, 10, 1, 10, 0)).build())).toList();
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(reports));
        List<FindingRepository.DailyReportCount> counts = reports.stream().map(report -> {
            long reportId = report.getId();
            var count = mock(FindingRepository.DailyReportCount.class);
            when(count.getReportId()).thenReturn(reportId);
            when(count.getFindingCount()).thenReturn(2L);
            when(count.getHighSensitivityCount()).thenReturn(1L);
            return count;
        }).toList();
        List<Long> ids = reports.stream().map(NewsReport::getId).toList();
        when(findingRepository.countForDailyReports(eq(ids), any())).thenReturn(counts);
        // Every review intentionally shares the same event key, so an error cannot leak to another report.
        when(feedbackStore.eventReviewStates(ids)).thenReturn(List.of(
                eventReview(1L, FeedbackModels.Category.SUMMARY_ERROR, FeedbackModels.Status.PENDING, "CONFIRMED_ERROR"),
                eventReview(2L, FeedbackModels.Category.SUMMARY_ERROR, FeedbackModels.Status.PROCESSING, "CONFIRMED_ERROR"),
                eventReview(3L, FeedbackModels.Category.SUMMARY_ERROR, FeedbackModels.Status.FAILED, "CONFIRMED_ERROR"),
                eventReview(4L, FeedbackModels.Category.SUMMARY_ERROR, FeedbackModels.Status.COMPLETED, "NOT_CONFIRMED"),
                eventReview(5L, FeedbackModels.Category.SUMMARY_ERROR, FeedbackModels.Status.COMPLETED, "INSUFFICIENT_EVIDENCE"),
                eventReview(6L, FeedbackModels.Category.PREFERENCE, FeedbackModels.Status.COMPLETED, "CONFIRMED_ERROR"),
                eventReview(7L, FeedbackModels.Category.WRONG_CLUSTER, FeedbackModels.Status.COMPLETED, "CONFIRMED_ERROR")));
        when(findingRepository.findForReportByIdIn(List.of(1L, 2L))).thenReturn(List.of(
                finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT),
                finding(2L, SensitivityLevel.LOW, Relevance.REFERENCE)));
        NewsReport affected = reports.get(6);
        when(eventSnapshots.capture(eq(affected), any())).thenReturn(List.of(
                eventItem('a', 0, stored.importantEvents().getFirst()),
                eventItem('b', 1, stored.importantEvents().getLast())));

        var result = service.getReports(null, null, 0, 20);

        assertEquals(ids, result.getContent().stream().map(ReportResDTO.Summary::getId).toList());
        assertEquals(List.of(2L, 2L, 2L, 2L, 2L, 2L, 1L, 2L), result.getContent().stream()
                .map(ReportResDTO.Summary::getFindingCount).toList());
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L, 1L, 0L, 1L), result.getContent().stream()
                .map(ReportResDTO.Summary::getHighSensitivityCount).toList());
        verify(feedbackStore, times(1)).eventReviewStates(ids);
        verify(feedbackStore, never()).eventFeedback(org.mockito.ArgumentMatchers.anyLong());
        verify(findingRepository, times(1)).findForReportByIdIn(List.of(1L, 2L));
        verify(findingRepository, never()).findForReportByRunId(any());
        verify(eventSnapshots, times(1)).capture(eq(affected), any());
        reports.stream().filter(report -> report != affected).forEach(report -> {
            verify(eventSnapshots, never()).capture(eq(report), any());
            verify(report, never()).getMarkdownBody();
            verify(report, never()).getStructuredContent();
        });
    }

    private static FeedbackModels.Feedback eventReview(long reportId, FeedbackModels.Category category,
                                                       FeedbackModels.Status status, String verdict) {
        return new FeedbackModels.Feedback(reportId, null, null, reportId, null, category, "의견", false,
                "request-hash", status, verdict, null, LocalDateTime.of(2026, 10, 1, 10, 1), null, "a".repeat(64));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUsesSourceRunStartWithKoreanOffsetAndKeepsMissingDatesNull() {
        LocalDateTime generatedAt = LocalDateTime.of(2026, 9, 9, 0, 5);
        NewsReport run = NewsReport.builder().id(17L)
                .run(CollectionRun.builder().id(42L)
                        .startedAt(LocalDateTime.of(2026, 9, 8, 23, 55)).build())
                .generatedAt(generatedAt).build();
        NewsReport missingStart = NewsReport.builder().id(16L)
                .run(CollectionRun.builder().id(41L).build()).generatedAt(generatedAt).build();
        NewsReport missingRun = NewsReport.builder().id(15L).generatedAt(generatedAt).build();
        NewsReport daily = NewsReport.builder().id(14L).reportScope(ReportScope.DAILY)
                .reportDate(LocalDate.of(2026, 9, 8)).sourceRunIds(List.of(42L, 41L))
                .generatedAt(generatedAt).build();
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(run, missingStart, missingRun, daily),
                        PageRequest.of(1, 4), 12));

        var result = service.getReports(null, null, 1, 4);

        assertEquals(List.of(17L, 16L, 15L, 14L),
                result.getContent().stream().map(ReportResDTO.Summary::getId).toList());
        assertEquals(OffsetDateTime.parse("2026-09-08T23:55:00+09:00"),
                result.getContent().getFirst().getCollectionStartedAt());
        assertEquals(OffsetDateTime.parse("2026-09-09T00:05:00+09:00"),
                result.getContent().getFirst().getGeneratedAt());
        result.getContent().subList(1, 4).forEach(summary -> assertNull(summary.getCollectionStartedAt()));
        assertEquals(List.of(), result.getContent().get(2).getSourceRunIds());
        assertEquals(daily.getReportDate(), result.getContent().getLast().getReportDate());
        assertEquals(12, result.getTotalElements());
        assertEquals(1, result.getPage());
        assertEquals(4, result.getSize());
        assertEquals(3, result.getTotalPages());
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findAll(any(Specification.class), pageable.capture());
        assertEquals(Sort.by(Sort.Order.desc("generatedAt"), Sort.Order.desc("id")),
                pageable.getValue().getSort());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsSavedCollectionContextsForBothScopesAndEmptyContextsForLegacyReports() {
        var snapshot = new CollectionTopicSnapshot(29L, "접수 당시 HBM 시장", "HBM 반도체",
                List.of("HBM"), List.of("삼성전자"), List.of("채용"), 100, 1440);
        List<ReportCollectionContext> contexts = List.of(
                new ReportCollectionContext(42L, List.of(snapshot)));
        CollectionRun run = CollectionRun.builder().id(42L).build();
        LocalDateTime generatedAt = LocalDateTime.of(2026, 9, 9, 0, 5);
        NewsReport runReport = NewsReport.builder().id(17L).run(run)
                .collectionContexts(contexts).generatedAt(generatedAt).build();
        NewsReport dailyReport = NewsReport.builder().id(16L).reportScope(ReportScope.DAILY)
                .collectionContexts(contexts).generatedAt(generatedAt).build();
        NewsReport legacyReport = NewsReport.builder().id(15L).run(run)
                .generatedAt(generatedAt).build();
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(runReport, dailyReport, legacyReport)));
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(runReport));

        var summaries = service.getReports(null, null, 0, 20).getContent();

        assertEquals(contexts, summaries.get(0).getCollectionContexts());
        assertEquals(contexts, summaries.get(1).getCollectionContexts());
        assertEquals(List.of(), summaries.get(2).getCollectionContexts());
        assertEquals(service.getReport(17L, false).getCollectionContexts(),
                summaries.getFirst().getCollectionContexts());
    }

    @Test
    void rejectsInvertedPeriodWithSpecifiedMessage() {
        GeneralException exception = assertThrows(GeneralException.class,
                () -> service.getReports("2026-08-18", "2026-08-17", 0, 20));

        assertEquals("from은 to보다 이전이어야 합니다.", exception.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsEqualOffsetDateTimes() {
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var result = service.getReports(
                "2026-08-18T10:00:00+09:00", "2026-08-18T10:00:00+09:00", 0, 20);

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void parsesSupportedDateFormatsAndInclusiveDateBoundaries() {
        assertEquals(LocalDateTime.of(2026, 8, 18, 10, 0),
                service.parseDateTime("2026-08-18T01:00:00Z", false));
        assertEquals(LocalDateTime.of(2026, 8, 18, 10, 0),
                service.parseDateTime("2026-08-18T10:00:00", false));
        assertEquals(LocalDateTime.of(2026, 8, 18, 0, 0),
                service.parseDateTime("2026-08-18", false));
        assertEquals(LocalDateTime.of(2026, 8, 18, 23, 59, 59, 999_999_999),
                service.parseDateTime("2026-08-18", true));
    }

    @Test
    void rejectsInvalidDateFormat() {
        assertThrows(GeneralException.class, () -> service.parseDateTime("18/08/2026", false));
    }

    @Test
    void topicExclusionSanitizesStoredBodyEvenWhenFindingsAreNotRequested() {
        var run = CollectionRun.builder().id(42L).build();
        var report = NewsReport.builder().id(17L).run(run).title("보고서")
                .markdownBody("## 오늘의 핵심\n토스 공정위 무관 요약")
                .structuredContent(new com.example.be.domain.reports.entity.ReportContent(
                        List.of("토스 공정위 무관 요약"), List.of(), List.of(), List.of()))
                .generatedAt(LocalDateTime.of(2026, 9, 17, 10, 0)).build();
        var relevant = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        var irrelevant = finding(2L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(findingRepository.countTopicExcludedForReportByRunId(42L)).thenReturn(1L);
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(relevant, irrelevant));
        when(relevancePolicy.filterFindings(any())).thenReturn(List.of(relevant));
        for (boolean includeFindings : List.of(false, true)) {
            var detail = service.getReport(17L, includeFindings);
            assertEquals(1, detail.getSummaryStats().getFindingCount());
            assertFalse(detail.getMarkdownBody().contains("토스"));
            assertFalse(detail.getStructuredContent().toString().contains("무관"));
            if (includeFindings) assertEquals(List.of(1L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
            else assertNull(detail.getFindings());
        }
        assertTrue(report.getMarkdownBody().contains("토스"));
    }

    @Test
    void detailWithoutFindingsUsesAggregateCountsWithoutLoadingClobs() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0)).build();
        FindingRepository.ReportStatsCount count = mock(FindingRepository.ReportStatsCount.class);
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(count.getCategory()).thenReturn("정책");
        when(count.getChangeType()).thenReturn(ChangeType.NEW);
        when(count.getFindingCount()).thenReturn(2L);
        when(count.getHighSensitivityCount()).thenReturn(2L);
        when(findingRepository.countStatsByRunId(
                eq(42L), eq(new java.math.BigDecimal("40")), eq(new java.math.BigDecimal("70"))))
                .thenReturn(List.of(count));

        ReportResDTO.Detail detail = service.getReport(17L, false);

        assertNull(detail.getFindings());
        assertEquals(2, detail.getSummaryStats().getFindingCount());
        assertEquals(2, detail.getSummaryStats().getNewCount());
        assertEquals(2, detail.getSummaryStats().getBySensitivityLevel().get("high"));
        verify(findingRepository, never()).findForReportByRunId(42L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ReportScope.class)
    @SuppressWarnings("unchecked")
    void confirmedEventDisappearsFromReloadedDetailLatestAndCountsWithoutChangingSavedReport(ReportScope scope) {
        var run = CollectionRun.builder().id(42L).build();
        var stored = new ReportContent(List.of("잘못된 이슈 저장 요약"), List.of(
                new ReportContent.ImportantEvent("잘못된 이슈", "잘못된 사건 내용", "잘못된 영향", List.of(1L)),
                new ReportContent.ImportantEvent("남은 이슈", "남은 사건 내용", "남은 영향", List.of(2L))),
                List.of(new ReportContent.WatchItem("잘못된 관찰", "잘못된 이유", List.of(1L))),
                List.of("잘못된 출처 설명"));
        var report = NewsReport.builder().id(17L).reportScope(scope)
                .run(scope == ReportScope.RUN ? run : null)
                .sourceRunIds(List.of(42L)).reflectedFindingIds(List.of(1L, 2L))
                .title("리포트").markdownBody("## 핵심\n잘못된 이슈 저장 본문").structuredContent(stored)
                .generatedAt(LocalDateTime.of(2026, 10, 1, 10, 0)).build();
        var rejected = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        var retained = finding(2L, SensitivityLevel.LOW, Relevance.REFERENCE);
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(reportRepository.findFirstByReportStatusNotAndDeletedAtIsNullOrderByGeneratedAtDescIdDesc(ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(report)));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(rejected, retained));
        when(findingRepository.findForReportByIdIn(List.of(1L, 2L))).thenReturn(List.of(rejected, retained));
        var first = eventItem('a', 0, stored.importantEvents().getFirst());
        var second = eventItem('b', 1, stored.importantEvents().getLast());
        when(eventSnapshots.capture(eq(report), any())).thenReturn(List.of(first, second));
        var reviews = List.of(new FeedbackModels.Feedback(
                9L, null, null, 17L, null, FeedbackModels.Category.SUMMARY_ERROR, "오류입니다", false,
                "request-hash", FeedbackModels.Status.COMPLETED, "CONFIRMED_ERROR", "검토 설명",
                LocalDateTime.of(2026, 10, 1, 10, 1), first, first.event().key()));
        when(feedbackStore.eventFeedback(17L)).thenReturn(reviews);
        when(feedbackStore.eventReviewStates(List.of(17L))).thenReturn(reviews);

        for (boolean includeFindings : List.of(false, true)) {
            var detail = service.getReport(17L, includeFindings);
            var reloaded = service.getReport(17L, includeFindings);
            var latest = service.getLatest(includeFindings);
            assertEquals(1, detail.getSummaryStats().getFindingCount());
            assertEquals(0, detail.getSummaryStats().getBySensitivityLevel().get("high"));
            assertEquals(List.of("남은 이슈"), detail.getStructuredContent().importantEvents().stream()
                    .map(ReportContent.ImportantEvent::title).toList());
            assertFalse(detail.getStructuredContent().toString().contains("잘못된"));
            assertFalse(detail.getMarkdownBody().contains("잘못된"));
            assertTrue(detail.getMarkdownBody().contains("남은 사건 내용"));
            assertEquals(detail.getMarkdownBody(), reloaded.getMarkdownBody());
            assertEquals(detail.getStructuredContent(), latest.getStructuredContent());
            assertEquals(1, latest.getSummaryStats().getFindingCount());
            if (includeFindings) assertEquals(List.of(2L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
            else assertNull(detail.getFindings());
        }
        var summary = service.getReports(null, null, 0, 20).getContent().getFirst();
        assertEquals(1, summary.getFindingCount());
        assertEquals(0, summary.getHighSensitivityCount());
        assertEquals(stored, report.getStructuredContent());
        assertEquals("## 핵심\n잘못된 이슈 저장 본문", report.getMarkdownBody());
        assertEquals(List.of(1L, 2L), report.getReflectedFindingIds());
    }

    private static FeedbackModels.Item eventItem(char key, int index, ReportContent.ImportantEvent event) {
        var context = new FeedbackModels.EventContext(String.valueOf(key).repeat(64), index,
                event.title(), event.summaryKo(), event.significance(), event.sourceFindingIds(), List.of(), List.of(), null);
        return new FeedbackModels.Item(null, null, null, List.of(), null, null, null, null,
                "report-v1", "fixture", null, context);
    }

    @Test
    void detailAndLatestHideUnavailableFindingsAndTheirSavedTextWithIdenticalCounts() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        var stored = new com.example.be.domain.reports.entity.ReportContent(List.of("숨길 저장 요약"),
                List.of(), List.of(), List.of());
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .markdownBody("## 오늘의 핵심\n숨길 저장 요약").structuredContent(stored)
                .generatedAt(LocalDateTime.of(2026, 9, 15, 10, 0)).build();
        Finding available = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        Finding metadata = finding(2L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        Finding blank = finding(3L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        org.springframework.test.util.ReflectionTestUtils.setField(metadata.getArticle(), "fetchStatus",
                com.example.be.domain.collection.entity.FetchStatus.METADATA_ONLY);
        blank.getArticle().applyFullText(" \n\t", FetchStatus.FULLTEXT, null);
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(reportRepository.findFirstByReportStatusNotAndDeletedAtIsNullOrderByGeneratedAtDescIdDesc(ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(metadata, blank, available));
        when(findingRepository.countWithoutFullTextForReportByRunId(42L)).thenReturn(2L);
        for (boolean includeFindings : List.of(true, false)) {
            var detail = service.getReport(17L, includeFindings);
            var latest = service.getLatest(includeFindings);
            assertEquals(1, detail.getSummaryStats().getFindingCount());
            assertEquals(1, latest.getSummaryStats().getFindingCount());
            assertFalse(detail.getMarkdownBody().contains("숨길"));
            assertFalse(detail.getStructuredContent().toString().contains("숨길"));
            assertEquals(detail.getMarkdownBody(), latest.getMarkdownBody());
            assertTrue(detail.getMarkdownBody().contains("요약 1"));
            if (includeFindings) assertEquals(List.of(1L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
            else assertNull(detail.getFindings());
        }
        assertEquals(stored, report.getStructuredContent());
        assertEquals("## 오늘의 핵심\n숨길 저장 요약", report.getMarkdownBody());
    }

    @Test
    void dailyVisibilityKeepsSavedSelectionAndFiltersBothFindingCountModes() {
        NewsReport report = NewsReport.builder().id(17L).reportScope(ReportScope.DAILY)
                .reportDate(LocalDate.of(2026, 9, 14)).reflectedFindingIds(List.of(2L, 1L))
                .markdownBody("숨길 저장 기사").generatedAt(LocalDateTime.of(2026, 9, 15, 0, 5)).build();
        Finding available = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        Finding missing = finding(2L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        missing.getArticle().applyFullText(null, FetchStatus.FULLTEXT, null);
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(findingRepository.findForReportByIdIn(List.of(2L, 1L))).thenReturn(List.of(missing, available));
        for (boolean includeFindings : List.of(true, false)) {
            var detail = service.getReport(17L, includeFindings);
            assertEquals(1, detail.getSummaryStats().getFindingCount());
            assertFalse(detail.getMarkdownBody().contains("숨길"));
            if (includeFindings) assertEquals(List.of(1L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
            else assertNull(detail.getFindings());
        }
        assertEquals(List.of(2L, 1L), report.getReflectedFindingIds());
    }

    @Test
    void sharedArticleDetailUsesAcceptedTopicIssueAndItsInvestigationTrace() {
        var report = NewsReport.builder().id(17L).run(CollectionRun.builder().id(42L).build())
                .title("보고서").generatedAt(LocalDateTime.of(2026, 9, 17, 10, 0)).build();
        Finding shared = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        NewsIssue accepted = NewsIssue.builder().id(88L).topic(topic(8L)).title("관련 주제 이슈")
                .articleCount(1).entities(List.of()).build();
        var rejectedMembership = membership(101L, 77L, 7L);
        var acceptedMembership = membership(101L, 88L, 8L);
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(shared));
        when(relevancePolicy.relevantTopicIdsByFinding(List.of(shared)))
                .thenReturn(java.util.Map.of(1L, java.util.Set.of(8L)));
        when(issueArticleRepository.findCoverageMembershipsByArticleIds(List.of(101L)))
                .thenReturn(List.of(rejectedMembership, acceptedMembership));
        when(newsIssueRepository.findAllById(List.of(88L))).thenReturn(List.of(accepted));
        when(investigationRepository.findTraces(42L)).thenReturn(java.util.Map.of(88L,
                new InvestigationTrace("NO_NEW_EVIDENCE", 1, 2, 0, "관련 주제 추가 확인", null)));

        var detail = service.getReport(17L, true).getFindings().getFirst();
        assertEquals(88L, detail.getIssueId());
        assertEquals("관련 주제 이슈", detail.getIssue().getTitle());
        assertEquals("NO_NEW_EVIDENCE", detail.getInvestigation().getStatus());
    }

    @Test
    void detailFindingsUseSamePriorityOrderAsGeneratedMarkdown() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .modelName("claude-sonnet-5")
                .promptVersion("report.ko.v1.4")
                .llmProvider("anthropic")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0)).build();
        Finding low = finding(2L, SensitivityLevel.LOW, Relevance.REFERENCE);
        Finding high = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        IssueArticleRepository.CoverageMembership matchingMembership = membership(101L, 88L, 7L);
        IssueArticleRepository.CoverageMembership wrongTopicMembership = membership(102L, 99L, 8L);
        NewsIssue issue = NewsIssue.builder()
                .id(88L)
                .title("HBM4 양산 일정 이슈")
                .summary("양산 일정이 앞당겨졌다.")
                .lastSeenAt(OffsetDateTime.parse("2026-08-18T09:00:00+09:00"))
                .articleCount(3)
                .publisherCount(2)
                .independentContentCount(2)
                .topic(topic(7L))
                .entities(List.of("SK하이닉스", "HBM4"))
                .build();
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(low, high));
        when(issueArticleRepository.findCoverageMembershipsByArticleIds(List.of(101L, 102L)))
                .thenReturn(List.of(matchingMembership, wrongTopicMembership));
        when(newsIssueRepository.findAllById(List.of(88L))).thenReturn(List.of(issue));
        when(investigationRepository.findTraces(42L)).thenReturn(java.util.Map.of(
                88L,
                new InvestigationTrace("NO_NEW_EVIDENCE", 1, 2, 0,
                        "B사 입장 확인", null)));

        ReportResDTO.Detail detail = service.getReport(17L, true);

        assertEquals(List.of(1L, 2L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
        assertEquals("report.ko.v1.4", detail.getPromptVersion());
        assertEquals("anthropic", detail.getLlmProvider());
        assertEquals(88L, detail.getFindings().getFirst().getIssueId());
        assertEquals("HBM4 양산 일정 이슈", detail.getFindings().getFirst().getIssue().getTitle());
        assertEquals(3, detail.getFindings().getFirst().getIssue().getArticleCount());
        assertEquals("NO_NEW_EVIDENCE",
                detail.getFindings().getFirst().getInvestigation().getStatus());
        assertEquals(2,
                detail.getFindings().getFirst().getInvestigation().getAddedArticleCount());
        assertNull(detail.getFindings().get(1).getIssueId());
        assertEquals("CHIP_MAKER", detail.getFindings().getFirst()
                .getPerspectiveTags().getFirst().getAudience());
        ReportResDTO.KeyPoint keyPoint = detail.getFindings().getFirst().getKeyPoints().getFirst();
        assertEquals("핵심", keyPoint.getText());
        assertEquals(List.of(0), keyPoint.getEvidence());
        assertEquals("grounded", keyPoint.getGroundedness());
        assertEquals("직접 확인", keyPoint.getGroundingReason());
        assertEquals("OPINION", keyPoint.getClaimType());
        assertEquals("분석가", keyPoint.getAttributedTo());
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailChunksIssueMembershipQueriesBelowOracleLimit() {
        CollectionRun run = CollectionRun.builder().id(42L).build();
        NewsReport report = NewsReport.builder().id(17L).run(run).title("보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 18, 10, 0)).build();
        List<Finding> findings = LongStream.rangeClosed(1, 1_001)
                .mapToObj(id -> finding(id, SensitivityLevel.LOW, Relevance.REFERENCE))
                .toList();
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING))
                .thenReturn(Optional.of(report));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(findings);
        when(issueArticleRepository.findCoverageMembershipsByArticleIds(anyCollection()))
                .thenReturn(List.of());

        service.getReport(17L, true);

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(issueArticleRepository, times(2))
                .findCoverageMembershipsByArticleIds(ids.capture());
        assertEquals(List.of(900, 101), ids.getAllValues().stream().map(Collection::size).toList());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ReportScope.class, names = {"DAILY", "WEEKLY"})
    @SuppressWarnings("unchecked")
    void aggregatesLoadSavedFindingsAcrossRunsAndCountTheSameWithoutDetails(ReportScope scope) {
        var date = java.time.LocalDate.of(2026, 9, 3);
        NewsReport daily = NewsReport.builder().id(70L)
                .reportScope(scope).reportDate(date)
                .reportEndDate(scope == ReportScope.WEEKLY ? date.plusDays(6) : null)
                .sourceReportIds(scope == ReportScope.WEEKLY ? List.of(60L, 61L) : List.of())
                .sourceReportDates(scope == ReportScope.WEEKLY ? List.of(date, date.plusDays(1)) : List.of())
                .missingReportDates(scope == ReportScope.WEEKLY ? List.of(date.plusDays(2)) : List.of())
                .sourceRunIds(List.of(42L, 43L)).reflectedFindingIds(List.of(2L, 1L))
                .generatedAt(date.plusDays(1).atStartOfDay()).build();
        Finding first = finding(1L, SensitivityLevel.HIGH, Relevance.IMPORTANT);
        Finding second = finding(2L, SensitivityLevel.LOW, Relevance.REFERENCE);
        org.springframework.test.util.ReflectionTestUtils.setField(first, "run", CollectionRun.builder().id(42L).build());
        org.springframework.test.util.ReflectionTestUtils.setField(second, "run", CollectionRun.builder().id(43L).build());
        when(reportRepository.findByIdAndReportStatusNot(70L, ReportStatus.PENDING)).thenReturn(Optional.of(daily));
        when(findingRepository.findForReportByIdIn(List.of(2L, 1L))).thenReturn(List.of(first, second));
        when(reportRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(daily)));

        var detail = service.getReport(70L, true);
        assertNull(detail.getRunId());
        assertEquals(date, detail.getReportDate());
        assertEquals(scope, detail.getReportScope());
        assertEquals(daily.getReportEndDate(), detail.getReportEndDate());
        assertEquals(daily.getSourceReportIds(), detail.getSourceReportIds());
        assertEquals(daily.getSourceReportDates(), detail.getSourceReportDates());
        assertEquals(daily.getMissingReportDates(), detail.getMissingReportDates());
        assertEquals(List.of(42L, 43L), detail.getSourceRunIds());
        assertEquals(List.of(2L, 1L), detail.getFindings().stream().map(ReportResDTO.Finding::getId).toList());
        assertEquals(List.of(43L, 42L), detail.getFindings().stream().map(ReportResDTO.Finding::getRunId).toList());
        assertEquals(2, service.getReport(70L, false).getSummaryStats().getFindingCount());
        var count = mock(FindingRepository.DailyReportCount.class);
        when(count.getReportId()).thenReturn(70L);
        when(count.getFindingCount()).thenReturn(2L);
        when(count.getHighSensitivityCount()).thenReturn(1L);
        when(findingRepository.countForDailyReports(eq(List.of(70L)), any())).thenReturn(List.of(count));
        var summary = service.getReports(null, null, 0, 20).getContent().getFirst();
        assertEquals(2, summary.getFindingCount());
        assertEquals(1, summary.getHighSensitivityCount());
        assertEquals(daily.getReportEndDate(), summary.getReportEndDate());
        assertEquals(daily.getSourceReportIds(), summary.getSourceReportIds());
        assertEquals(daily.getSourceReportDates(), summary.getSourceReportDates());
        assertEquals(daily.getMissingReportDates(), summary.getMissingReportDates());
        assertEquals(List.of(42L, 43L), summary.getSourceRunIds());
        verify(findingRepository, never()).findForReportByRunId(any());
        verify(investigationRepository).findTraces(List.of(43L, 42L));
        verify(investigationRepository, never()).findTraces(any(Long.class));
        verify(findingRepository, times(2)).findForReportByIdIn(anyCollection());
    }

    private Finding finding(Long id, SensitivityLevel sensitivityLevel, Relevance relevance) {
        Article article = Article.builder()
                .id(id + 100)
                .topic(topic(7L))
                .title("기사 " + id)
                .canonicalUrl("https://example.com/" + id)
                .fetchStatus(com.example.be.domain.collection.entity.FetchStatus.FULLTEXT).body("확보한 본문")
                .build();
        return Finding.builder()
                .run(CollectionRun.builder().id(42L).build())
                .id(id)
                .article(article)
                .analysisSource(com.example.be.domain.analysis.entity.AnalysisSource.LLM)
                .changeType(ChangeType.NEW)
                .summary("요약 " + id)
                .keyPoints(List.of(
                        new FindingKeyPoint(
                                "핵심", List.of(0), "grounded", "직접 확인", "OPINION", "분석가"),
                        new FindingKeyPoint("비근거 주장", List.of(1), "ungrounded")))
                .sentiment(Sentiment.NEUTRAL)
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.legacy(sensitivityLevel))
                .relevance(relevance)
                .category("정책")
                .perspectiveTags(List.of(new FindingPerspectiveTag(
                        Audience.CHIP_MAKER,
                        AudienceRelevance.HIGH,
                        "생산 계획에 직접 영향을 줍니다.",
                        List.of(0))))
                .build();
    }

    private IssueArticleRepository.CoverageMembership membership(Long articleId,
                                                                  Long issueId,
                                                                  Long topicId) {
        IssueArticleRepository.CoverageMembership value =
                mock(IssueArticleRepository.CoverageMembership.class);
        when(value.getArticleId()).thenReturn(articleId);
        when(value.getIssueId()).thenReturn(issueId);
        when(value.getTopicId()).thenReturn(topicId);
        return value;
    }

    private Topic topic(Long id) {
        return Topic.builder().id(id).name("HBM").build();
    }
}
