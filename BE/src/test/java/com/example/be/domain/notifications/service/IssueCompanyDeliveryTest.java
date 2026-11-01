package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.dart.model.DartModels.*;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.dart.ReportIssueCompanySnapshot;
import com.example.be.domain.reports.dart.StoredReportDartFixture;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class IssueCompanyDeliveryTest {
    @Test
    void offlineIssueLinksResolveToCompanyChartAndFilingTableAndEmailUsesSamePage() throws Exception {
        var report = StoredReportDartFixture.topicWeekly();
        var company = StoredReportDartFixture.COMPANY;
        org.springframework.test.util.ReflectionTestUtils.setField(report, "reportIssueCompanySnapshot", new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION,
                OffsetDateTime.parse("2026-10-30T09:00:00+09:00"), List.of(new ReportIssueCompanySnapshot.Link(
                company, 51L, 101L, 501L, company.corpName(), "SK하이닉스가 반기 실적을 발표했다.",
                "https://example.com/news/101", List.of("20260814001234")))));
        var filings = List.of(
                new Filing("20260814001234", "[기재정정] 반기보고서 · 가상 검증 자료", "2026-08-14", StoredReportDartFixture.FILING_URL, true),
                new Filing("20260515001234", "사업보고서 · 가상 검증 자료", "2026-05-15", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260515001234", false));
        var quarters = IntStream.range(0, 12).mapToObj(i -> new Quarter(2023 + (i + 2) / 4, (i + 2) % 4 + 1,
                String.valueOf(10000000000L + i * 1000000000L), i == 4 ? null : String.valueOf(1000000000L + i * 100000000L),
                i == 4 ? null : BigDecimal.TEN, (i + 2) % 4 == 3, List.of(filings.get(1)))).toList();
        var stored = new StoredReportDartFixture();
        stored.stored(report, "PARTIAL", new FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-30T09:30:00+09:00"),
                quarters, filings, List.of("일부 분기 영업이익은 확인할 수 없어 비워 두었습니다.")));
        var finding = Finding.builder().id(51L).article(Article.builder().id(101L).canonicalUrl("https://example.com/news/101").build()).build();
        var content = new ReportContent(List.of("기업 공시를 함께 확인할 수 있는 가상 보고서입니다."), List.of(
                new ReportContent.ImportantEvent("SK하이닉스 반기 실적 발표", "SK하이닉스가 반기 실적을 발표했다.",
                        "저장된 실적과 공시 원문을 함께 확인합니다.", List.of(51L))), List.of(), List.of());
        var view = new ReportEventFeedbackProjection.View(List.of(finding), new ReportReadingContent("", content), false);
        var summary = new NotificationDartSummary(stored.service());
        var companies = summary.project(report, view);
        var pdf = new ReportPdfRenderer(stored.service()).renderDetailed(report, view, null, null, null, null, null, companies);
        int companyPage = pdf.companyPages().get(company.corpCode());
        try (var document = Loader.loadPDF(pdf.bytes())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("공시 상세"));
            assertTrue(text.contains("기사에서 직접 인용"));
            assertTrue(text.contains("회사 참고"));
            assertTrue(text.replaceAll("\\s", "").contains("실적근거"));
            assertTrue(text.contains("자료 없음"));
            assertTrue(text.contains("접수 20260814001234"));
            var dests = document.getDocumentCatalog().getNames().getDests().getNames();
            assertEquals(companyPage - 1, dests.get("company-0").retrievePageNumber());
            int issueLinks = 0;
            int filingLinks = 0;
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                for (var annotation : document.getPage(index).getAnnotations()) {
                    if (!(annotation instanceof PDAnnotationLink link)) continue;
                    assertTrue(link.getRectangle().getLowerLeftY() >= 50);
                    if (link.getAction() instanceof PDActionGoTo action) {
                        String name = ((PDNamedDestination) action.getDestination()).getNamedDestination();
                        assertNotNull(dests.get(name));
                        if ("company-0".equals(name) && index < companyPage - 1) issueLinks++;
                    }
                    if (link.getAction() instanceof PDActionURI action && action.getURI().startsWith("https://dart.fss.or.kr/")) filingLinks++;
                }
            }
            assertTrue(issueLinks >= 3); // briefing, issue detail, company index
            assertEquals(2, filingLinks); // receipts deduplicate recent + financial sources
        }
        var renderer = new NotificationRenderer(null, null, null, summary);
        String email = renderer.render(report, NotificationChannel.builder().channelType(ChannelType.EMAIL).build(), view,
                null, null, pdf.companyPages(), null, null, null, null, companies).chunks().getFirst();
        String emailText = Jsoup.parse(email).text();
        assertTrue(emailText.contains("확인된 기업 · SK하이닉스 · 첨부 PDF p. " + companyPage));
        assertTrue(emailText.contains("기사에서 직접 인용한 공시 1건"));
        assertFalse(email.contains("localhost"));
        stored.verifyStoredReads(report.getId(), 1);
        Path dir = Path.of("build/reports/notifications/m5");
        Files.createDirectories(dir);
        Files.write(dir.resolve("issue-company.pdf"), pdf.bytes());
        Files.writeString(dir.resolve("issue-company.html"), email);
    }

    @Test
    void eventWithoutMatchingEvidenceDoesNotGetAnUnrelatedCompanyLabel() {
        var company = new com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment(
                StoredReportDartFixture.COMPANY, "READY", "", StoredReportDartFixture.financials(),
                List.of(new com.example.be.domain.reports.dto.res.ReportDartDTO.IssueLink(51L, 101L, 501L,
                        "SK하이닉스", "확인된 원문", "https://example.com/news/101", List.of())), List.of());
        var html = new StringBuilder();
        NotificationCompanySection.email(html, List.of(52L), List.of(company), Map.of(company.company().corpCode(), 4));
        assertTrue(html.isEmpty());
    }
}
