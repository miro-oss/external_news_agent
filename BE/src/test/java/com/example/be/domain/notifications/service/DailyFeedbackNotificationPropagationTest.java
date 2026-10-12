package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.FeedbackDeliveryService;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.DailyReportFeedbackExclusions;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real source-key validation, frozen issue matching, projection and both notification render paths. */
class DailyFeedbackNotificationPropagationTest {
    private static final long DAILY_ID = 101L;
    private static final long WEEKLY_ID = 201L;
    private static final long RECIPIENT_ID = 99L;
    private static final long TOPIC_ID = 41L;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 5);
    private static final LocalDate THROUGH = FROM.plusDays(6);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = TopicRelevanceTestSupport.legacyPolicy();
    private final FeedbackStore store = mock(FeedbackStore.class);
    private final ReportComparisonRepository comparisons = mock(ReportComparisonRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReportEventSnapshotFactory snapshots = new ReportEventSnapshotFactory(jdbc, new ObjectMapper());
    private final DailyReportFeedbackExclusions exclusions = new DailyReportFeedbackExclusions(
            reports, store, snapshots, findings, relevance, comparisons);
    private final ReportEventFeedbackProjection projection = new ReportEventFeedbackProjection(store, snapshots, exclusions);
    private final FeedbackDeliveryService feedback = mock(FeedbackDeliveryService.class);
    private final NotificationRenderer renderer = new NotificationRenderer(findings, relevance, projection);
    private final PersonalizedNotificationRenderer personalized = new PersonalizedNotificationRenderer(
            reports, findings, relevance, feedback, renderer, projection);

    @BeforeEach
    void configure() {
        when(relevance.filterTopicFindings(eq(TOPIC_ID), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        when(jdbc.queryForList(anyString(), eq(Long.class), anyLong(), anyLong())).thenReturn(List.of(TOPIC_ID));
        ReflectionTestUtils.setField(renderer, "publicBaseUrl", "https://news.example.com");
        ReflectionTestUtils.setField(personalized, "publicBaseUrl", "https://news.example.com");
    }

    @ParameterizedTest(name = "{0}, topic weekly={1}")
    @MethodSource("channelsAndScopes")
    void completedDailyDecisionImmediatelyFiltersExistingWeeklyPreviewAndBothDeliveryEntries(
            ChannelType channelType, boolean topicWeekly) {
        Fixture fixture = fixture(topicWeekly, true);
        prepare(fixture, Set.of());
        Feedback processing = review(fixture, Status.PROCESSING);
        Feedback completed = review(fixture, Status.COMPLETED);
        when(store.eventReviewStates(anyCollection())).thenReturn(List.of(processing));
        assertTrue(preview(fixture.weekly(), channelType).contains("주간 오류 제목"));

        // The same already-generated report is read again after its DAILY review finishes.
        when(store.eventReviewStates(anyCollection())).thenReturn(List.of(completed));
        for (String body : allEntryPoints(fixture.weekly(), channelType)) {
            assertRejectedContentAbsent(body);
            assertTrue(body.contains("정상 주간 사건"));
            assertTrue(body.contains("정상 주간 요약"));
            assertTrue(body.contains("정상 후속 확인"));
            assertTrue(body.contains("https://example.com/source-22"));
        }

        for (var view : preparedViews(fixture.weekly())) {
            assertTrue(view.excludedEvents());
            assertEquals(List.of(22L), view.findings().stream().map(Finding::getId).toList());
            assertEquals(List.of("정상 주간 사건"), view.readingContent().structuredContent().importantEvents()
                    .stream().map(ReportContent.ImportantEvent::title).toList());
            assertFalse(view.readingContent().markdownBody().contains("주간 오류"));
        }
        verify(store, atLeastOnce()).eventFeedback(WEEKLY_ID);
        assertTrue(store.eventFeedback(WEEKLY_ID).isEmpty(), "No weekly-local review is required");
        assertSame(fixture.storedContent(), fixture.weekly().getStructuredContent());
        assertTrue(fixture.weekly().getMarkdownBody().contains("주간 오류 저장 마크다운"));
        assertEquals(2, fixture.weekly().getReflectedFindingIds().size());
        assertEquals(topicWeekly ? List.of() : List.of(DAILY_ID), fixture.weekly().getSourceReportIds());
    }

    @ParameterizedTest(name = "{0}, topic weekly={1}")
    @MethodSource("channelsAndScopes")
    void allInheritedErrorsLeaveNoStoredClaimsOrSourceFallback(ChannelType channelType, boolean topicWeekly) {
        Fixture fixture = fixture(topicWeekly, false);
        prepare(fixture, Set.of());
        Feedback completed = review(fixture, Status.COMPLETED);
        when(store.eventReviewStates(anyCollection())).thenReturn(List.of(completed));

        for (String body : allEntryPoints(fixture.weekly(), channelType)) {
            assertRejectedContentAbsent(body);
            assertFalse(body.contains("https://example.com/source-"));
            assertTrue(body.contains("새롭게 정리할 주요 내용이 없습니다"));
            assertTrue(body.contains("보고서 전체 보기"));
        }
        for (var view : preparedViews(fixture.weekly())) {
            assertTrue(view.findings().isEmpty());
            assertTrue(view.readingContent().structuredContent().importantEvents().isEmpty());
        }
    }

    @ParameterizedTest(name = "{0}, topic weekly={1}")
    @MethodSource("channelsAndScopes")
    void personalSuppressionOfLastSurvivingEventCannotRestoreInheritedError(ChannelType channelType, boolean topicWeekly) {
        Fixture fixture = fixture(topicWeekly, true);
        prepare(fixture, Set.of(22L));
        Feedback completed = review(fixture, Status.COMPLETED);
        when(store.eventReviewStates(anyCollection())).thenReturn(List.of(completed));

        for (boolean queued : List.of(false, true)) {
            String body = delivery(fixture.weekly(), channelType, queued);
            assertRejectedContentAbsent(body);
            assertFalse(body.contains("정상 주간"));
            assertFalse(body.contains("https://example.com/source-"));
            assertTrue(body.contains("새롭게 정리할 주요 내용이 없습니다"));
            assertTrue(body.contains("이슈 피드백"));
        }
    }

    private void prepare(Fixture fixture, Set<Long> suppressed) {
        when(reports.findById(WEEKLY_ID)).thenReturn(Optional.of(fixture.weekly()));
        when(reports.findByReportScopeAndReportDateBetweenOrderByReportDateAsc(ReportScope.DAILY, FROM, THROUGH))
                .thenReturn(List.of(fixture.daily()));
        when(findings.findForReportByIdIn(fixture.daily().getReflectedFindingIds())).thenReturn(List.of(fixture.dailyFinding()));
        when(findings.findForReportByIdIn(fixture.weekly().getReflectedFindingIds())).thenReturn(fixture.weeklyFindings());
        when(comparisons.findInput(DAILY_ID)).thenReturn(Optional.of(snapshot(List.of(identity(11L, 1001L)))));
        when(feedback.prepare(eq(fixture.weekly()), eq(RECIPIENT_ID), any(ReportEventFeedbackProjection.View.class)))
                .thenReturn(new DeliveryFeedback(suppressed, "synthetic-test-token"));
    }

    private Feedback review(Fixture fixture, Status status) {
        Item original = snapshots.capture(fixture.daily(), new ReportFindings.Visible(List.of(fixture.dailyFinding()), false))
                .getFirst();
        assertNull(original.event().unavailableReason());
        return new Feedback(1L, null, null, DAILY_ID, null, Category.SUMMARY_ERROR, "오류 의견", false,
                "request-hash", status, "CONFIRMED_ERROR", "확인된 오류", FROM.atTime(12, 0), original, original.event().key());
    }

    private List<ReportEventFeedbackProjection.View> preparedViews(NewsReport report) {
        var captor = ArgumentCaptor.forClass(ReportEventFeedbackProjection.View.class);
        verify(feedback, times(2)).prepare(eq(report), eq(RECIPIENT_ID), captor.capture());
        return captor.getAllValues();
    }

    private List<String> allEntryPoints(NewsReport report, ChannelType channelType) {
        return List.of(preview(report, channelType), delivery(report, channelType, false), delivery(report, channelType, true));
    }

    private String preview(NewsReport report, ChannelType type) {
        String body = String.join("\n", renderer.render(report, channel(type)).chunks());
        assertFalse(body.contains("synthetic-test-token"));
        return body;
    }

    private String delivery(NewsReport report, ChannelType type, boolean queued) {
        var rendered = queued ? personalized.render(report.getId(), channel(type), RECIPIENT_ID)
                : personalized.render(report, channel(type), RECIPIENT_ID);
        String body = String.join("\n", rendered.chunks());
        assertTrue(body.contains("https://news.example.com/#/feedback?token=synthetic-test-token"));
        return body;
    }

    private static void assertRejectedContentAbsent(String body) {
        for (String rejected : List.of("주간 오류 제목", "주간 오류 요약", "주간 오류 해석", "주간 오류 저장 요약",
                "주간 오류 저장 마크다운", "주간 오류 후속 확인", "주간 오류 확인 이유", "주간 오류 출처 설명",
                "오류 기사 대체 요약", "https://example.com/source-21")) {
            assertFalse(body.contains(rejected), rejected + " must not be delivered");
        }
    }

    private static Fixture fixture(boolean topicWeekly, boolean includeValid) {
        Finding dailyFinding = finding(11L, 31L);
        var dailyContent = new ReportContent(List.of("일일 오류 요약"), List.of(
                new ReportContent.ImportantEvent("일일 오류 사건", "일일 오류 요약", "일일 해석", List.of(11L))), List.of(), List.of());
        var daily = NewsReport.builder().id(DAILY_ID).reportScope(ReportScope.DAILY).reportDate(FROM)
                .title("일일 보고서").markdownBody("일일 오류 본문").structuredContent(dailyContent)
                .reflectedFindingIds(List.of(11L)).collectionContexts(List.of(context(31L)))
                .generatedAt(FROM.plusDays(1).atTime(0, 5)).build();

        // Finding 21 is another saved analysis of the same issue/topic, with a paraphrased weekly event.
        List<Finding> weeklyFindings = new ArrayList<>(List.of(finding(21L, 32L)));
        List<ReportContent.ImportantEvent> events = new ArrayList<>(List.of(new ReportContent.ImportantEvent(
                "주간 오류 제목", "주간 오류 요약", "주간 오류 해석", List.of(21L))));
        List<ReportContent.WatchItem> watches = new ArrayList<>(List.of(new ReportContent.WatchItem(
                "주간 오류 후속 확인", "주간 오류 확인 이유", List.of(21L))));
        List<ReportComparisonSnapshot.Issue> issues = new ArrayList<>(List.of(identity(21L, 1001L)));
        if (includeValid) {
            weeklyFindings.add(finding(22L, 32L));
            events.add(new ReportContent.ImportantEvent("정상 주간 사건", "정상 주간 요약", "정상 해석", List.of(22L)));
            watches.add(new ReportContent.WatchItem("정상 후속 확인", "정상 확인 이유", List.of(22L)));
            issues.add(identity(22L, 1002L));
        }
        var content = new ReportContent(List.of("주간 오류 저장 요약"), events, watches, List.of("주간 오류 출처 설명"));
        List<Long> ids = weeklyFindings.stream().map(Finding::getId).toList();
        var source = new WeeklyReportInput.DailySource(topicWeekly ? null : DAILY_ID, FROM, "저장된 입력", "저장된 입력",
                content, ids, snapshot(issues));
        var weekly = NewsReport.builder().id(WEEKLY_ID).reportScope(ReportScope.WEEKLY)
                .reportDate(FROM).reportEndDate(THROUGH).topicId(topicWeekly ? TOPIC_ID : null)
                .topicName(topicWeekly ? "반도체" : null).title("주간 보고서")
                .markdownBody("## 핵심 요약\n주간 오류 저장 마크다운").structuredContent(content).reflectedFindingIds(ids)
                .sourceReportIds(topicWeekly ? List.of() : List.of(DAILY_ID)).collectionContexts(List.of(context(32L)))
                .weeklyInput(new WeeklyReportInput(FROM, THROUGH, List.of(source), List.of(),
                        topicWeekly ? TOPIC_ID : null, topicWeekly ? "반도체" : null))
                .generatedAt(THROUGH.plusDays(1).atTime(0, 10)).build();
        return new Fixture(daily, dailyFinding, weekly, List.copyOf(weeklyFindings), content);
    }

    private static Finding finding(long id, long runId) {
        var topic = Topic.builder().id(TOPIC_ID).name("반도체").build();
        return Finding.builder().id(id).run(CollectionRun.builder().id(runId).startedAt(FROM.atTime(9, 0)).build())
                .article(Article.builder().id(id + 100).topic(topic).title("근거 기사 " + id).body("확보된 원문 " + id)
                        .canonicalUrl("https://example.com/source-" + id).fetchStatus(FetchStatus.FULLTEXT).build())
                .summary(id == 21L ? "오류 기사 대체 요약" : "기사 대체 요약 " + id).analysisSource(AnalysisSource.LLM)
                .analysisInputHash("input-" + id).promptVersion("test-v1").llmModel("test-model")
                .keyPoints(List.of(new FindingKeyPoint("확인된 근거", List.of(0), "grounded")))
                .sensitivity(FindingSensitivity.legacy(SensitivityLevel.HIGH)).relevance(Relevance.IMPORTANT).build();
    }

    private static ReportCollectionContext context(long runId) {
        return new ReportCollectionContext(runId, List.of(new CollectionTopicSnapshot(
                TOPIC_ID, "반도체", "반도체", List.of("반도체"), List.of(), List.of(), 100, 1440)));
    }

    private static ReportComparisonSnapshot.Issue identity(long findingId, long issueId) {
        return new ReportComparisonSnapshot.Issue(findingId, "input-" + findingId, "test-v1", null,
                new ReportChanges.Side(issueId, TOPIC_ID, "저장된 이슈", "저장된 분석", List.of()));
    }

    private static ReportComparisonSnapshot snapshot(List<ReportComparisonSnapshot.Issue> issues) {
        return new ReportComparisonSnapshot(1, issues, List.of(context(31L), context(32L)));
    }

    private static NotificationChannel channel(ChannelType type) {
        return NotificationChannel.builder().id(type == ChannelType.EMAIL ? 1L : 2L).channelType(type).name(type.name())
                .config(type == ChannelType.TELEGRAM ? Map.of("parseMode", "HTML") : Map.of()).maxLength(3500).active(true).build();
    }

    private static Stream<Arguments> channelsAndScopes() {
        return Stream.of(ChannelType.values()).flatMap(type -> Stream.of(false, true).map(topic -> Arguments.of(type, topic)));
    }

    private record Fixture(NewsReport daily, Finding dailyFinding, NewsReport weekly,
                           List<Finding> weeklyFindings, ReportContent storedContent) { }
}
