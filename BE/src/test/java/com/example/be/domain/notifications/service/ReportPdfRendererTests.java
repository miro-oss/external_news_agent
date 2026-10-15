package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.dart.model.DartModels;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportPdfRendererTests {
    private static final String RECEIPT = "20260814001234";
    private static final String FILING_URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + RECEIPT;
    private final ReportDartService dart = mock(ReportDartService.class);
    private final ReportPdfRenderer renderer = new ReportPdfRenderer(dart);

    @Test
    void includesEveryBodyItemAndOnlyFinalRecipientVisibleContent() throws Exception {
        var report = report(ReportScope.RUN);
        var findings = IntStream.rangeClosed(1, 10).mapToObj(i -> finding(i, "기사 " + i, "기사 분석 " + i)).toList();
        var events = IntStream.rangeClosed(1, 7).mapToObj(i -> new ReportContent.ImportantEvent(
                "중요 사건 " + i, "사건 전체 내용 " + i, "주목할 이유 " + i, List.of((long) i))).toList();
        var content = new ReportContent(List.of("첫 번째 전체 요약", "두 번째 전체 요약", "세 번째 전체 요약", "네 번째 전체 요약"),
                events, List.of(new ReportContent.WatchItem("후속 관찰", "후속 확인할 조건", List.of(8L))),
                List.of("절대 표시하지 않을 수집 상태"));
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of()));

        byte[] bytes = renderer.render(report, view(content, findings.subList(0, 9), Set.of()), null);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("핵심 요약", "네 번째 전체 요약", "중요 이벤트", "중요 사건 7", "사건 전체 내용 7",
                    "주목할 이유 7", "관찰 항목", "후속 확인할 조건", "기타 분석", "기사 분석 9")) assertTrue(text.contains(expected), expected);
            for (String absent : List.of("수집 상태", "절대 표시하지", "기사 분석 10", "원본 보고서에만 남은 비공개 내용", "리포트 관점 분석"))
                assertFalse(text.contains(absent), absent);
            assertTrue(urls(document).contains("https://example.com/article-9"));
            for (long id : List.of(1L, 8L, 9L)) assertTrue(text.contains("https://example.com/article-" + id));
            assertFalse(text.contains("출처 ·"));
            assertFalse(urls(document).contains("https://example.com/article-10"));
            for (var page : document.getPages()) for (var name : page.getResources().getFontNames())
                assertTrue(page.getResources().getFont(name).isEmbedded(), "Korean fonts must travel with the attachment");
        }
    }

    @Test
    void rejectedSharedEvidenceNeverReturnsAsOtherAnalysis() throws Exception {
        var report = report(ReportScope.DAILY);
        var content = new ReportContent(List.of(), List.of(), List.of(), List.of());
        String text = text(renderer.render(report, view(content,
                List.of(finding(1, "제외할 기사", "제외할 분석"), finding(2, "유효한 기사", "유효한 분석")), Set.of(1L)), null));
        assertFalse(text.contains("제외할"));
        assertTrue(text.contains("유효한 분석"));
        verifyNoInteractions(dart);
    }

    @Test
    void longKoreanProseAndLongTableRowsContinueWithoutClippingOrDiscardingContent() throws Exception {
        var report = report(ReportScope.WEEKLY);
        String body = IntStream.rangeClosed(1, 90).mapToObj(i -> "완전한 문장 " + i + ": 반도체 장비 공급 일정과 고객사의 투자 계획을 확인해야 합니다.")
                .collect(java.util.stream.Collectors.joining("\n"));
        var content = new ReportContent(List.of("장문 검증"), List.of(new ReportContent.ImportantEvent("긴 이벤트", body,
                "마지막 중요성도 보존합니다", List.of(1L))), List.of(), List.of());
        String longUrl = "https://example.com/news/" + "long-article-address-".repeat(15) + "?edition=2026";
        var linkedFinding = Finding.builder().id(1L).article(Article.builder().id(101L).title("기사")
                .canonicalUrl(longUrl).build()).build();
        try (PDDocument document = Loader.loadPDF(renderer.render(report, view(content, List.of(linkedFinding), Set.of()), null))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(document.getNumberOfPages() >= 3);
            for (int i = 1; i <= 90; i++) assertTrue(text.contains("완전한 문장 " + i + ":"), "missing prose " + i);
            assertTrue(text.contains("마지막 중요성도 보존합니다"));
            assertTrue(text.replaceAll("\\s", "").contains(longUrl));
            assertTrue(urls(document).contains(longUrl));
            assertWithinPageBounds(document);
        }
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ReportPdfLayout pdf = new ReportPdfLayout(document)) {
            String cell = IntStream.rangeClosed(1, 130).mapToObj(i -> "표의 근거 항목 " + i).collect(java.util.stream.Collectors.joining("\n"));
            pdf.table(List.of("반복 제목", "설명"), List.of(List.of(cell, "연결된 조건")));
            pdf.paragraph("표 이후 문장");
            bytes = pdf.save();
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("표의 근거 항목 130"));
            assertTrue(text.contains("표 이후 문장"));
            assertTrue(text.split("반복 제목", -1).length >= 3);
            assertWithinPageBounds(document);
        }
    }

    @Test
    void allCompaniesHavePrintedExactFinancialTablesWithoutFilingColumnsOrLists() throws Exception {
        var report = report(ReportScope.RUN);
        var filing = filing("반기보고서", FILING_URL, true);
        var financials = new DartModels.FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-14T16:30:00Z"),
                List.of(quarter(2026, 1, "10000000005000001.20", "-1", null, List.of(filing)),
                        quarter(2026, 2, null, "0", BigDecimal.ZERO, List.of()),
                        quarter(2026, 4, "123456789012", "-1234567890", new BigDecimal("-1.00"), List.of(filing))),
                List.of(filing, filing("최근 주요사항보고서", FILING_URL, false)), List.of("동일한 재무 기준으로 비교합니다."));
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L,
                IntStream.rangeClosed(1, 5).mapToObj(i -> company("연결 회사 " + i, "PARTIAL", financials)).toList()));

        try (PDDocument document = Loader.loadPDF(renderer.render(report, view(emptyContent(), List.of(), Set.of()), null))) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("연결 회사 5", "2026년 1분기", "매출액", "영업이익", "영업이익률",
                    "자료 없음", "-1", "0%", "산출값", "2026-10-15 01:30",
                    "동일한 재무 기준으로 비교합니다", "차트 단위: 억원", "표 금액 단위: 원 (KRW)")) assertTrue(text.contains(expected), expected);
            assertTrue(text.replaceAll("\\s", "").contains("10,000,000,005,000,001.20"));
            for (String hidden : List.of("근거 공시", "실적 근거 공시", "최근 공시", "최근 주요사항보고서", "반기보고서", "[1]"))
                assertFalse(text.contains(hidden), hidden);
            assertTrue(urls(document).isEmpty());
            assertWithinPageBounds(document);
        }
    }

    @Test
    void rejectsUnsafeLinksAndNonReadyFinancialPayloadsWithoutReadingRemoteResources() throws Exception {
        var report = report(ReportScope.RUN);
        var payload = new DartModels.FinancialData("OFS", "USD", null, List.of(),
                List.of(filing("안전하지 않은 공시 제목", "https://evil.example/doc", false)), List.of());
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of(
                company("비공개 대기 회사", "PENDING", new DartModels.FinancialData("CFS", "KRW", null,
                        List.of(), List.of(filing("대기 자료 유출", FILING_URL, false)), List.of())), company("공시 회사", "READY", payload))));
        var finding = Finding.builder().id(1L).article(Article.builder().id(101L).title("외부 출처 제목")
                .canonicalUrl("javascript:alert(1)").build()).summary("근거 분석").keyPoints(List.of(new FindingKeyPoint("근거", List.of(0), "grounded"))).build();
        try (PDDocument document = Loader.loadPDF(renderer.render(report, view(emptyContent(), List.of(finding), Set.of()), null))) {
            String text = new PDFTextStripper().getText(document);
            assertFalse(text.contains("안전하지 않은 공시 제목"));
            assertTrue(text.contains("외부 출처 제목"));
            assertFalse(text.contains("대기 자료 유출"));
            assertFalse(text.contains("javascript:"));
            assertTrue(urls(document).isEmpty());
        }
    }

    @Test
    void legacySectionsRemainCompleteWhileCollectionNotesAndTheirSubsectionsAreOmitted() throws Exception {
        var report = report(ReportScope.DAILY);
        var reading = new ReportReadingContent("# 중복 제목\n\n## 핵심 요약\n- 저장된 요약\n\n## 중요 이벤트\n### 계약 체결\n"
                + "전체 계약 내용\n\n## 수집 및 출처 참고\n숨겨야 할 참고\n### 내부 상태\n숨겨야 할 하위 내용\n"
                + "## 기타 분석\n### 다른 분석\n마지막 분석 내용\n[기사](https://example.com/legacy)\n- 원문: <https://example.com/filtered-legacy>\n", null);
        try (PDDocument document = Loader.loadPDF(renderer.render(report,
                new ReportEventFeedbackProjection.View(List.of(), reading, false), null))) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("저장된 요약", "전체 계약 내용", "기타 분석", "마지막 분석 내용")) assertTrue(text.contains(expected));
            assertFalse(text.contains("숨겨야"));
            assertFalse(text.contains("수집 및 출처 참고"));
            assertFalse(text.contains("중복 제목"));
            assertTrue(urls(document).contains("https://example.com/legacy"));
            assertTrue(urls(document).contains("https://example.com/filtered-legacy"));
            assertTrue(text.contains("https://example.com/filtered-legacy"));
            assertTrue(text.contains("https://example.com/legacy"));
        }
    }

    @Test
    void monetaryTableKeepsOriginalDecimalPrecisionAndMissingDistinctFromZero() {
        assertEquals("10,000,000,000,000,001.20", ReportPdfRenderer.amount("10000000000000001.20"));
        assertEquals("-0.01", ReportPdfRenderer.amount("-0.01"));
        assertEquals("0", ReportPdfRenderer.amount("0"));
        assertEquals("자료 없음", ReportPdfRenderer.amount(null));
        assertEquals("자료 없음", ReportPdfRenderer.amount("NaN"));
    }

    @Test
    void exportsComprehensiveSampleForVisualReview() throws Exception {
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).title("PDF 형식 예시 · 가상 데이터")
                .generatedAt(LocalDateTime.of(2026, 10, 15, 9, 0)).markdownBody("").build();
        var content = new ReportContent(List.of("반도체 투자 계획과 신규 생산라인의 장비 발주 일정을 함께 점검했습니다.",
                "이번 보고서에서는 확인된 사실과 관점별 조건부 해석을 구분하여 제공합니다."),
                List.of(new ReportContent.ImportantEvent("차세대 공정 투자 계획 구체화", "제조사는 신규 공정 전환과 생산 능력 확대를 위한 투자 계획을 공개했습니다. 장비 반입과 양산 준비 일정이 후속 확인 대상입니다.",
                        "발주 규모와 인도 시점을 함께 확인해야 장비 업체 실적 반영 시기를 판단할 수 있습니다.", List.of(1L))),
                List.of(new ReportContent.WatchItem("설비 발주 일정", "공식 공시와 공급계약에서 투자 집행 여부를 확인합니다.", List.of(2L))), List.of("수집 상태는 출력하지 않습니다."));
        var findings = List.of(finding(1, "신규 생산라인 투자 계획 발표", "회사가 생산라인 투자 계획을 발표했습니다."),
                finding(2, "주요 공급사 계약 현황", "공급사의 계약 일정과 장비 반입 일정을 확인했습니다."),
                finding(3, "시장 수요와 재고 동향", "주요 제품의 수요와 재고 수준은 다음 분기 생산 계획을 판단하는 참고 지표입니다."));
        var filing = filing("반기보고서 (2026.06)", FILING_URL, false);
        var quarters = IntStream.range(0, 12).mapToObj(i -> quarter(2023 + (i + 2) / 4, (i + 2) % 4 + 1,
                i == 5 ? null : String.valueOf(5_000_000_000L + i * 300_000_000L),
                i == 5 ? null : String.valueOf((i - 2) * 140_000_000L), i == 5 ? null : new BigDecimal("8.35"), List.of(filing))).toList();
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of(company("예시 반도체", "PARTIAL",
                new DartModels.FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-15T09:00:00+09:00"),
                        quarters, List.of(filing, filing("주요사항보고서 (시설투자)", FILING_URL, true)),
                        List.of("2024년 4분기는 비교 가능한 금액이 없어 비워 두었습니다."))))));
        var fact = new ReportInsightDTO.Fact("1:0", "회사는 생산라인 투자를 계획하고 있다고 발표했다.", "FORECAST", "회사 발표", 1L, 101L, List.of(0), "grounded");
        var insight = new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "장비 수요의 집행 시점 점검", "high",
                List.of(new AgentReportInsightResponse.Overview("투자 계획은 장비 공급사의 수요 증가로 이어질 수 있습니다.", List.of("1:0"), "투자 계획이 실제 발주로 전환되는 경우")),
                List.of(new ReportInsightDTO.Issue(1L, "high", "생산라인 증설은 장비 반입과 설치 수요에 직접 연결됩니다.", List.of("1:0"), new AgentReportInsightResponse.Axes(3, 2, null, null), 1)),
                List.of(fact), List.of(new AgentReportInsightResponse.Implication("발주 시점에 따라 실적 반영 기간이 달라질 수 있습니다.", "설비 발주 후 제조·인도·검수를 거쳐 매출이 인식됩니다.", List.of("1:0"), "계약과 인도 일정이 확정되는 경우", "투자 집행이 연기되거나 계약 규모가 축소되는 경우")),
                List.of(new AgentReportInsightResponse.WatchItem("장비 발주", "공급계약과 수주 잔고", "계약 체결 및 인도 일정 확인", List.of("1:0"))), "test", "test", OffsetDateTime.parse("2026-10-15T09:00:00+09:00"));
        byte[] bytes = renderer.render(report, view(content, findings, Set.of()), insight);
        Path path = Path.of("build/reports/pdf/report-attachment-sample.pdf");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        try (PDDocument document = Loader.loadPDF(bytes)) { assertWithinPageBounds(document); }
    }

    private static void assertWithinPageBounds(PDDocument document) throws Exception {
        new PDFTextStripper() {
            @Override protected void processTextPosition(TextPosition text) {
                assertTrue(text.getXDirAdj() >= 40, "text crossed left margin: " + text.getUnicode());
                assertTrue(text.getXDirAdj() + text.getWidthDirAdj() <= 553, "text crossed right margin: " + text.getUnicode());
                assertTrue(text.getYDirAdj() > 20 && text.getYDirAdj() < 820, "text outside page: " + text.getUnicode());
                super.processTextPosition(text);
            }
        }.getText(document);
    }

    private static List<String> urls(PDDocument document) throws Exception {
        List<String> urls = new ArrayList<>();
        for (var page : document.getPages()) for (var annotation : page.getAnnotations())
            if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI action) urls.add(action.getURI());
        return urls;
    }

    private static String text(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(document); }
    }

    private static NewsReport report(ReportScope scope) {
        return NewsReport.builder().id(17L).reportScope(scope).title("반도체 산업 동향 · 상세 뉴스 리포트")
                .generatedAt(LocalDateTime.of(2026, 10, 15, 9, 0)).markdownBody("원본 보고서에만 남은 비공개 내용").build();
    }

    private static Finding finding(long id, String title, String summary) {
        return Finding.builder().id(id).article(Article.builder().id(100 + id).title(title).canonicalUrl("https://example.com/article-" + id)
                .sourceName("산업 뉴스").publishedAt(OffsetDateTime.parse("2026-10-15T08:00:00+09:00")).build())
                .summary(summary).keyPoints(List.of(new FindingKeyPoint("기사의 확인된 근거", List.of(0), "grounded"))).build();
    }

    private static ReportContent emptyContent() { return new ReportContent(List.of(), List.of(), List.of(), List.of()); }
    private static ReportEventFeedbackProjection.View view(ReportContent content, List<Finding> findings, Set<Long> excluded) {
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", content), !excluded.isEmpty(), excluded);
    }
    private static DartModels.Filing filing(String name, String url, boolean corrected) {
        return new DartModels.Filing(RECEIPT, name, "2026-08-14", url, corrected);
    }
    private static DartModels.Quarter quarter(int year, int quarter, String revenue, String profit, BigDecimal margin, List<DartModels.Filing> sources) {
        return new DartModels.Quarter(year, quarter, revenue, profit, margin, quarter == 4, sources);
    }
    private static ReportDartDTO.CompanyAttachment company(String name, String status, DartModels.FinancialData data) {
        return new ReportDartDTO.CompanyAttachment(new DartModels.Company("00126380", name, "005930"), status, "저장된 자료", data);
    }
}
