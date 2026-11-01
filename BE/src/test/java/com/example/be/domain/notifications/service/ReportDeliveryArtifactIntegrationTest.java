package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.feedback.service.FeedbackDeliveryService;
import com.example.be.domain.feedback.service.ReportEventSnapshotFactory;
import com.example.be.domain.notifications.channel.EmailNotificationSender;
import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.entity.*;
import com.example.be.domain.reports.coverage.*;
import com.example.be.domain.reports.dart.*;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.keyword.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.*;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

import static com.example.be.domain.feedback.model.FeedbackModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real final projections, HTML/PDF rendering and MIME encoding; only persistence and SMTP are isolated. */
class ReportDeliveryArtifactIntegrationTest {
    enum Mode { MANUAL_NEUTRAL, MANUAL_INSIGHT, AUTOMATIC_READY, AUTOMATIC_UNAVAILABLE }
    private static final OffsetDateTime CAPTURED = OffsetDateTime.parse("2026-10-30T09:00:00+09:00");

    @ParameterizedTest
    @EnumSource(Mode.class)
    void recipientArtifactsRemainConsistentThroughMimeSerialization(Mode mode) throws Exception {
        var reports = mock(NewsReportRepository.class);
        var findings = mock(FindingRepository.class);
        var articles = mock(ArticleRepository.class);
        var relevance = TopicRelevanceTestSupport.legacyPolicy();
        var store = mock(FeedbackStore.class);
        var snapshots = mock(ReportEventSnapshotFactory.class);
        var feedback = mock(FeedbackDeliveryService.class);
        var daily = mock(DailyReportFeedbackExclusions.class);
        var projection = new ReportEventFeedbackProjection(store, snapshots, daily);
        var storedDart = new StoredReportDartFixture();
        var dart = storedDart.service();
        var renderer = new NotificationRenderer(findings, relevance, projection, new NotificationDartSummary(dart));
        renderer.setCoverageService(new ReportCoverageService(articles, relevance));
        renderer.setKeywordService(new ReportKeywordService(articles, relevance, daily));
        var personalized = new PersonalizedNotificationRenderer(reports, findings, relevance, feedback,
                renderer, projection, new ReportPdfRenderer(dart));

        var inputs = List.of(finding(11), finding(12), finding(13));
        var report = report(inputs);
        when(reports.findById(17L)).thenReturn(Optional.of(report));
        when(findings.findForReportByRunId(42L)).thenReturn(inputs);
        when(articles.findAllById(any())).thenAnswer(call -> {
            var ids = (Iterable<?>) call.getArgument(0);
            var selected = new HashSet<Object>();
            ids.forEach(selected::add);
            return inputs.stream().map(Finding::getArticle).filter(a -> selected.contains(a.getId())).toList();
        });
        when(snapshots.capture(eq(report), any())).thenReturn(inputs.stream().map(f -> new Item(
                null, null, null, List.of(), null, null, null, null, null, null, null,
                new EventContext(key(f.getId()), f.getId().intValue() - 11, title(f.getId()),
                        "확인한 변화 " + f.getId(), "조건부 의미 " + f.getId(), List.of(f.getId()),
                        List.of(), List.of(), null))).toList());
        when(store.eventFeedback(17L)).thenReturn(List.of(new Feedback(1L, null, null, 17L, null,
                Category.PREFERENCE, "공용 전달 제외", false, "synthetic", Status.PENDING, null, null,
                LocalDateTime.of(2026, 10, 30, 10, 0), null, key(12))));
        when(feedback.prepare(eq(report), anyLong(), any(ReportEventFeedbackProjection.View.class)))
                .thenAnswer(call -> new DeliveryFeedback(call.getArgument(1).equals(2L) ? Set.of(13L) : Set.of(),
                        "synthetic-recipient-" + call.getArgument(1)));
        when(feedback.hasActivePersonalPolicies(2L)).thenReturn(true);
        storedDart.ready(report, inputs.stream().map(f -> company(f.getId())).toList());
        var insight = NotificationInsightTestSupport.insight(List.of(
                new AgentReportInsightResponse.Overview("허용 판단", List.of("11:0"), "허용 조건"),
                new AgentReportInsightResponse.Overview("개인 제외 판단", List.of("13:0"), "개인 제외 조건"),
                new AgentReportInsightResponse.Overview("공용 제외 판단", List.of("12:0"), "공용 제외 조건")), List.of());
        var channel = NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL).active(true)
                .maxLength(Integer.MAX_VALUE).config(Map.of("host", "smtp.invalid", "port", 25,
                        "from", "report@example.test")).build();

        var publicPreview = renderer.render(report, channel);
        assertTrue(publicPreview.attachments().isEmpty());
        assertFalse(publicPreview.chunks().getFirst().contains("공용 제외"));
        assertTrue(publicPreview.chunks().getFirst().contains("개인 제외 사건"));
        assertFalse(publicPreview.chunks().getFirst().contains("synthetic-recipient"));

        var artifacts = new ArrayList<RenderedNotification>();
        for (long recipient : List.of(1L, 2L)) {
            artifacts.add(switch (mode) {
                case MANUAL_NEUTRAL -> personalized.render(report, channel, recipient);
                case MANUAL_INSIGHT -> personalized.render(report, channel, recipient, insight);
                case AUTOMATIC_READY -> personalized.renderAutomatic(17L, channel, recipient,
                        AutomaticNotificationInsightService.Selection.ready(insight));
                case AUTOMATIC_UNAVAILABLE -> personalized.renderAutomatic(17L, channel, recipient,
                        AutomaticNotificationInsightService.Selection.unavailable(Audience.EQUIPMENT_MAKER));
            });
        }
        var messages = serializeThroughSender(channel, artifacts);
        for (int index = 0; index < messages.size(); index++) {
            var wire = messages.get(index);
            assertEquals("recipient-" + (index + 1) + "@example.test", wire.getRecipients(Message.RecipientType.TO)[0].toString());
            assertTrue(wire.isMimeType("multipart/mixed"));
            var multipart = (Multipart) wire.getContent();
            assertEquals(2, multipart.getCount());
            assertTrue(multipart.getBodyPart(0).isMimeType("text/html"));
            String html = (String) multipart.getBodyPart(0).getContent();
            // SMTP canonicalizes text line endings to CRLF; content and attachment bytes must survive intact.
            assertEquals(artifacts.get(index).chunks().getFirst().replace("\r\n", "\n"), html.replace("\r\n", "\n"));
            var attachment = multipart.getBodyPart(1);
            assertEquals(Part.ATTACHMENT, attachment.getDisposition());
            assertEquals("news-report-17.pdf", attachment.getFileName());
            assertTrue(attachment.isMimeType("application/pdf"));
            byte[] bytes = attachment.getInputStream().readAllBytes();
            assertArrayEquals(artifacts.get(index).attachments().getFirst().content(), bytes);
            try (var document = Loader.loadPDF(bytes)) {
                String pdf = new PDFTextStripper().getText(document);
                for (String text : List.of(Jsoup.parse(html).text(), pdf)) {
                    assertTrue(text.contains("허용 사건"));
                    assertTrue(text.contains("허용기업"));
                    assertFalse(text.contains("공용 제외"));
                    assertFalse(text.contains("공용제외기업"));
                    assertEquals(index == 0, text.contains("개인 제외 사건"));
                    assertEquals(index == 0, text.contains("개인제외기업"));
                    assertTrue(text.contains("관련 기사 1건 · 1개 매체"));
                    String compact = text.replaceAll("\\s", "");
                    if (index == 0) assertTrue(compact.contains("2건/전체2건·100%"));
                    else assertFalse(text.contains("키워드로 보는 이슈"));
                    assertEquals(mode == Mode.MANUAL_INSIGHT || mode == Mode.AUTOMATIC_READY, text.contains("허용 판단"));
                    if (mode == Mode.AUTOMATIC_UNAVAILABLE)
                        assertTrue(compact.contains("관점분석이준비되지않아기본보고서만전달합니다."));
                }
                var destinations = document.getDocumentCatalog().getNames().getDests();
                int firstPage = destinations.getValue("company-0").retrievePageNumber() + 1;
                assertTrue(html.contains("첨부 PDF p. " + firstPage + "에서 상세 확인"));
                assertEquals(index == 0, destinations.getValue("company-1") != null);
                assertNull(destinations.getValue("company-2"));
                var urls = new ArrayList<>(Jsoup.parse(html).select("a").eachAttr("href"));
                for (var page : document.getPages()) for (var annotation : page.getAnnotations())
                    if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri)
                        urls.add(uri.getURI());
                assertTrue(urls.contains("https://example.com/article-11"));
                assertFalse(urls.contains("https://example.com/article-12"));
                if (index == 1) assertFalse(urls.contains("https://example.com/article-13"));
                assertTrue(urls.contains(StoredReportDartFixture.FILING_URL));
                assertTrue(urls.stream().noneMatch(url -> url.contains("localhost") || url.contains("127.0.0.1")));
                if (mode == Mode.AUTOMATIC_READY) {
                    var folder = Path.of("build/reports/notifications/m6");
                    Files.createDirectories(folder);
                    Files.writeString(folder.resolve("recipient-" + (index + 1) + ".html"), html);
                    Files.write(folder.resolve("recipient-" + (index + 1) + ".pdf"), bytes);
                }
            }
        }
        assertNotEquals(messages.get(0).getHeader("References", null), messages.get(1).getHeader("References", null));
        assertEquals(3, report.getStructuredContent().importantEvents().size());
        assertEquals(3, report.getReportIssueCompanySnapshot().links().size());
        storedDart.verifyStoredReads(17L, 3); // preview plus once per recipient, shared by email and PDF
        verify(feedback, times(2)).prepare(eq(report), anyLong(), any(ReportEventFeedbackProjection.View.class));
    }

    private static List<MimeMessage> serializeThroughSender(NotificationChannel channel, List<RenderedNotification> artifacts) throws Exception {
        Transport transport = mock(Transport.class);
        var captured = new ArrayList<byte[]>();
        doAnswer(call -> {
            var out = new ByteArrayOutputStream();
            ((Message) call.getArgument(0)).writeTo(out);
            captured.add(out.toByteArray());
            return null;
        }).when(transport).sendMessage(any(), any());
        try (var sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.getInstance(any(Properties.class), nullable(Authenticator.class)))
                    .thenAnswer(call -> {
                        var session = spy((Session) call.callRealMethod());
                        doReturn(transport).when(session).getTransport("smtp");
                        return session;
                    });
            var sender = new EmailNotificationSender(new NotificationProperties());
            try (var session = sender.openSession(channel)) {
                for (int index = 0; index < artifacts.size(); index++) {
                    var artifact = artifacts.get(index);
                    session.send("recipient-" + (index + 1) + "@example.test", artifact.subject(),
                            artifact.chunks().getFirst(), artifact.attachments());
                }
            }
        }
        var result = new ArrayList<MimeMessage>();
        for (var raw : captured) result.add(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw)));
        verify(transport).close();
        return result;
    }

    private static NewsReport report(List<Finding> inputs) {
        var content = new ReportContent(List.of(), inputs.stream().map(f -> new ReportContent.ImportantEvent(
                title(f.getId()), "확인한 변화 " + f.getId(), "조건부 의미 " + f.getId(), List.of(f.getId()))).toList(), List.of(), List.of());
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).reportStatus(ReportStatus.PENDING)
                .run(CollectionRun.builder().id(42L).build()).title("수신자별 리포트 · 가상 검증 자료")
                .generatedAt(CAPTURED.toLocalDateTime()).structuredContent(content).markdownBody("").build();
        report.freezeReportCoverageSnapshot(new ReportCoverageSnapshot(ReportCoverageSnapshot.RULE_VERSION, CAPTURED,
                "해당 실행 수집 기준", inputs.stream().map(f -> new ReportCoverageSnapshot.Observation(f.getId(), 42L,
                        1L, f.getId(), f.getArticle().getId(), "example.com", "가상 매체", f.getArticle().getTitle(),
                        f.getArticle().getCanonicalUrl(), CAPTURED, null)).toList(),
                inputs.stream().map(f -> new ReportCoverageSnapshot.Binding(f.getId(), f.getId(), 1L)).toList()));
        report.freezeReportKeywordSnapshot(new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION, CAPTURED,
                "해당 실행 수집 기준", null, List.of(new ReportKeywordSnapshot.Definition("hbm", "HBM")),
                inputs.stream().map(f -> new ReportKeywordSnapshot.Candidate(f.getId(), f.getArticle().getId(), 42L,
                        1L, f.getId(), List.of("hbm"))).toList()));
        report.freezeReportIssueCompanySnapshot(new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION,
                CAPTURED, inputs.stream().map(f -> new ReportIssueCompanySnapshot.Link(company(f.getId()), f.getId(),
                        f.getArticle().getId(), f.getId(), company(f.getId()).corpName(), "기업 식별 원문", f.getArticle().getCanonicalUrl(),
                        List.of("20260814001234"))).toList()));
        ReflectionTestUtils.setField(report, "reportStatus", ReportStatus.GENERATED);
        return report;
    }

    private static Finding finding(long id) {
        return Finding.builder().id(id).article(Article.builder().id(100L + id).title(title(id))
                        .canonicalUrl("https://example.com/article-" + id).body("확보된 원문").fetchStatus(FetchStatus.FULLTEXT).build())
                .summary("기사 요약 " + id).analysisSource(AnalysisSource.LLM).relevance(Relevance.IMPORTANT)
                .keyPoints(List.of(new FindingKeyPoint("검증 근거", List.of(0), "grounded")))
                .sensitivity(FindingSensitivity.legacy(SensitivityLevel.HIGH)).build();
    }
    private static String key(long id) { return String.valueOf((char) ('a' + id - 11)).repeat(64); }
    private static String title(long id) { return id == 11 ? "허용 사건" : id == 12 ? "공용 제외 사건" : "개인 제외 사건"; }
    private static Company company(long id) {
        return new Company(String.format(Locale.ROOT, "%08d", id), id == 11 ? "허용기업" : id == 12 ? "공용제외기업" : "개인제외기업", "");
    }
}
