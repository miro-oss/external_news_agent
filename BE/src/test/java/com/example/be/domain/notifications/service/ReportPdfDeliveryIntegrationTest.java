package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.WatchItem;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.dart.model.DartModels;
import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.coverage.ReportCoverageDTO;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.keyword.ReportKeywordDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the complete offline document with the final delivery models for M1–M5 together. */
class ReportPdfDeliveryIntegrationTest {
    private static final OffsetDateTime CAPTURED = OffsetDateTime.parse("2026-11-02T09:00:00+09:00");
    private static final String ARTICLE_URL = "https://example.com/news/semiconductor?edition=2026&lang=ko";
    private static final String RECEIPT = "20261030001234";
    private static final String FILING_URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + RECEIPT;

    @Test
    void completeDeliveryPdfRetainsEverySectionFontAndResolvedOfflineDestination() throws Exception {
        var report = NewsReport.builder().id(363L).reportScope(ReportScope.WEEKLY)
                .title("주간 뉴스 브리핑 · 통합 형식 예시")
                .reportDate(LocalDate.of(2026, 10, 26)).reportEndDate(LocalDate.of(2026, 11, 1))
                .generatedAt(CAPTURED.toLocalDateTime()).markdownBody("숨긴 원본 회사와 기사").build();
        var content = new ReportContent(List.of("생산 투자 일정과 공급 계약의 후속 변화를 함께 확인했습니다."),
                List.of(new ReportContent.ImportantEvent("생산라인 투자 일정 구체화",
                                "가상 반도체 회사가 투자 계획과 반입 일정을 공개했습니다.",
                                "계획이 실제 계약으로 이어지는지 확인해야 합니다.", List.of(11L)),
                        new ReportContent.ImportantEvent("공급 계약의 후속 조건 확인",
                                "한글·English가 섞인 장문도 누락 없이 읽을 수 있어야 합니다.",
                                "세부 조건을 다음 공시와 함께 확인합니다.", List.of(12L))),
                List.of(new ReportContent.WatchItem("설비 반입 일정", "공급 계약 체결 여부를 확인합니다.", List.of(11L))),
                List.of("숨긴 수집 기록"));
        var findings = List.of(finding(11L, ARTICLE_URL), finding(12L, "javascript:alert(1)"));
        var view = new ReportEventFeedbackProjection.View(findings,
                new ReportReadingContent("", content), true, Set.of(13L));
        var coverage = new ReportCoverageDTO.View("report-coverage.v1", CAPTURED, "2026-10-26 ~ 2026-11-01 수집 기준",
                List.of(new ReportCoverageDTO.EventCoverage(List.of(11L), ReportCoverageDTO.Status.AVAILABLE, 7, 3, 1,
                                List.of(new ReportCoverageDTO.Article(111L, "가상 회사 투자 발표 원문", "예시 뉴스", CAPTURED, ARTICLE_URL))),
                        new ReportCoverageDTO.EventCoverage(List.of(12L), ReportCoverageDTO.Status.AVAILABLE, 2, 1, 0,
                                List.of(new ReportCoverageDTO.Article(112L, "주소를 확인할 수 없는 대표 보도", "예시 뉴스", CAPTURED, "file:///private/report")))),
                List.of());
        var keywords = new ReportKeywordDTO.View(ReportKeywordDTO.Status.AVAILABLE, null, "report-keywords.v1", CAPTURED,
                coverage.periodLabel(), 10, List.of(new ReportKeywordDTO.Item("HBM", 6, 60.0,
                        List.of(new ReportKeywordDTO.RelatedKeyword("설비 투자", 3))),
                        new ReportKeywordDTO.Item("설비 투자", 4, 40.0, List.of())), 0);
        var companies = List.of(company(), new ReportDartDTO.CompanyAttachment(
                new DartModels.Company("00000002", "자료 준비 중인 가상 회사", null), "PENDING", "준비 중", null));
        var dart = mock(ReportDartService.class);
        var result = new ReportPdfRenderer(dart).renderDetailed(report, view, insight(), coverage,
                null, keywords, changes(), companies);

        try (var document = Loader.loadPDF(result.bytes())) {
            String text = boundedText(document);
            for (String section : List.of("핵심 브리핑", "관점 한눈에", "지난 보고서와 달라진 점", "키워드 변화",
                    "키워드로 보는 이슈", "핵심 요약", "중요 이벤트", "관련 기사 7건", "3개 매체", "리포트 관점 분석",
                    "변화 비교 상세", "회사별 부록 바로가기", "회사 실적 · 공시", "공시 상세", "기사에서 직접 인용"))
                assertTrue(text.contains(section), section);
            for (String hidden : List.of("숨긴", "javascript:", "file:///", "알 수 없는 자유 헤드라인"))
                assertFalse(text.contains(hidden), hidden);
            assertTrue(text.contains("해석의 조건"));
            assertTrue(text.contains("발주가 확정되고 투자 집행이 유지되는 경우"));
            assertTrue(text.contains("자료 없음은 0이 아닙니다."));
            assertTrue(text.contains("산출값"));
            assertTrue(text.contains("신규 집계"));
            for (int line = 0; line < 65; line++) assertTrue(text.contains("장문 공시 조건 " + line + ":"), "filing line " + line);
            assertTrue(document.getNumberOfPages() >= 7);

            var destinations = document.getDocumentCatalog().getNames().getDests().getNames();
            assertEquals(Set.of("summary", "report-detail", "insight-detail", "changes-detail", "company-0", "company-1"), destinations.keySet());
            Set<String> visited = new HashSet<>();
            Set<String> external = new HashSet<>();
            for (var page : document.getPages()) {
                assertNotNull(page.getResources());
                for (var name : page.getResources().getFontNames()) {
                    var font = page.getResources().getFont(name);
                    assertTrue(font.isEmbedded(), font.getName());
                    assertNotNull(font.getFontDescriptor());
                }
                for (var annotation : page.getAnnotations()) {
                    assertInstanceOf(PDAnnotationLink.class, annotation);
                    var link = (PDAnnotationLink) annotation;
                    var bounds = link.getRectangle();
                    assertTrue(bounds.getLowerLeftX() >= 43 && bounds.getUpperRightX() <= page.getMediaBox().getWidth() - 43);
                    assertTrue(bounds.getLowerLeftY() >= 50 && bounds.getUpperRightY() <= page.getMediaBox().getHeight() - 50);
                    if (link.getAction() instanceof PDActionGoTo action) {
                        String name = ((PDNamedDestination) action.getDestination()).getNamedDestination();
                        assertTrue(destinations.containsKey(name));
                        int pageIndex = destinations.get(name).retrievePageNumber();
                        assertTrue(pageIndex >= 0 && pageIndex < document.getNumberOfPages());
                        visited.add(name);
                    } else {
                        var action = assertInstanceOf(PDActionURI.class, link.getAction());
                        assertTrue(ReportPdfLayout.safeUrl(action.getURI()));
                        external.add(action.getURI());
                    }
                }
            }
            assertEquals(destinations.keySet(), visited);
            assertEquals(Set.of(ARTICLE_URL, FILING_URL, "https://example.com/previous"), external);
            assertEquals(destinations.get("company-0").retrievePageNumber() + 1, result.companyPages().get("00000001"));
            assertEquals(destinations.get("company-1").retrievePageNumber() + 1, result.companyPages().get("00000002"));
            for (Integer pageNumber : result.companyPages().values()) assertTrue(text.contains("p. " + pageNumber));
            assertDestinationContains(document, "insight-detail", "리포트 관점 분석");
            assertDestinationContains(document, "changes-detail", "변화 비교 상세");
            assertDestinationContains(document, "company-0", "회사 실적 · 공시");
        }
        verifyNoInteractions(dart);
        Path sample = Path.of("build/reports/notifications/m6/combined-delivery.pdf");
        Files.createDirectories(sample.getParent());
        Files.write(sample, result.bytes());
    }

    @Test
    void insightDestinationFollowsTheOpeningBlockWhenItMovesToTheNextPage() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var pdf = new ReportPdfLayout(document)) {
            pdf.paragraph("앞 페이지 내용");
            pdf.internalLink("관점 분석 자세히 보기", "insight-detail");
            pdf.gap(490);
            ReportPdfInsightSection.write(pdf, insight(), new ReportEventFeedbackProjection.View(
                    List.of(finding(11L, ARTICLE_URL)), new ReportReadingContent("", null), false));
            bytes = pdf.save();
        }
        try (var document = Loader.loadPDF(bytes)) {
            assertTrue(document.getNumberOfPages() > 1);
            assertDestinationContains(document, "insight-detail", "리포트 관점 분석");
            var destination = document.getDocumentCatalog().getNames().getDests().getNames().get("insight-detail");
            assertEquals(1, destination.retrievePageNumber());
            var first = new PDFTextStripper();
            first.setStartPage(1);
            first.setEndPage(1);
            assertTrue(first.getText(document).contains("p. 2"));
            assertFalse(first.getText(document).contains("리포트 관점 분석"));
        }
    }

    @Test
    void unresolvedDestinationFailsBeforeReturningAttachmentBytes() throws Exception {
        try (var document = new PDDocument(); var pdf = new ReportPdfLayout(document)) {
            pdf.internalLink("기업 상세", "missing-company");
            assertThrows(IOException.class, pdf::save);
        }
    }

    @Test
    void decomposedKoreanAndUnsupportedGlyphsRemainReadableWithoutBreakingPagination() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var pdf = new ReportPdfLayout(document)) {
            pdf.title("한글 English 🧪");
            pdf.paragraph("한글-English ".repeat(650) + "마지막 문장");
            bytes = pdf.save();
        }
        try (var document = Loader.loadPDF(bytes)) {
            String text = boundedText(document);
            assertTrue(text.contains("한글 English [U+1F9EA]"));
            assertTrue(text.contains("마지막 문장"));
            assertTrue(document.getNumberOfPages() > 1);
        }
    }

    private static ReportInsightDTO.AudienceInsight insight() {
        return new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "알 수 없는 자유 헤드라인", "high",
                List.of(new Overview("투자 계획은 공급사의 장비 수요로 이어질 수 있습니다.", List.of("11:0"),
                                "발주가 확정되고 투자 집행이 유지되는 경우"),
                        new Overview("숨긴 기업 전망", List.of("13:0"), "숨긴 조건")), List.of(),
                List.of(NotificationInsightTestSupport.fact(11L), NotificationInsightTestSupport.fact(13L)), List.of(),
                List.of(new WatchItem("투자 계획의 실행", "계약 금액과 반입 시점", "계약이 체결되고 일정이 확정되는지 확인", List.of("11:0"))),
                "test", "test", CAPTURED);
    }

    private static ReportChanges changes() {
        var previous = new ReportChanges.Side(1, 1, "이전 투자 계획", "계획의 세부 일정을 검토 중이었습니다.",
                List.of(new ReportChanges.Claim("old:0", "투자 일정을 검토 중이었다.", List.of(new ReportChanges.Evidence(
                        10, 110, 1, "이전 발표 원문", "https://example.com/previous", 0, "세부 일정은 미정이다.")))));
        var current = new ReportChanges.Side(1, 1, "이번 투자 일정", "반입 일정이 구체화됐습니다.",
                List.of(new ReportChanges.Claim("11:0", "설비 반입 일정이 공개됐다.", List.of(new ReportChanges.Evidence(
                        11, 111, 2, "이번 발표 원문", ARTICLE_URL, 0, "내년 상반기 반입을 계획한다.")))));
        return new ReportChanges(363, LocalDate.of(2026, 10, 26), 362L, LocalDate.of(2026, 10, 19),
                ReportChanges.Status.READY, ReportChanges.Status.READY.message, false, List.of(),
                List.of(new ReportChanges.Item("issue-1", ReportChanges.Type.UPDATED, "설비 반입 일정 구체화",
                        "반입 시점을 이전 계획보다 구체적으로 공개했습니다.", previous, current)),
                new ReportChanges.Periods("WEEKLY", "2026-10-19 ~ 2026-10-25", "2026-10-26 ~ 2026-11-01"),
                new ReportChanges.KeywordChanges(ReportChanges.KeywordStatus.AVAILABLE, null, "같은 주제의 키워드 비중을 비교합니다.",
                        10, 10, false, List.of(new ReportChanges.KeywordChange("HBM", 3, 6, 30.0, 60.0, 30.0),
                        new ReportChanges.KeywordChange("설비 투자", 0, 4, 0.0, 40.0, 40.0))));
    }

    private static ReportDartDTO.CompanyAttachment company() {
        String longTitle = IntStream.range(0, 65).mapToObj(i -> "장문 공시 조건 " + i + ": 계약 조건과 정정 사항 확인")
                .collect(java.util.stream.Collectors.joining("\n"));
        var filing = new DartModels.Filing(RECEIPT, "[정정] 시설투자 계획\n" + longTitle, "2026-10-30", FILING_URL, true);
        var quarters = IntStream.range(0, 12).mapToObj(i -> new DartModels.Quarter(2023 + (i + 3) / 4, (i + 3) % 4 + 1,
                i == 4 ? null : String.valueOf(5_000_000_000L + i * 100_000_000L),
                i == 4 ? null : String.valueOf((i - 2) * 100_000_000L), i == 4 ? null : BigDecimal.valueOf(i - 2),
                (i + 3) % 4 == 3, List.of(filing))).toList();
        return new ReportDartDTO.CompanyAttachment(new DartModels.Company("00000001", "가상 반도체 회사", "000001"),
                "PARTIAL", "일부 분기 미확인", new DartModels.FinancialData("CFS", "KRW", CAPTURED, quarters,
                List.of(filing), List.of("같은 회계 기준을 확인할 수 없는 분기는 비워 두었습니다.")),
                List.of(new ReportDartDTO.IssueLink(11, 111, 1L, "가상 반도체 회사", "투자를 발표했다.", ARTICLE_URL, List.of(RECEIPT))),
                List.of(new ReportDartDTO.FilingRow(filing, List.of("RECENT", "FINANCIAL_SOURCE"), "DIRECT_REFERENCE", List.of(11L))));
    }

    private static Finding finding(long id, String url) {
        return Finding.builder().id(id).article(Article.builder().id(100 + id).canonicalUrl(url)
                .title("가상 기사 " + id).sourceName("예시 뉴스").publishedAt(CAPTURED).build()).build();
    }

    private static void assertDestinationContains(PDDocument document, String name, String heading) throws Exception {
        int page = document.getDocumentCatalog().getNames().getDests().getNames().get(name).retrievePageNumber() + 1;
        var stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        assertTrue(stripper.getText(document).contains(heading), name + " must resolve to its actual heading");
    }

    private static String boundedText(PDDocument document) throws Exception {
        return new PDFTextStripper() {
            @Override protected void processTextPosition(TextPosition text) {
                assertTrue(text.getXDirAdj() >= 40 && text.getXDirAdj() + text.getWidthDirAdj() <= 554,
                        "text crossed horizontal margin: " + text.getUnicode());
                assertTrue(text.getYDirAdj() > 20 && text.getYDirAdj() < 820,
                        "text crossed vertical margin: " + text.getUnicode());
                super.processTextPosition(text);
            }
        }.getText(document);
    }
}
