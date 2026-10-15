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
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.FeedbackDeliveryService;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.DailyReportFeedbackExclusions;
import com.example.be.domain.reports.service.ReportFindings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalizedNotificationRendererTest {
    private static final long RECIPIENT_ID = 99L;
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = TopicRelevanceTestSupport.legacyPolicy();
    private final FeedbackStore store = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory snapshots = mock(ReportEventSnapshotFactory.class);
    private final FeedbackDeliveryService feedback = mock(FeedbackDeliveryService.class);
    private final ReportEventFeedbackProjection projection = new ReportEventFeedbackProjection(
            store, snapshots, mock(DailyReportFeedbackExclusions.class));
    private final NotificationRenderer renderer = new NotificationRenderer(findings, relevance, projection, mock(NotificationDartSummary.class));
    private final ReportPdfRenderer pdfRenderer = mock(ReportPdfRenderer.class);
    private final PersonalizedNotificationRenderer personalized = new PersonalizedNotificationRenderer(
            reports, findings, relevance, feedback, renderer, projection, pdfRenderer);

    @BeforeEach
    void configurePublicLinks() {
        when(pdfRenderer.render(any(), any(), any())).thenReturn(new byte[]{'%', 'P', 'D', 'F'});
        ReflectionTestUtils.setField(renderer, "publicBaseUrl", "https://news.example.com");
        ReflectionTestUtils.setField(personalized, "publicBaseUrl", "https://news.example.com");
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void selectedInsightsRespectPersonalAndPublicEvidenceExclusions(ChannelType type) {
        var fixture = fixture(true);
        prepare(fixture, List.of(confirmed('a')), Set.of(14L));
        var insight = NotificationInsightTestSupport.insight(List.of(
                new com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview(
                        "개인 제외 해석", List.of("14:0", "13:0"), "개인 제외 전제"),
                new com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview(
                        "공용 제외 해석", List.of("11:0"), "공용 제외 전제"),
                new com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview(
                        "허용된 관점 해석", List.of("13:0"), "허용된 전제")), List.of());
        var channel = NotificationChannel.builder().id(1L).channelType(type).maxLength(3500).active(true).build();
        String body = personalized.render(fixture.report(), channel, RECIPIENT_ID, insight).chunks().getFirst();
        assertFalse(body.contains("개인 제외"));
        assertFalse(body.contains("공용 제외"));
        assertFalse(body.contains("숨겨진 헤드라인"));
        assertTrue(body.contains("허용된 관점 해석"));
        assertEquals(type == ChannelType.TELEGRAM, body.contains("허용된 전제"));
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void confirmedLocalErrorIsExcludedFromQueuedAndManualDeliveryEvenWithSharedEvidence(ChannelType type) {
        Fixture fixture = fixture(false);
        prepare(fixture, List.of(confirmed('a')), Set.of());

        for (boolean byId : List.of(true, false)) {
            String body = render(fixture.report(), type, byId);
            assertRejectedContentAbsent(body);
            assertTrue(body.contains("정상 사건 제목"));
            assertTrue(body.contains("정상 사건 주요 내용"));
            assertTrue(body.contains("정상 후속 확인"));
            assertTrue(body.contains("https://example.com/source-12"));
            assertTrue(body.contains("https://example.com/source-13"));
            assertTrue(body.contains("https://news.example.com/#/reports?reportId=17"));
            assertTrue(body.contains("https://news.example.com/#/feedback?token=synthetic-test-token"));
        }

        var prepared = preparedViews(fixture.report(), 2);
        for (var view : prepared) {
            assertTrue(view.excludedEvents());
            assertEquals(List.of(12L, 13L), view.findings().stream().map(Finding::getId).toList());
            assertEquals(Set.of(11L, 12L), view.excludedEvidenceFindingIds());
            assertEquals(List.of("정상 사건 제목"), eventTitles(view));
            assertFalse(view.readingContent().markdownBody().contains("제외할"));
        }
        verify(snapshots, times(2)).capture(fixture.report(), new ReportFindings.Visible(fixture.findings(), false));
        assertEquals(2, fixture.report().getStructuredContent().importantEvents().size());
        assertEquals("## 핵심 요약\n제외할 저장 마크다운", fixture.report().getMarkdownBody());
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void recipientPolicyIsAppliedToTheProjectedEventsWithoutRestoringRejectedContent(ChannelType type) {
        Fixture fixture = fixture(true);
        prepare(fixture, List.of(confirmed('a')), Set.of(14L));

        for (boolean byId : List.of(true, false)) {
            String body = render(fixture.report(), type, byId);
            assertRejectedContentAbsent(body);
            assertTrue(body.contains("정상 사건 제목"));
            assertTrue(body.contains("정상 사건 주요 내용"));
            assertTrue(body.contains("https://example.com/source-12"));
            assertFalse(body.contains("개인 제외"));
            assertFalse(body.contains("https://example.com/source-14"));
        }

        for (var view : preparedViews(fixture.report(), 2)) {
            assertEquals(List.of("정상 사건 제목", "개인 제외 사건"), eventTitles(view));
            assertEquals(List.of(12L, 13L, 14L), view.findings().stream().map(Finding::getId).toList());
        }
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void suppressingTheLastValidEventDoesNotResendSharedEvidenceAsFallbackContent(ChannelType type) {
        Fixture fixture = fixture(false);
        prepare(fixture, List.of(confirmed('a')), Set.of(13L));

        for (boolean byId : List.of(true, false)) {
            String body = render(fixture.report(), type, byId);
            assertRejectedContentAbsent(body);
            assertFalse(body.contains("정상 사건"));
            assertFalse(body.contains("정상 후속 확인"));
            assertFalse(body.contains("공유 근거 대체 요약"));
            assertFalse(body.contains("https://example.com/source-12"));
            assertFalse(body.contains("https://example.com/source-13"));
            assertTrue(body.contains("이슈 피드백"));
        }
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void rejectingEveryEventLeavesNeitherStoredClaimsNorSourceFallbackInEitherEntryPoint(ChannelType type) {
        Fixture fixture = fixture(false);
        prepare(fixture, List.of(confirmed('a'), confirmed('b')), Set.of());

        for (boolean byId : List.of(true, false)) {
            String body = render(fixture.report(), type, byId);
            assertRejectedContentAbsent(body);
            assertFalse(body.contains("정상 사건"));
            assertFalse(body.contains("정상 후속 확인"));
            assertFalse(body.contains("https://example.com/source-"));
            assertTrue(body.contains("새롭게 정리할 주요 내용이 없습니다"));
            assertTrue(body.contains("보고서 전체 보기"));
        }
        for (var view : preparedViews(fixture.report(), 2)) {
            assertTrue(view.findings().isEmpty());
            assertTrue(view.readingContent().structuredContent().importantEvents().isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void localOpinionsImmediatelyChangeManualAndQueuedDeliveryWithoutChangingPublicReading(ChannelType type) {
        Fixture fixture = fixture(false);
        List<Feedback> reviews = List.of(
                review('a', Category.SUMMARY_ERROR, Status.PENDING, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.PROCESSING, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.FAILED, "CONFIRMED_ERROR", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "NOT_CONFIRMED", null),
                review('a', Category.SUMMARY_ERROR, Status.COMPLETED, "INSUFFICIENT_EVIDENCE", null),
                review('a', Category.PREFERENCE, Status.COMPLETED, "PREFERENCE", null));
        prepare(fixture, List.of(), Set.of());

        for (Feedback review : reviews) {
            when(store.eventFeedback(17L)).thenReturn(List.of(review));
            for (boolean byId : List.of(true, false)) {
                String body = render(fixture.report(), type, byId);
                assertRejectedContentAbsent(body);
                assertTrue(body.contains("정상 사건 제목"));
            }
            var publicView = projection.project(fixture.report(), new ReportFindings.Visible(fixture.findings(), false), List.of(review));
            assertSame(fixture.report().getStructuredContent(), publicView.readingContent().structuredContent());
        }

        for (var view : preparedViews(fixture.report(), reviews.size() * 2)) {
            assertTrue(view.excludedEvents());
            assertEquals(Set.of(11L, 12L), view.excludedEvidenceFindingIds());
        }
        assertEquals(2, fixture.report().getStructuredContent().importantEvents().size());
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void recipientSpecificReviewNeverBecomesACompanyDeliveryExclusion(ChannelType type) {
        Fixture fixture = fixture(false);
        prepare(fixture, List.of(review('a', Category.PREFERENCE, Status.COMPLETED, "PREFERENCE", RECIPIENT_ID)), Set.of());
        for (boolean byId : List.of(true, false)) {
            String body = render(fixture.report(), type, byId);
            assertTrue(body.contains("제외할 사건 제목"));
            assertTrue(body.contains("https://example.com/source-11"));
        }
        for (var view : preparedViews(fixture.report(), 2)) assertFalse(view.excludedEvents());
        verify(snapshots, never()).capture(any(), any());
    }

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    void onlyEmailAttachesPdfBuiltFromTheFinalRecipientFilteredView(ChannelType type) {
        Fixture fixture = fixture(true);
        prepare(fixture, List.of(confirmed('a')), Set.of(14L));
        var insight = NotificationInsightTestSupport.insight();
        var channel = NotificationChannel.builder().id(1L).channelType(type).maxLength(3500).active(true).build();

        var result = personalized.render(fixture.report(), channel, RECIPIENT_ID, insight);

        if (type == ChannelType.TELEGRAM) {
            assertTrue(result.attachments().isEmpty());
            verifyNoInteractions(pdfRenderer);
            return;
        }
        assertEquals(1, result.attachments().size());
        var attachment = result.attachments().getFirst();
        assertEquals("news-report-17.pdf", attachment.filename());
        assertEquals("application/pdf", attachment.contentType());
        assertArrayEquals(new byte[]{'%', 'P', 'D', 'F'}, attachment.content());
        var view = ArgumentCaptor.forClass(ReportEventFeedbackProjection.View.class);
        verify(pdfRenderer).render(eq(fixture.report()), view.capture(), same(insight));
        assertEquals(List.of("정상 사건 제목"), eventTitles(view.getValue()));
        assertEquals(List.of(12L, 13L), view.getValue().findings().stream().map(Finding::getId).toList());
        assertTrue(view.getValue().excludedEvidenceFindingIds().containsAll(Set.of(11L, 12L, 14L)));
        assertRejectedContentAbsent(view.getValue().readingContent().markdownBody());
        assertFalse(view.getValue().readingContent().markdownBody().contains("개인 제외"));
        assertEquals(List.of("정상 후속 확인"), view.getValue().readingContent().structuredContent().watchItems()
                .stream().map(ReportContent.WatchItem::topic).toList());
    }

    @Test
    void pdfPreparationFailureDoesNotReturnAnEmailWithoutItsAttachment() {
        Fixture fixture = fixture(false);
        prepare(fixture, List.of(), Set.of());
        var channel = NotificationChannel.builder().id(1L).channelType(ChannelType.EMAIL).maxLength(3500).active(true).build();
        var failure = new IllegalStateException("synthetic PDF preparation failure");
        when(pdfRenderer.render(any(), any(), any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> personalized.render(fixture.report(), channel, RECIPIENT_ID)));
    }

    private void prepare(Fixture fixture, List<Feedback> reviews, Set<Long> suppressed) {
        when(reports.findById(17L)).thenReturn(Optional.of(fixture.report()));
        when(findings.findForReportByRunId(42L)).thenReturn(fixture.findings());
        when(store.eventFeedback(17L)).thenReturn(reviews);
        when(snapshots.capture(eq(fixture.report()), any())).thenReturn(fixture.items());
        when(feedback.prepare(eq(fixture.report()), eq(RECIPIENT_ID), any(ReportEventFeedbackProjection.View.class)))
                .thenReturn(new DeliveryFeedback(suppressed, "synthetic-test-token"));
    }

    private List<ReportEventFeedbackProjection.View> preparedViews(NewsReport report, int count) {
        var captor = ArgumentCaptor.forClass(ReportEventFeedbackProjection.View.class);
        verify(feedback, times(count)).prepare(eq(report), eq(RECIPIENT_ID), captor.capture());
        return captor.getAllValues();
    }

    private String render(NewsReport report, ChannelType type, boolean byId) {
        NotificationChannel channel = NotificationChannel.builder().id(1L).channelType(type).name(type.name())
                .config(type == ChannelType.TELEGRAM ? Map.of("parseMode", "HTML") : Map.of())
                .maxLength(3500).active(true).build();
        var rendered = byId ? personalized.render(report.getId(), channel, RECIPIENT_ID)
                : personalized.render(report, channel, RECIPIENT_ID);
        return String.join("\n", rendered.chunks());
    }

    private static void assertRejectedContentAbsent(String body) {
        for (String rejected : List.of("제외할 사건 제목", "제외할 사건 주요 내용", "제외할 저장 요약",
                "제외할 저장 마크다운", "제외할 공유 관찰", "제외할 관찰 이유", "제외할 출처 설명",
                "https://example.com/source-11")) {
            assertFalse(body.contains(rejected), rejected + " must not be delivered");
        }
    }

    private static List<String> eventTitles(ReportEventFeedbackProjection.View view) {
        return view.readingContent().structuredContent().importantEvents().stream()
                .map(ReportContent.ImportantEvent::title).toList();
    }

    private static Fixture fixture(boolean personalEvent) {
        List<ReportContent.ImportantEvent> events = new ArrayList<>(List.of(
                new ReportContent.ImportantEvent("제외할 사건 제목", "제외할 사건 주요 내용", "제외할 해석", List.of(11L, 12L)),
                new ReportContent.ImportantEvent("정상 사건 제목", "정상 사건 주요 내용", "정상 해석", List.of(12L, 13L))));
        List<ReportContent.WatchItem> watches = new ArrayList<>(List.of(
                new ReportContent.WatchItem("제외할 공유 관찰", "제외할 관찰 이유", List.of(12L)),
                new ReportContent.WatchItem("정상 후속 확인", "정상 확인 이유", List.of(13L))));
        List<Finding> findings = new ArrayList<>(List.of(finding(11L), finding(12L), finding(13L)));
        if (personalEvent) {
            events.add(new ReportContent.ImportantEvent("개인 제외 사건", "개인 제외 요약", "", List.of(14L)));
            watches.add(new ReportContent.WatchItem("개인 제외 관찰", "개인 제외 이유", List.of(14L)));
            findings.add(finding(14L));
        }
        var content = new ReportContent(List.of("제외할 저장 요약"), events, watches, List.of("제외할 출처 설명"));
        var report = NewsReport.builder().id(17L).run(CollectionRun.builder().id(42L).build())
                .title("뉴스 보고서").markdownBody("## 핵심 요약\n제외할 저장 마크다운").structuredContent(content)
                .generatedAt(LocalDateTime.of(2026, 10, 1, 12, 0)).build();
        List<Item> items = java.util.stream.IntStream.range(0, events.size()).mapToObj(index -> {
            var event = events.get(index);
            return new Item(null, null, null, List.of(), null, null, null, null, null, null, null,
                    new EventContext(String.valueOf((char) ('a' + index)).repeat(64), index,
                            event.title(), event.summaryKo(), event.significance(), event.sourceFindingIds(),
                            List.of(), List.of(), null));
        }).toList();
        return new Fixture(report, List.copyOf(findings), items);
    }

    private static Finding finding(long id) {
        return Finding.builder().id(id)
                .article(Article.builder().id(100 + id).title("근거 기사 " + id).body("확보한 원문")
                        .canonicalUrl("https://example.com/source-" + id).fetchStatus(FetchStatus.FULLTEXT).build())
                .summary(id == 12L ? "공유 근거 대체 요약" : "기사별 대체 요약 " + id)
                .analysisSource(AnalysisSource.LLM)
                .keyPoints(List.of(new FindingKeyPoint("확인된 근거", List.of(0), "grounded")))
                .sensitivity(FindingSensitivity.legacy(SensitivityLevel.HIGH)).relevance(Relevance.IMPORTANT).build();
    }

    private static Feedback confirmed(char key) {
        return review(key, Category.SUMMARY_ERROR, Status.COMPLETED, "CONFIRMED_ERROR", null);
    }

    private static Feedback review(char key, Category category, Status status, String verdict, Long recipientId) {
        return new Feedback(1L, null, recipientId, 17L, null, category, "의견", false, "hash", status,
                verdict, "검토 설명", LocalDateTime.of(2026, 10, 1, 12, 0), null, String.valueOf(key).repeat(64));
    }

    private record Fixture(NewsReport report, List<Finding> findings, List<Item> items) { }
}
