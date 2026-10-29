package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class M4ChangesRenderingTest {
    private final NotificationRenderer email = new NotificationRenderer(mock(FindingRepository.class),
            mock(TopicRelevancePolicy.class), mock(ReportEventFeedbackProjection.class), mock(NotificationDartSummary.class));
    private final ReportPdfRenderer pdf = new ReportPdfRenderer(mock(ReportDartService.class));
    private final NewsReport report = NewsReport.builder().id(17L).title("반도체 산업 · 주간 변화 브리핑")
            .generatedAt(LocalDateTime.of(2026, 10, 29, 9, 0)).build();
    private final ReportEventFeedbackProjection.View view = new ReportEventFeedbackProjection.View(List.of(),
            new ReportReadingContent("", new ReportContent(List.of("실제 수집 기간의 이슈와 등록 키워드 변화를 확인합니다."),
                    List.of(), List.of(), List.of())), false);

    @Test void staticPairedBarsShareNumbersAndKeepLongClaimsInOfflineDetails() throws Exception {
        String longSummary = "발표 조건과 실제 공급 일정 확인이 필요합니다. ".repeat(15);
        var changes = ready(List.of(new ReportChanges.Item("new", ReportChanges.Type.NEWLY_INCLUDED,
                        "첨단 패키징 투자 계획이 이번 보고서에 포함됐습니다", "투자 계획의 발표와 실제 집행을 구분해 확인합니다.", null, side()),
                new ReportChanges.Item("updated", ReportChanges.Type.UPDATED, "HBM 공급 일정 업데이트", longSummary, side(), side())));
        String html = renderEmail(changes);
        var dom = Jsoup.parse(html);
        assertTrue(dom.select("script,img").isEmpty());
        assertTrue(dom.text().contains("3건 → 7건 · 30% → 70% · +40%p"));
        assertTrue(dom.text().contains("0건 → 2건 · 0% → 20% · +20%p · 신규 집계"));
        assertTrue(dom.text().contains("사건의 최초 발생을 뜻하지 않습니다"));
        assertFalse(dom.text().contains("<unsafe>"));
        assertFalse(html.contains(longSummary));
        assertTrue(dom.text().contains("첨부 PDF"));
        var output = pdf.renderDetailed(report, view, null, null, null, null, changes);
        try (var document = Loader.loadPDF(output.bytes())) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s", "");
            assertTrue(text.contains("3건→7건·30%→70%·+40%p"));
            assertTrue(text.contains(longSummary.replaceAll("\\s", "")));
            assertTrue(text.contains("원문근거·공급계획은승인절차완료를조건으로합니다."));
            assertTrue(document.getDocumentCatalog().getNames().getDests().getNames().containsKey("changes-detail"));
            Path directory = Path.of("build/reports/notifications/m4");
            Files.createDirectories(directory);
            Files.write(directory.resolve("changes.pdf"), output.bytes());
            Files.writeString(directory.resolve("changes-email.html"), html);
            var raster = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++)
                ImageIO.write(raster.renderImageWithDPI(page, 110), "png", directory.resolve("changes-" + (page + 1) + ".png").toFile());
        }
    }

    @Test void unavailableStatesCarryNoZeroCountsOrEvidenceAndLeaveTelegramAlone() throws Exception {
        var missing = new ReportChanges(17, null, null, null, ReportChanges.Status.UNAVAILABLE,
                ReportChanges.Status.UNAVAILABLE.message, false, List.of(), List.of(),
                new ReportChanges.Periods("RUN", null, "2026-10-29 09:00 실행"),
                new ReportChanges.KeywordChanges(ReportChanges.KeywordStatus.UNAVAILABLE, "PERSONALIZATION_UNVERIFIED",
                        "이전 자료에 개인 수신 기준을 확인할 수 없어 비교를 제공하지 않습니다.", null, null, false, List.of()));
        String html = renderEmail(missing);
        assertTrue(html.contains("개인 수신 기준"));
        assertFalse(html.contains("0건"));
        assertFalse(html.contains("공급 계획"));
        try (var doc = Loader.loadPDF(pdf.renderDetailed(report, view, null, null, null, null, missing).bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertFalse(text.contains("0건"));
            assertFalse(text.contains("변화 비교 상세"));
        }
        var telegram = NotificationChannel.builder().channelType(ChannelType.TELEGRAM).maxLength(3500).build();
        assertEquals(email.render(report, telegram, view, null, null, Map.of(), null, null, null).chunks(),
                email.render(report, telegram, view, null, null, Map.of(), null, null, null, ready(List.of())).chunks());
    }

    @Test void comparisonTextIsEscapedAndUnsafeEvidenceLinksAreNotPublished() throws Exception {
        var unsafeSide = new ReportChanges.Side(4, 2, "<script>alert(1)</script>", "설명", List.of(new ReportChanges.Claim("c", "주장",
                List.of(new ReportChanges.Evidence(12, 21, 4, "링크", "javascript:alert(1)", 0, "근거")))));
        var changes = ready(List.of(new ReportChanges.Item("x", ReportChanges.Type.UPDATED, "<script>alert(1)</script>",
                "<b>저장 설명</b>", unsafeSide, unsafeSide)));
        assertTrue(Jsoup.parse(renderEmail(changes)).select("script").isEmpty());
        try (var doc = Loader.loadPDF(pdf.renderDetailed(report, view, null, null, null, null, changes).bytes())) {
            for (var page : doc.getPages()) for (var annotation : page.getAnnotations()) {
                if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link
                        && link.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI uri)
                    assertFalse(uri.getURI().startsWith("javascript:"));
            }
        }
    }

    private String renderEmail(ReportChanges changes) {
        return email.render(report, NotificationChannel.builder().channelType(ChannelType.EMAIL).maxLength(3500).build(),
                view, null, null, Map.of(), null, null, null, changes).chunks().getFirst();
    }
    private ReportChanges ready(List<ReportChanges.Item> items) {
        return new ReportChanges(17, LocalDate.parse("2026-10-19"), 16L, LocalDate.parse("2026-10-12"),
                ReportChanges.Status.READY, ReportChanges.Status.READY.message, false, List.of(), items,
                new ReportChanges.Periods("WEEKLY", "2026-10-12 ~ 2026-10-18", "2026-10-19 ~ 2026-10-25"),
                new ReportChanges.KeywordChanges(ReportChanges.KeywordStatus.AVAILABLE, null, "같은 수집 조건의 기간을 비교했습니다.",
                        10, 10, false, List.of(new ReportChanges.KeywordChange("HBM", 3, 7, 30.0, 70.0, 40.0),
                        new ReportChanges.KeywordChange("패키징", 0, 2, 0.0, 20.0, 20.0))));
    }
    private ReportChanges.Side side() {
        return new ReportChanges.Side(1, 2, "공급 계획 발표", "조건부 공급 계획을 발표했습니다.",
                List.of(new ReportChanges.Claim("claim", "공급 계획은 승인 절차 완료를 조건으로 합니다.",
                        List.of(new ReportChanges.Evidence(12, 21, 4, "공급 계획 원문", "https://example.com/news/supply",
                                0, "공급 계획은 승인 절차 완료를 조건으로 합니다.")))));
    }
}
