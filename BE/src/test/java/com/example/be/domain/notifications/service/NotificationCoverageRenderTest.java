package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.coverage.ReportCoverageDTO;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationCoverageRenderTest {
    @Test
    void emailAndPdfUseTheSameCountsAndSeparateRepresentativeCoverageFromDirectEvidence() throws Exception {
        var finding = Finding.builder().id(1L).article(Article.builder().id(11L).title("직접 근거")
                .canonicalUrl("https://example.com/direct").build()).build();
        var report = NewsReport.builder().id(17L).title("가상 검증 보고서").reportScope(ReportScope.RUN).build();
        var content = new ReportContent(List.of("핵심 요약 문장"),
                List.of(new ReportContent.ImportantEvent("이슈 제목", "이슈 내용", "", List.of(1L))), List.of(), List.of());
        var view = new ReportEventFeedbackProjection.View(List.of(finding), new ReportReadingContent("", content), false);
        var event = new ReportCoverageDTO.EventCoverage(List.of(1L), ReportCoverageDTO.Status.AVAILABLE, 8, 3, 2,
                List.of(new ReportCoverageDTO.Article(12L, "별도 대표 기사 <script>", "가상 매체",
                        OffsetDateTime.parse("2026-10-28T23:30:00Z"), "https://example.com/representative")));
        var coverage = new ReportCoverageDTO.View("test.v1", null, "해당 실행 수집 기준", List.of(event), List.of());
        var renderer = new NotificationRenderer(mock(FindingRepository.class), mock(TopicRelevancePolicy.class),
                mock(ReportEventFeedbackProjection.class), mock(NotificationDartSummary.class));
        var email = NotificationChannel.builder().channelType(ChannelType.EMAIL).maxLength(100000).build();
        String html = renderer.render(report, email, view, null, null, Map.of(), coverage).chunks().getFirst();
        String pdf;
        var pdfResult = new ReportPdfRenderer(mock(ReportDartService.class)).renderDetailed(report, view, null, coverage);
        try (var document = Loader.loadPDF(pdfResult.bytes())) { pdf = new PDFTextStripper().getText(document); }
        for (String output : List.of(org.jsoup.Jsoup.parse(html).text(), pdf)) {
            for (String text : List.of("관련 기사 8건 · 3개 매체 · 매체 미확인 2건", "대표 보도", "요약의 직접 근거",
                    "가상 매체", "2026-10-29", "해당 실행 수집 기준")) assertTrue(output.contains(text), text);
        }
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        var anchors = org.jsoup.Jsoup.parse(html).select("a").eachAttr("href");
        assertTrue(anchors.containsAll(List.of("https://example.com/representative", "https://example.com/direct")));
        var output = java.nio.file.Path.of("build/reports/notifications/m1-coverage-email.html");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output, html);
        var pdfOutput = java.nio.file.Path.of("build/reports/pdf/m1-coverage-sample.pdf");
        java.nio.file.Files.createDirectories(pdfOutput.getParent());
        java.nio.file.Files.write(pdfOutput, pdfResult.bytes());
    }

    @Test
    void unavailableAndVerifiedEmptyCoverageHaveDifferentLabels() {
        assertEquals("집계 자료 없음", NotificationCoverage.label(ReportCoverageDTO.unavailable(List.of(1L))));
        assertEquals("관련 기사 0건 · 0개 매체", NotificationCoverage.label(new ReportCoverageDTO.EventCoverage(
                List.of(1L), ReportCoverageDTO.Status.AVAILABLE, 0, 0, 0, List.of())));
    }
}
