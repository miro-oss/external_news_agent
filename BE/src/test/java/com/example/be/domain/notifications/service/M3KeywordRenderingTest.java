package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.keyword.ReportKeywordDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class M3KeywordRenderingTest {
    private final NotificationRenderer email = new NotificationRenderer(mock(FindingRepository.class),
            mock(TopicRelevancePolicy.class), mock(ReportEventFeedbackProjection.class), mock(NotificationDartSummary.class));
    private final ReportPdfRenderer pdf = new ReportPdfRenderer(mock(ReportDartService.class));
    private final NewsReport report = NewsReport.builder().id(17L).title("키워드로 보는 반도체 산업 · 예시 리포트")
            .generatedAt(LocalDateTime.of(2026, 10, 29, 9, 0)).build();
    private final ReportEventFeedbackProjection.View view = new ReportEventFeedbackProjection.View(List.of(),
            new ReportReadingContent("", new ReportContent(List.of("등록 키워드의 이슈 분포를 확인합니다."),
                    List.of(), List.of(), List.of())), false);

    @Test void staticEmailAndPdfShareCountsWithoutExternalAssetsAndPreserveNavigation() throws Exception {
        var stats = available(10, List.of(
                new ReportKeywordDTO.Item("HBM", 8, 80.0, List.of(new ReportKeywordDTO.RelatedKeyword("패키징", 5))),
                new ReportKeywordDTO.Item("패키징", 6, 60.0, List.of(new ReportKeywordDTO.RelatedKeyword("HBM", 5))),
                new ReportKeywordDTO.Item("차세대 고대역폭 메모리용 첨단 반도체 패키징 공정 장비 및 공급망", 3, 30.0, List.of()),
                new ReportKeywordDTO.Item("<script> & 미일치 키워드", 0, 0.0, List.of())));
        String html = renderEmail(stats);
        var dom = Jsoup.parse(html);
        assertTrue(dom.select("script,img").isEmpty());
        assertTrue(dom.text().contains("8건 / 전체 10건 · 80%"));
        assertTrue(dom.text().contains("6건 / 전체 10건 · 60%"));
        assertTrue(dom.text().contains("0건 / 전체 10건 · 0%"));
        assertTrue(dom.text().contains("함께 나온 키워드 · 패키징 5건"));
        assertTrue(dom.text().contains("비중 합이 100%를 넘을 수 있습니다"));
        assertTrue(html.contains("width=\"80%\""));
        var output = pdf.renderDetailed(report, view, null, null, null, stats);
        try (var document = Loader.loadPDF(output.bytes())) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s", "");
            assertTrue(text.contains("8건/전체10건·80%"));
            assertTrue(text.contains("함께나온키워드·패키징5건"));
            assertTrue(text.indexOf("키워드로보는이슈") < text.indexOf("중요이벤트"));
            assertTrue(document.getDocumentCatalog().getNames().getDests().getNames().keySet()
                    .containsAll(List.of("summary", "report-detail")));
            Path directory = Path.of("build/reports/notifications/m3");
            Files.createDirectories(directory);
            Files.write(directory.resolve("keywords.pdf"), output.bytes());
            Files.writeString(directory.resolve("keywords-email.html"), html);
            var raster = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++)
                ImageIO.write(raster.renderImageWithDPI(page, 110), "png", directory.resolve("keywords-" + (page + 1) + ".png").toFile());
        }
    }

    @Test void zeroDenominatorAndUnavailableAreNotPresentedAsZeroPercent() throws Exception {
        var empty = available(0, List.of(new ReportKeywordDTO.Item("HBM", 0, null, List.of())));
        String html = renderEmail(empty);
        assertTrue(html.contains("비중 계산 안 함"));
        assertFalse(Jsoup.parse(html).text().contains("· 0%"));
        assertFalse(html.contains("bgcolor=\"#1b64da\""));
        for (String reason : List.of("NO_SNAPSHOT", "INCOMPLETE_SOURCE", "MISSING_COLLECTION_CONTEXT",
                "INVALID_SNAPSHOT", "COMPANY_SCOPE_UNVERIFIED", "PERSONALIZATION_UNVERIFIED")) {
            var unavailable = new ReportKeywordDTO.View(ReportKeywordDTO.Status.UNAVAILABLE, reason, null, null,
                    "해당 실행 수집 기준", null, List.of(), 0);
            String notice = NotificationKeywordSection.unavailable(reason);
            assertTrue(renderEmail(unavailable).contains(notice));
            assertFalse(renderEmail(unavailable).contains("전체 0건"));
            try (var document = Loader.loadPDF(pdf.renderDetailed(report, view, null, null, null, unavailable).bytes())) {
                assertTrue(new PDFTextStripper().getText(document).replaceAll("\\s", "").contains(notice.replaceAll("\\s", "")));
            }
        }
    }

    @Test void telegramKeepsItsExistingOutputWhenKeywordDataIsProvided() {
        var channel = NotificationChannel.builder().channelType(ChannelType.TELEGRAM).maxLength(3500).build();
        var withKeywords = email.render(report, channel, view, null, null, Map.of(), null, null, available(10, List.of()));
        var without = email.render(report, channel, view, null, null, Map.of(), null, null, null);
        assertEquals(without.chunks(), withKeywords.chunks());
    }

    private String renderEmail(ReportKeywordDTO.View stats) {
        return email.render(report, NotificationChannel.builder().channelType(ChannelType.EMAIL).maxLength(3500).build(),
                view, null, null, Map.of(), null, null, stats).chunks().getFirst();
    }

    private ReportKeywordDTO.View available(int count, List<ReportKeywordDTO.Item> items) {
        return new ReportKeywordDTO.View(ReportKeywordDTO.Status.AVAILABLE, null, "report-keywords.v1",
                OffsetDateTime.parse("2026-10-29T09:00:00+09:00"), "2026-10-28 수집 기준 (한국시간)", count, items, 0);
    }
}
