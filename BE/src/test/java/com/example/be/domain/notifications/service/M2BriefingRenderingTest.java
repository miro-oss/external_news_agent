package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.WatchItem;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.briefing.ReportBriefing;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class M2BriefingRenderingTest {
    private final NewsReport report = NewsReport.builder().id(17L).title("AI 인프라 · 핵심 브리핑").build();
    private final ReportPdfRenderer pdf = new ReportPdfRenderer(mock(ReportDartService.class));
    private final NotificationRenderer email = new NotificationRenderer(mock(FindingRepository.class),
            TopicRelevanceTestSupport.legacyPolicy(), mock(ReportEventFeedbackProjection.class), mock(NotificationDartSummary.class));
    private static final String LONG_TEXT = "공급사별 인증 진행 상황과 생산 준비 계획을 추가로 확인하고 있습니다. ".repeat(6)
            + "다만 공급 계약이나 실제 납품은 확정되지 않았습니다.";

    @Test
    void sameBoundedBriefPreservesForecastConditionsAndMovesWholeLongItemsIntoOfflineDetail() throws Exception {
        var view = view(false);
        var insight = insight();
        var brief = ReportBriefing.from(view);
        String html = email.render(report, channel(ChannelType.EMAIL, 3500), view, null, insight).chunks().getFirst();
        var dom = Jsoup.parse(html);
        assertEquals(1, brief.items().size());
        assertEquals(1, brief.omittedItemCount());
        assertTrue(html.indexOf("핵심 브리핑") < html.indexOf("관점 인사이트"));
        assertTrue(html.indexOf("관점 인사이트") < html.indexOf("주요 이슈"));
        assertTrue(dom.text().contains("계획·전망"));
        assertTrue(dom.text().contains("고객 인증이 완료되고 투자가 집행되는 경우"));
        assertTrue(dom.text().contains("계약 공시가 확인되면 공급 일정을 다시 검토"));
        assertFalse(html.contains("원문: 「"));
        assertFalse(html.contains("공급사별 인증 진행 상황"));
        assertFalse(html.contains("자유 서술 헤드라인"));
        assertEquals(1, dom.select("p").stream().filter(p -> p.text().equals(brief.items().getFirst().summary())).count());

        var rendered = pdf.renderDetailed(report, view, insight);
        try (var document = Loader.loadPDF(rendered.bytes())) {
            String text = new PDFTextStripper().getText(document);
            String normalized = text.replaceAll("\\s", "");
            assertTrue(normalized.contains(LONG_TEXT.replaceAll("\\s", "")));
            assertTrue(text.indexOf("핵심 브리핑") < text.indexOf("중요 이벤트"));
            assertTrue(text.indexOf("관점 한눈에") < text.indexOf("중요 이벤트"));
            assertTrue(text.contains("원문: 「")); // Quotes remain available in the full analysis.
            var destinations = document.getDocumentCatalog().getNames().getDests().getNames();
            assertTrue(destinations.keySet().containsAll(Set.of("summary", "report-detail", "insight-detail")));
            Path directory = Path.of("build/reports/notifications/m2");
            Files.createDirectories(directory);
            Files.write(directory.resolve("briefing.pdf"), rendered.bytes());
            Files.writeString(directory.resolve("briefing-email.html"), html);
            var renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++)
                ImageIO.write(renderer.renderImageWithDPI(page, 110), "png", directory.resolve("briefing-" + (page + 1) + ".png").toFile());
        }
    }

    @Test
    void rejectedEvidenceCannotReappearInBriefOrFullAnalysisAcrossChannels() throws Exception {
        var view = view(true);
        String html = email.render(report, channel(ChannelType.EMAIL, 3500), view, null, insight()).chunks().getFirst();
        assertFalse(html.contains("고객 인증"));
        assertFalse(html.contains("설비 준비 일정"));
        assertFalse(html.contains("계약 공시가"));
        try (var document = Loader.loadPDF(pdf.render(report, view, insight()))) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("고객 인증"));
            assertFalse(text.contains("설비 준비 일정"));
            assertFalse(text.contains("계약 공시가"));
        }
    }

    @Test
    void automaticUnpreparedAudienceIsVisibleWithoutDroppingTheBaseReportOrBreakingTelegramBudget() throws Exception {
        var view = view(false);
        String notice = NotificationBriefingSection.unavailable(Audience.EQUIPMENT_MAKER);
        for (var type : ChannelType.values()) {
            String body = email.render(report, channel(type, 3500), view, null, null, Map.of(), null,
                    Audience.EQUIPMENT_MAKER).chunks().getFirst();
            assertTrue(body.contains(notice));
            assertTrue(body.contains("후속 생산 계획"));
        }
        for (int budget : List.of(12, 80, 120, 300, 600, 3500)) {
            String body = email.render(report, channel(ChannelType.TELEGRAM, budget), view, null, null,
                    Map.of(), null, Audience.EQUIPMENT_MAKER).chunks().getFirst();
            assertTrue(body.length() <= budget);
            assertFalse(body.contains("관점 분석이 준비되지 않아") && !body.contains(notice));
        }
        try (var document = Loader.loadPDF(pdf.renderDetailed(report, view, null, null,
                Audience.EQUIPMENT_MAKER).bytes())) {
            assertTrue(new PDFTextStripper().getText(document).replaceAll("\\s", "").contains(notice.replaceAll("\\s", "")));
        }
    }

    private ReportEventFeedbackProjection.View view(boolean exclude) {
        var first = new ReportContent.ImportantEvent("후속 생산 계획 발표", "회사는 내년 공급 확대 계획을 발표했습니다.",
                "실제 공급으로 이어지는 조건을 확인할 필요가 있습니다.", List.of(11L));
        var longItem = new ReportContent.ImportantEvent("공급 준비 현황", LONG_TEXT, "", List.of(12L));
        var content = new ReportContent(List.of("후속 계획과 공급 준비 상황을 확인합니다."),
                exclude ? List.of(longItem) : List.of(first, longItem), List.of(), List.of());
        return new ReportEventFeedbackProjection.View(List.of(finding(11), finding(12)),
                new ReportReadingContent("", content), exclude, exclude ? Set.of(11L) : Set.of());
    }

    private ReportInsightDTO.AudienceInsight insight() {
        return new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "자유 서술 헤드라인", "high",
                List.of(new Overview("원문: 「회사는 내년 공급 확대 계획을 발표했습니다.」 해석: 설비 준비 일정 확인이 필요할 수 있습니다.",
                        List.of("11:0"), "원문: 「아직 인증이 끝나지 않았다.」 미확인 가정: 고객 인증이 완료되고 투자가 집행되는 경우")), List.of(),
                List.of(new ReportInsightDTO.Fact("11:0", "회사는 내년 공급 확대 계획을 발표했습니다.", "FORECAST", null,
                        11L, 111L, List.of(0), "grounded")), List.of(),
                List.of(new WatchItem("원문: 「공급계획 발표」 관찰 항목: 실제 공급 일정", "원문: 「공시 확인」 해석: 후속 계약 공시",
                        "원문: 「실제 계약 미정」 반증 조건: 계약 공시가 확인되면 공급 일정을 다시 검토", List.of("11:0"))),
                "test", "test", null);
    }

    private Finding finding(long id) {
        return Finding.builder().id(id).summary("저장된 요약")
                .article(Article.builder().id(id + 100).canonicalUrl("https://example.com/article-" + id).build()).build();
    }

    private NotificationChannel channel(ChannelType type, int maximum) {
        return NotificationChannel.builder().id(1L).channelType(type).maxLength(maximum).active(true).build();
    }
}
