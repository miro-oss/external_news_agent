package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingSensitivity;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.feedback.model.FeedbackModels.*;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.FeedbackDeliveryService;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.DailyReportFeedbackExclusions;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationWatchItemPromotionTest {
    private static final long RECIPIENT = 99L;
    private final FindingRepository findings = mock(FindingRepository.class);
    private final TopicRelevancePolicy relevance = mock(TopicRelevancePolicy.class);
    private final FeedbackStore store = mock(FeedbackStore.class);
    private final ReportEventSnapshotFactory snapshots = new ReportEventSnapshotFactory(mock(JdbcTemplate.class), new ObjectMapper());
    private final ReportEventFeedbackProjection projection = new ReportEventFeedbackProjection(store, snapshots, mock(DailyReportFeedbackExclusions.class));
    private final NotificationRenderer renderer = new NotificationRenderer(findings, relevance, projection, mock(NotificationDartSummary.class));
    private final FeedbackDeliveryService feedback = mock(FeedbackDeliveryService.class);
    private final PersonalizedNotificationRenderer personalized = new PersonalizedNotificationRenderer(mock(NewsReportRepository.class), findings,
            relevance, feedback, renderer, projection, new ReportPdfRenderer(mock(ReportDartService.class)));
    private final NotificationChannel email = NotificationChannel.builder().channelType(ChannelType.EMAIL).maxLength(3500).build();

    @BeforeEach void relevance() {
        when(relevance.filterFindings(any())).thenAnswer(call -> call.getArgument(0));
        when(feedback.prepare(any(), eq(RECIPIENT), any(ReportEventFeedbackProjection.View.class)))
                .thenReturn(new DeliveryFeedback(Set.of(), null));
    }

    @Test void previewAndActualEmailIncludePromotedCardBeyondDefaultTwoAndPdfKeepsAllEvents() throws Exception {
        var report = report(3, 1, "공식 발표가 나오면 공급 일정을 확인합니다.");
        var original = report.getStructuredContent();
        report.promoteWatchItem(0);
        String preview = renderer.render(report, email).chunks().getFirst();
        var delivered = personalized.render(report, email, RECIPIENT);
        for (String html : List.of(preview, delivered.chunks().getFirst())) {
            assertEquals(List.of("기존 사건 1", "기존 사건 2", "올린 관찰 1"), cardTitles(html));
            assertEquals(List.of("1. 기존 사건 1", "2. 기존 사건 2"), briefingTitles(html));
            assertTrue(html.contains("공식 발표가 나오면 공급 일정을 확인합니다."));
            assertTrue(html.contains("https://example.com/source-201"));
        }
        var pdf = pdf(delivered);
        for (String title : List.of("기존 사건 1", "기존 사건 2", "기존 사건 3", "올린 관찰 1")) assertTrue(pdf.contains(title), title);
        assertTrue(pdf.contains("공식 발표가 나오면 공급 일정을 확인합니다."));
        assertSame(original, report.getStructuredContent());
        assertEquals("원본 본문", report.getMarkdownBody());
    }

    @Test void everyExplicitSelectionIsIncludedInSavedSelectionOrderWithoutChangingTheCoreBriefing() {
        var report = report(3, 3, "조건이 충족되면 확인합니다.");
        report.promoteWatchItem(2);
        report.promoteWatchItem(0);
        report.promoteWatchItem(1);
        String html = renderer.render(report, email).chunks().getFirst();
        assertEquals(List.of("기존 사건 1", "기존 사건 2", "올린 관찰 3", "올린 관찰 1", "올린 관찰 2"), cardTitles(html));
        assertEquals(List.of("1. 기존 사건 1", "2. 기존 사건 2"), briefingTitles(html));
    }

    @Test void selectionAlreadyWithinDefaultTwoIsNotRepeated() {
        var report = report(1, 1, "후속 조건을 확인합니다.");
        report.promoteWatchItem(0);
        assertEquals(List.of("기존 사건 1", "올린 관찰 1"), cardTitles(renderer.render(report, email).chunks().getFirst()));
    }

    @Test void reportsWithoutExplicitSelectionsKeepDefaultEmailAndTelegramLimits() {
        var report = report(4, 1, "관찰만 하는 항목입니다.");
        assertEquals(List.of("기존 사건 1", "기존 사건 2"), cardTitles(renderer.render(report, email).chunks().getFirst()));
        String telegram = renderer.render(report, NotificationChannel.builder().channelType(ChannelType.TELEGRAM).maxLength(3500).build())
                .chunks().getFirst();
        assertTrue(telegram.contains("기존 사건 3"));
        assertFalse(telegram.contains("기존 사건 4"));
        assertFalse(telegram.contains("올린 관찰 1"));
    }

    @Test void submittedLocalFeedbackStillRemovesPromotedCardsAndTheirPdfEvidence() throws Exception {
        var report = report(3, 2, "조건이 충족되면 확인합니다.");
        report.promoteWatchItem(0);
        report.promoteWatchItem(1);
        var rejected = snapshots.capture(report, visible(report)).stream().filter(item -> item.event().title().equals("올린 관찰 1")).findFirst().orElseThrow();
        when(store.eventFeedback(17L)).thenReturn(List.of(new Feedback(1L, null, null, 17L, null, Category.OTHER, "제외 의견", false,
                "hash", Status.PENDING, null, null, LocalDateTime.of(2026, 11, 9, 12, 0), rejected, rejected.event().key())));
        var actual = personalized.render(report, email, RECIPIENT);
        for (String html : List.of(renderer.render(report, email).chunks().getFirst(), actual.chunks().getFirst())) {
            assertEquals(List.of("기존 사건 1", "기존 사건 2", "올린 관찰 2"), cardTitles(html));
            assertFalse(html.contains("https://example.com/source-201"));
        }
        assertFalse(pdf(actual).contains("올린 관찰 1"));
        assertFalse(pdf(actual).contains("https://example.com/source-201"));
        assertTrue(pdf(actual).contains("올린 관찰 2"));
    }

    @Test void finalPersonalSuppressionCannotBeBypassedByAStoredSelection() throws Exception {
        var report = report(3, 2, "조건이 충족되면 확인합니다.");
        report.promoteWatchItem(0);
        report.promoteWatchItem(1);
        when(feedback.prepare(eq(report), eq(RECIPIENT), any(ReportEventFeedbackProjection.View.class)))
                .thenReturn(new DeliveryFeedback(Set.of(201L), null));
        var actual = personalized.render(report, email, RECIPIENT);
        assertEquals(List.of("기존 사건 1", "기존 사건 2", "올린 관찰 2"), cardTitles(actual.chunks().getFirst()));
        assertFalse(actual.chunks().getFirst().contains("https://example.com/source-201"));
        assertFalse(pdf(actual).contains("올린 관찰 1"));
        assertFalse(pdf(actual).contains("https://example.com/source-201"));
        assertTrue(pdf(actual).contains("올린 관찰 2"));
    }

    @Test void longPromotedReasonKeepsItsCardAndSourceWhileFullConditionalTextRemainsInPdf() throws Exception {
        String reason = "공식 발표가 나오면 확인합니다. " + "조건을 확인할 때까지 확정하지 않습니다. ".repeat(16);
        var report = report(2, 1, reason);
        report.promoteWatchItem(0);
        var actual = personalized.render(report, email, RECIPIENT);
        String html = actual.chunks().getFirst();
        assertEquals(List.of("기존 사건 1", "기존 사건 2", "올린 관찰 1"), cardTitles(html));
        assertTrue(html.contains("긴 요약과 후속 확인 내용은 첨부 PDF에서 확인하세요."));
        assertTrue(html.contains("https://example.com/source-201"));
        assertFalse(html.contains("공식 발표가 나오면 확인합니다."), "Do not deliver a clipped version of the conditional paragraph");
        assertTrue(pdf(actual).replaceAll("\\s", "").contains(reason.replaceAll("\\s", "")));
    }

    @Test void selectionRequiresExactFinalTextAndAllEvidenceInsteadOfTitleOrSourceSimilarity() {
        var report = report(2, 1, "원래 관찰 조건");
        report.promoteWatchItem(0);
        var original = report.getStructuredContent();
        var events = new ArrayList<>(original.importantEvents());
        events.add(new ReportContent.ImportantEvent("올린 관찰 1", "다른 내용", null, List.of(201L)));
        var changed = new ReportContent(List.of(), events, List.of(), List.of());
        var view = new ReportEventFeedbackProjection.View(visible(report).findings(), new ReportReadingContent("", changed), false);
        assertEquals(List.of("기존 사건 1", "기존 사건 2"), cardTitles(renderer.render(report, email, view, null).chunks().getFirst()));
        var full = projection.projectForDelivery(report, visible(report), List.of());
        var missingEvidence = new ReportEventFeedbackProjection.View(full.findings().stream().filter(f -> f.getId() != 201L).toList(), full.readingContent(), false);
        assertEquals(List.of("기존 사건 1", "기존 사건 2"), cardTitles(renderer.render(report, email, missingEvidence, null).chunks().getFirst()));
    }

    private NewsReport report(int ordinaryCount, int watchCount, String reason) {
        var events = IntStream.rangeClosed(1, ordinaryCount).mapToObj(i -> new ReportContent.ImportantEvent(
                "기존 사건 " + i, "기존 사건 내용 " + i, "", List.of(100L + i))).toList();
        var watches = IntStream.rangeClosed(1, watchCount).mapToObj(i -> new ReportContent.WatchItem(
                "올린 관찰 " + i, reason, List.of(200L + i))).toList();
        var sources = new ArrayList<Finding>();
        IntStream.rangeClosed(1, ordinaryCount).forEach(i -> sources.add(finding(100L + i)));
        IntStream.rangeClosed(1, watchCount).forEach(i -> sources.add(finding(200L + i)));
        when(findings.findForReportByRunId(42L)).thenReturn(sources);
        return NewsReport.builder().id(17L).title("승격 보고서").run(CollectionRun.builder().id(42L).build())
                .generatedAt(LocalDateTime.of(2026, 11, 9, 12, 0)).markdownBody("원본 본문")
                .structuredContent(new ReportContent(List.of(), events, watches, List.of())).build();
    }

    private ReportFindings.Visible visible(NewsReport report) { return ReportFindings.loadVisible(report, findings, relevance); }
    private static Finding finding(long id) {
        return Finding.builder().id(id).run(CollectionRun.builder().id(42L).build()).article(Article.builder().id(id + 1000)
                .title("기사 " + id).body("확보한 본문 " + id).fetchStatus(FetchStatus.FULLTEXT)
                .canonicalUrl("https://example.com/source-" + id).build()).summary("분석 " + id)
                .sensitivity(FindingSensitivity.legacy(SensitivityLevel.HIGH)).relevance(Relevance.IMPORTANT).build();
    }
    private static List<String> cardTitles(String html) { return Jsoup.parse(html).select("td[bgcolor='#f9fafb'] h2").eachText(); }
    private static List<String> briefingTitles(String html) { return Jsoup.parse(html).select("td[bgcolor='#e8f3ff'] h3").eachText(); }
    private static String pdf(RenderedNotification rendered) throws Exception {
        try (var document = Loader.loadPDF(rendered.attachments().getFirst().content())) { return new PDFTextStripper().getText(document); }
    }
}
