package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.dart.model.DartModels;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dart.StoredReportDartFixture;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    void includesFullEventsAndWatchesWithoutDuplicateExecutiveSummary() throws Exception {
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
            for (String expected : List.of("핵심 브리핑", "중요 이벤트", "중요 사건 7", "사건 전체 내용 7",
                    "관찰 항목", "후속 확인할 조건")) assertTrue(text.contains(expected), expected);
            for (String absent : List.of("핵심 요약", "네 번째 전체 요약", "기타 분석", "기사 분석 9", "수집 상태", "절대 표시하지", "기사 분석 10", "원본 보고서에만 남은 비공개 내용", "리포트 관점 분석"))
                assertFalse(text.contains(absent), absent);
            assertFalse(urls(document).contains("https://example.com/article-9"));
            assertFalse(text.contains("https://example.com/article-9"));
            for (long id : List.of(1L, 8L)) assertTrue(text.contains("https://example.com/article-" + id));
            assertFalse(text.contains("출처 ·"));
            assertFalse(urls(document).contains("https://example.com/article-10"));
            for (var page : document.getPages()) for (var name : page.getResources().getFontNames())
                assertTrue(page.getResources().getFont(name).isEmbedded(), "Korean fonts must travel with the attachment");
        }
    }

    @Test
    void weeklyPdfKeepsOnlyEventSummaryAndSourceWithoutDatedAnalysis() throws Exception {
        String summary = "아마존이 데카르트를 약 70억달러(약 9조4000억원)에 인수하는 방안을 논의하고 있다.";
        String chronology = "2026-10-12 주제별 분석 결과: " + summary
                + "\n2026-10-12 주제별 분석 결과: [전망] 소식통들은 조만간 거래가 성사될 수 있다고 말했다."
                + "\n2026-10-12 주제별 분석 결과: [근거 제한: 스타트업이라는 표현은 명시되지 않음.] 데카르트는 소프트웨어 기업이다."
                + "\n2026-10-13 주제별 분석 결과: " + summary;
        var content = new ReportContent(List.of("이번 주 주요 인수 논의"),
                List.of(new ReportContent.ImportantEvent("아마존의 데카르트 인수 논의", summary, chronology, List.of(1L))),
                List.of(), List.of());
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.WEEKLY)
                .title("주간 PDF 형식 검증 · 사용자 제공 문구")
                .reportDate(java.time.LocalDate.of(2026, 10, 12)).reportEndDate(java.time.LocalDate.of(2026, 10, 18)).build();
        byte[] bytes = renderer.render(report, view(content,
                List.of(finding(1, "아마존의 데카르트 인수 논의", summary)), Set.of()), null);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.replaceAll("\\s", "").contains(summary.replaceAll("\\s", "")), text);
            assertEquals(1, text.split("논의하고 있다\\.", -1).length - 1, text); // Prose appears once in event detail; the briefing contains only its title.
            for (String omitted : List.of("주제별 분석 결과", "[전망]", "소식통", "근거 제한", "스타트업", "먼저 살펴볼 이슈"))
                assertFalse(text.contains(omitted), omitted);
            assertTrue(urls(document).contains("https://example.com/article-1"));
            assertWithinPageBounds(document);
        }
        assertEquals(chronology, content.importantEvents().getFirst().significance());
        Path path = Path.of("build/reports/pdf/weekly-summary-sample.pdf");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        verify(dart).getStored(report);
        verifyNoMoreInteractions(dart);
    }

    @ParameterizedTest
    @ValueSource(strings = {"주제별 분석 결과", "일일 보고서"})
    void legacyWeeklyPdfOmitsChronologyAndPriorityIssuesButKeepsSummaryAndLinks(String sourceLabel) throws Exception {
        String markdown = "# 주간 보고서\n\n## 주요 이슈와 주간 흐름\n\n### 인수 논의\n\n"
                + "인수하는 방안을 논의하고 있다. 2026-10-12 " + sourceLabel + ": 반복된 인수 논의.\n"
                + "[같은 문단의 원문](https://example.com/article-2)\n\n"
                + "- 2026-10-13 " + sourceLabel + ": [전망] 제거할 추가 설명. <https://example.com/article-3>\n\n"
                + "[기사 원문](https://example.com/article-1)\n\n"
                + "## 먼저 살펴볼 이슈\n\n### 제거할 이슈\n\n제거할 이슈 설명\n\n"
                + "## 다음 확인 항목\n\n공식 발표를 확인합니다.\n";
        var view = new ReportEventFeedbackProjection.View(List.of(), new ReportReadingContent(markdown, null), false, Set.of());
        try (PDDocument document = Loader.loadPDF(renderer.render(report(ReportScope.WEEKLY), view, null))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("인수하는 방안을 논의하고 있다."), text);
            assertTrue(text.contains("공식 발표를 확인합니다."), text);
            for (String omitted : List.of(sourceLabel, "반복된", "제거할", "먼저 살펴볼 이슈"))
                assertFalse(text.contains(omitted), omitted);
            for (long id : List.of(1L, 2L, 3L))
                assertTrue(urls(document).contains("https://example.com/article-" + id));
        }
    }

    @Test
    void standaloneFindingsDoNotCreateAnOtherAnalysisSection() throws Exception {
        var report = report(ReportScope.DAILY);
        var content = new ReportContent(List.of(), List.of(), List.of(), List.of());
        String text = text(renderer.render(report, view(content,
                List.of(finding(1, "제외할 기사", "제외할 분석"), finding(2, "유효한 기사", "유효한 분석")), Set.of(1L)), null));
        assertFalse(text.contains("제외할"));
        assertFalse(text.contains("유효한 분석"));
        assertFalse(text.contains("기타 분석"));
        verify(dart).getStored(report);
        verifyNoMoreInteractions(dart);
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
    void appendixKeepsOnlyLatestFourQuartersAndOneRecentFilingWithoutChangingStoredData() throws Exception {
        var report = report(ReportScope.RUN);
        var filing = filing("반기보고서", FILING_URL, true);
        String sourceUrl = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260331000001";
        var quarterSource = new DartModels.Filing("20260331000001", "분기 실적 근거 전용", "2026-03-31", sourceUrl, false);
        var quarters = List.of(quarter(2026, 4, "123456789012", "-1234567890", null, List.of(quarterSource)),
                quarter(2025, 3, "999999999", "88", null, List.of()),
                quarter(2026, 1, "10000000005000001.20", "-1", null, List.of(quarterSource)),
                quarter(2026, 2, null, "0", BigDecimal.ZERO, List.of()),
                quarter(2025, 4, "999999999", "77", null, List.of()),
                quarter(2026, 3, "5000000000", "500000000", null, List.of()));
        var financials = new DartModels.FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-14T16:30:00Z"),
                quarters, List.of(quarterSource, filing), List.of("동일한 재무 기준으로 비교합니다.", "2025년 3분기 이전 자료 안내"));
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L,
                List.of(company("선택 회사", "PARTIAL", financials))));

        try (PDDocument document = Loader.loadPDF(renderer.render(report, companyView("선택 회사"), null))) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("2026년 1분기", "2026년 2분기", "2026년 3분기", "2026년 4분기",
                    "매출", "영업이익", "자료 없음", "산출값", "2026-10-15 01:30", "일부 자료 없음",
                    "동일한 재무 기준으로 비교합니다", "최근 4분기 · 억원 (KRW)", "소수 둘째 자리 반올림",
                    "100,000,000.05", "1,234.57", "-12.35", "-0.00000001", "자료 없음은 0이 아닙니다."))
                assertTrue(text.contains(expected), expected);
            for (String absent : List.of("2025년", "영업이익률", "분기 실적 근거 전용", "회사별 부록 바로가기", "공시 상세", "12분기"))
                assertFalse(text.contains(absent), absent);
            assertTrue(text.indexOf("본문 회사 이슈") < text.indexOf("회사 실적 · 공시"));
            assertTrue(text.contains("[정정] 반기보고서"));
            assertEquals(1, urls(document).stream().filter(FILING_URL::equals).count());
            assertFalse(urls(document).contains(sourceUrl));
            assertWithinPageBounds(document);
        }
        assertEquals(6, financials.quarters().size());
        assertEquals(2, financials.disclosures().size());
    }

    @Test
    void finalRendererInputCannotRestoreACompanyAbsentFromVisibleIssueTitles() throws Exception {
        var attachment = company("선택 회사", "READY", new DartModels.FinancialData("CFS", "KRW", null,
                List.of(quarter(2026, 1, "5000000000", "500000000", null, List.of())), List.of(), List.of()));
        var result = renderer.renderDetailed(report(ReportScope.RUN), companyView("다른 기업"), null,
                null, null, null, null, List.of(attachment));
        assertTrue(result.companyPages().isEmpty());
        String text = text(result.bytes());
        assertFalse(text.contains("회사 실적 · 공시"));
        assertFalse(text.contains("선택 회사"));
        verifyNoInteractions(dart);
    }

    @Test
    void appendixPreservesSeparateStatementsForeignCurrencyAndMissingQuarters() throws Exception {
        var report = report(ReportScope.RUN);
        var financials = new DartModels.FinancialData("OFS", "USD", null,
                List.of(quarter(2026, 1, "10000000000000001.20", "-0.01", null, List.of()),
                        quarter(2026, 2, null, null, null, List.of())), List.of(), List.of());
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L,
                List.of(company("선택 회사", "PARTIAL", financials))));
        try (var document = Loader.loadPDF(renderer.render(report, companyView("선택 회사"), null))) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("별도재무제표", "단위: USD", "확인 시각 미상",
                    "10,000,000,000,000,001.20", "-0.01", "자료 없음", "자료가 없는 분기는 선을 연결하지 않습니다."))
                assertTrue(text.contains(expected), expected);
            assertFalse(text.contains("억원"));
            assertWithinPageBounds(document);
        }
    }

    @Test
    void topicWeeklyPdfIncludesStoredFinancialsAndRecentFilingsAfterNewsWithoutPreparingData() throws Exception {
        var report = StoredReportDartFixture.topicWeekly();
        var stored = new StoredReportDartFixture();
        stored.ready(report);
        var content = new ReportContent(List.of("주간 뉴스 요약은 그대로 유지합니다."),
                List.of(new ReportContent.ImportantEvent("SK하이닉스 주간 주요 이슈", "저장한 뉴스 내용", "표시하지 않는 추가 분석", List.of(1L))),
                List.of(), List.of());
        byte[] bytes = new ReportPdfRenderer(stored.service()).render(report,
                view(content, List.of(finding(1, "주간 뉴스 기사", "저장한 뉴스 내용")), Set.of()), null);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("회사 실적", "SK하이닉스", "000660", "연결재무제표", "2026년 1분기", "2026년 2분기",
                    "단위: 억원 (KRW)", "1,234.57", "-12.35", "주간 주요 이슈", "저장한 뉴스 내용"))
                assertTrue(text.contains(expected), expected + "\n" + text);
            assertTrue(text.indexOf("핵심 브리핑") < text.indexOf("회사 실적"));
            assertTrue(text.contains("표시하지 않는 추가 분석"));
            for (String expected : List.of("회사 실적 · 공시", "최근 공시", "[정정]", "반기보고서 (가상 검증 자료)",
                    "2026-08-14")) assertTrue(text.contains(expected), expected);
            assertFalse(text.contains("이전 공시 (가상 검증 자료)"));
            assertTrue(text.indexOf("핵심 브리핑") < text.indexOf("최근 공시"));
            assertTrue(urls(document).contains(StoredReportDartFixture.financials().disclosures().getFirst().url()));
            assertFalse(urls(document).contains(StoredReportDartFixture.financials().disclosures().getLast().url()));
            assertTrue(urls(document).contains("https://example.com/article-1"));
            assertWithinPageBounds(document);
        }
        stored.verifyStoredReads(report.getId(), 1);
        Path path = Path.of("build/reports/pdf/topic-weekly-dart-sample.pdf");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    @Test
    void partialTopicWeeklyWithoutQuartersKeepsOnlyOneBoundedRecentFiling() throws Exception {
        var report = StoredReportDartFixture.topicWeekly();
        var stored = new StoredReportDartFixture();
        var filings = IntStream.rangeClosed(1, 6).mapToObj(i -> {
            String receipt = "2026101900000" + i;
            String title = "공시 항목 " + i + ": "
                    + "생산 설비 증설과 공급 계약 일정에 관한 확인 사항입니다. ".repeat(24) + "공시 끝 " + i;
            return new DartModels.Filing(receipt, title, "2026-10-" + (20 - i),
                    "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt, i == 1);
        }).toList();
        var data = new DartModels.FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-19T09:00:00+09:00"),
                List.of(), filings, List.of("비교 가능한 분기 실적은 확보하지 못했습니다."));
        stored.stored(report, "PARTIAL", data);
        byte[] bytes = new ReportPdfRenderer(stored.service()).render(report, companyView("SK하이닉스"), null);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(document.getNumberOfPages() <= 3, "a long filing name must not expand into a long appendix");
            assertTrue(text.contains("공시 항목 1:"));
            assertFalse(text.contains("공시 끝 1"));
            assertTrue(urls(document).contains(filings.getFirst().url()));
            for (var filing : filings.subList(1, 6)) assertFalse(urls(document).contains(filing.url()));
            for (String expected : List.of("회사 실적 · 공시", "최근 공시", "[정정]", "2026-10-19",
                    "표시할 분기 실적 자료가 없습니다.", "비교 가능한 분기 실적은 확보하지 못했습니다."))
                assertTrue(text.contains(expected), expected);
            for (String absent : List.of("공시 항목 6", "공시 끝 6", "저장된 공시 자료가 없습니다.", "차트 단위"))
                assertFalse(text.contains(absent), absent);
            assertFalse(urls(document).contains(filings.get(5).url()));
            assertWithinPageBounds(document);
            for (var page : document.getPages()) for (var annotation : page.getAnnotations())
                if (annotation instanceof PDAnnotationLink link) {
                    var rectangle = link.getRectangle();
                    assertTrue(rectangle.getLowerLeftX() >= 40 && rectangle.getUpperRightX() <= 553);
                    assertTrue(rectangle.getLowerLeftY() > 20 && rectangle.getUpperRightY() < page.getMediaBox().getHeight() - 20);
                }
        }
        assertEquals(6, data.disclosures().size(), "rendering must not trim the stored disclosure list");
        stored.verifyStoredReads(report.getId(), 1);
        Path path = Path.of("build/reports/pdf/topic-weekly-long-filings-sample.pdf");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    @Test
    void readyTopicWeeklyExplainsMissingStoredDisclosuresWithoutDroppingFinancials() throws Exception {
        var report = StoredReportDartFixture.topicWeekly();
        var stored = new StoredReportDartFixture();
        var data = StoredReportDartFixture.financials();
        stored.stored(report, "READY", new DartModels.FinancialData(data.fsDiv(), data.currency(), data.fetchedAt(),
                data.quarters(), List.of(), data.notes()));
        try (PDDocument document = Loader.loadPDF(new ReportPdfRenderer(stored.service()).render(report,
                companyView("SK하이닉스"), null))) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("단위: 억원 (KRW)", "최근 공시", "저장된 공시 자료가 없습니다."))
                assertTrue(text.contains(expected), expected);
            assertTrue(urls(document).stream().noneMatch(url -> url.contains("dart.fss.or.kr")));
            assertWithinPageBounds(document);
        }
        stored.verifyStoredReads(report.getId(), 1);
    }

    @Test
    void ineligiblePdfReportsDoNotReadAttachmentsOrInheritHistoricalCompany() throws Exception {
        var stored = new StoredReportDartFixture();
        var actualRenderer = new ReportPdfRenderer(stored.service());
        for (var report : StoredReportDartFixture.withoutEligibleCompany()) {
            String text = text(actualRenderer.render(report, view(emptyContent(), List.of(), Set.of()), null));
            assertFalse(text.contains("회사 실적"), report.getTitle());
            assertFalse(text.contains("연결재무제표"), report.getTitle());
            assertFalse(text.contains("최근 공시"), report.getTitle());
        }
        stored.verifyNoPersistenceAccess();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/doc", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814009999", "javascript:alert(1)"})
    void rejectsUnsafeLinksAndNonReadyFinancialPayloadsWithoutReadingRemoteResources(String unsafeUrl) throws Exception {
        var report = report(ReportScope.RUN);
        var payload = new DartModels.FinancialData("OFS", "USD", null, List.of(),
                List.of(filing("안전하지 않은 공시 제목", unsafeUrl, false)), List.of());
        var companies = new ArrayList<ReportDartDTO.CompanyAttachment>();
        for (String status : List.of("PENDING", "FAILED", "DISABLED")) companies.add(company("비공개 " + status + " 회사", status,
                new DartModels.FinancialData("CFS", "KRW", null, List.of(),
                        List.of(filing("상태 자료 유출 " + status, FILING_URL, false)), List.of())));
        companies.add(company("공시 회사", "READY", payload));
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, companies));
        var finding = Finding.builder().id(1L).article(Article.builder().id(101L).title("외부 출처 제목")
                .canonicalUrl("javascript:alert(1)").build()).summary("근거 분석").keyPoints(List.of(new FindingKeyPoint("근거", List.of(0), "grounded"))).build();
        var content = new ReportContent(List.of(), List.of(new ReportContent.ImportantEvent("공시 회사 · 비공개 PENDING 회사 · 비공개 FAILED 회사 · 비공개 DISABLED 회사 외부 출처 제목",
                "사건 내용", "주목할 이유", List.of(1L))), List.of(), List.of());
        try (PDDocument document = Loader.loadPDF(renderer.render(report, view(content, List.of(finding), Set.of()), null))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("안전하지 않은 공시 제목"));
            assertTrue(text.contains("2026-08-14"));
            assertFalse(text.contains(unsafeUrl));
            assertTrue(text.contains("외부 출처 제목"));
            assertFalse(text.contains("상태 자료 유출"));
            assertFalse(text.contains("javascript:"));
            assertTrue(urls(document).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"기타 분석", "기타 분석 이슈", "기사별 분석", "보고서 제외 이슈"})
    void legacyOmitsOtherAnalysisAndCollectionSubsectionsWhileKeepingFollowingSectionsAndLinks(String otherHeading) throws Exception {
        var report = report(ReportScope.DAILY);
        var reading = new ReportReadingContent("# 중복 제목\n\n## 핵심 요약\n- 저장된 요약\n\n## 중요 이벤트\n### 계약 체결\n"
                + "전체 계약 내용\n\n## 수집 및 출처 참고\n숨겨야 할 참고\n### 내부 상태\n숨겨야 할 하위 내용\n"
                + "## " + otherHeading + "\n### 다른 분석\n숨겨야 할 기사 분석\n[기타 기사](https://example.com/omitted)\n"
                + "#### 추가 근거\n숨겨야 할 하위 근거\n[추가 기사](https://example.com/omitted-nested)\n"
                + "## 관찰 항목\n마지막 관찰 내용\n[기사](https://example.com/legacy)\n- 원문: <https://example.com/filtered-legacy>\n", null);
        try (PDDocument document = Loader.loadPDF(renderer.render(report,
                new ReportEventFeedbackProjection.View(List.of(), reading, false), null))) {
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("저장된 요약", "전체 계약 내용", "관찰 항목", "마지막 관찰 내용")) assertTrue(text.contains(expected));
            assertFalse(text.contains("숨겨야"));
            assertFalse(text.contains("기타 분석"));
            assertFalse(text.contains(otherHeading));
            assertFalse(text.contains("다른 분석"));
            assertFalse(text.contains("추가 근거"));
            assertFalse(urls(document).contains("https://example.com/omitted"));
            assertFalse(urls(document).contains("https://example.com/omitted-nested"));
            assertFalse(text.contains("수집 및 출처 참고"));
            assertFalse(text.contains("중복 제목"));
            assertTrue(urls(document).contains("https://example.com/legacy"));
            assertTrue(urls(document).contains("https://example.com/filtered-legacy"));
            assertTrue(text.contains("https://example.com/filtered-legacy"));
            assertTrue(text.contains("https://example.com/legacy"));
        }
    }

    @Test
    void monetaryTableScalesWonAndRoundsWithoutConfusingMissingTinyOrNegativeValuesWithZero() {
        assertEquals("50", ReportPdfRenderer.amount("5000000000", "KRW"));
        assertEquals("1,234.57", ReportPdfRenderer.amount("123456789012", "KRW"));
        assertEquals("-12.35", ReportPdfRenderer.amount("-1234567890", "KRW"));
        assertEquals("1.01", ReportPdfRenderer.amount("100500000", "KRW"));
        assertEquals("-1.01", ReportPdfRenderer.amount("-100500000", "KRW"));
        assertEquals("0.01", ReportPdfRenderer.amount("1000000", "KRW"));
        assertEquals("0.00000001", ReportPdfRenderer.amount("1", "KRW"));
        assertEquals("-0.00000001", ReportPdfRenderer.amount("-1", "KRW"));
        assertEquals("0", ReportPdfRenderer.amount("0.00", "KRW"));
        assertEquals("자료 없음", ReportPdfRenderer.amount(null, "KRW"));
        assertEquals("자료 없음", ReportPdfRenderer.amount("NaN", "KRW"));
        assertEquals("10,000,000,000,000,001.20", ReportPdfRenderer.amount("10000000000000001.20", "USD"));
        assertEquals("-0.01", ReportPdfRenderer.amount("-0.01", null));
    }

    @Test
    void exportsComprehensiveSampleForVisualReview() throws Exception {
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN)
                .collectionContexts(report(ReportScope.RUN).getCollectionContexts()).title("PDF 형식 예시 · 가상 데이터")
                .generatedAt(LocalDateTime.of(2026, 10, 15, 9, 0)).markdownBody("").build();
        var content = new ReportContent(List.of("반도체 투자 계획과 신규 생산라인의 장비 발주 일정을 함께 점검했습니다.",
                "이번 보고서에서는 확인된 사실과 관점별 종합 판단을 구분하여 제공합니다."),
                List.of(new ReportContent.ImportantEvent("예시 반도체 차세대 공정 투자 계획 구체화", "제조사는 신규 공정 전환과 생산 능력 확대를 위한 투자 계획을 공개했습니다. 장비 반입과 양산 준비 일정이 후속 확인 대상입니다.",
                        "발주 규모와 인도 시점을 함께 확인해야 장비 업체 실적 반영 시기를 판단할 수 있습니다.", List.of(1L))),
                List.of(new ReportContent.WatchItem("설비 발주 일정", "공식 공시와 공급계약에서 투자 집행 여부를 확인합니다.", List.of(2L))), List.of("수집 상태는 출력하지 않습니다."));
        var findings = List.of(finding(1, "신규 생산라인 투자 계획 발표", "회사가 생산라인 투자 계획을 발표했습니다."),
                finding(2, "주요 공급사 계약 현황", "공급사의 계약 일정과 장비 반입 일정을 확인했습니다."),
                finding(3, "시장 수요와 재고 동향", "주요 제품의 수요와 재고 수준은 다음 분기 생산 계획을 판단하는 참고 지표입니다."));
        var filing = filing("반기보고서 (2026.06)", FILING_URL, false);
        var quarters = IntStream.range(0, 12).mapToObj(i -> {
            long revenue = 5_000_000_000L + i * 300_000_000L;
            long profit = (i - 2) * 140_000_000L;
            BigDecimal margin = BigDecimal.valueOf(profit).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(revenue), 2, RoundingMode.HALF_UP);
            return quarter(2023 + (i + 2) / 4, (i + 2) % 4 + 1,
                    i == 5 ? null : String.valueOf(revenue), i == 5 ? null : String.valueOf(profit),
                    i == 5 ? null : margin, List.of(filing));
        }).toList();
        when(dart.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of(company("예시 반도체", "PARTIAL",
                new DartModels.FinancialData("CFS", "KRW", OffsetDateTime.parse("2026-10-15T09:00:00+09:00"),
                        quarters, List.of(filing, filing("주요사항보고서 (시설투자)", FILING_URL, true)),
                        List.of("2024년 4분기는 비교 가능한 금액이 없어 비워 두었습니다."))))));
        var fact = new ReportInsightDTO.Fact("1:0", "회사는 생산라인 투자를 계획하고 있다고 발표했다.", "FORECAST", null, 1L, 101L, List.of(0), "grounded");
        var insight = new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "장비 수요의 집행 시점 점검", "high",
                List.of(new AgentReportInsightResponse.Overview("투자 계획은 장비 공급사의 수요 증가로 이어질 수 있습니다.", List.of("1:0"), "투자 계획이 실제 발주로 전환되는 경우")),
                List.of(new ReportInsightDTO.Issue(1L, "high", "생산라인 증설은 장비 반입과 설치 수요에 직접 연결됩니다.", List.of("1:0"), new AgentReportInsightResponse.Axes(3, 2, null, null), 1)),
                List.of(fact), List.of(new AgentReportInsightResponse.Implication("발주 시점에 따라 실적 반영 기간이 달라질 수 있습니다.", "설비 발주 후 제조·인도·검수를 거쳐 매출이 인식됩니다.", List.of("1:0"), "계약과 인도 일정이 확정되는 경우", "투자 집행이 연기되거나 계약 규모가 축소되는 경우")),
                List.of(new AgentReportInsightResponse.WatchItem("장비 발주", "공급계약과 수주 잔고", "계약 체결 및 인도 일정 확인", List.of("1:0"))), "test", "test", OffsetDateTime.parse("2026-10-15T09:00:00+09:00"));
        byte[] bytes = renderer.render(report, view(content, findings, Set.of()), insight);
        Path path = Path.of("build/reports/pdf/report-attachment-sample.pdf");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertWithinPageBounds(document);
            String text = new PDFTextStripper().getText(document);
            for (String expected : List.of("관점 한눈에 · 장비·소재사", "업무상 의미", "다음 확인점"))
                assertTrue(text.contains(expected), expected);
            for (String omitted : List.of("먼저 살펴볼 이슈", "생산라인 증설은 장비 반입과 설치 수요에 직접 연결됩니다.",
                    "기타 분석", "우선순위", "중요도", "직접 관련성", "영향 크기", "조건부 해석"))
                assertFalse(text.contains(omitted), omitted);
        }
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
        return NewsReport.builder().id(17L).reportScope(scope)
                .collectionContexts(List.of(new com.example.be.domain.reports.entity.ReportCollectionContext(42L,
                        List.of(new com.example.be.domain.collection.entity.CollectionTopicSnapshot(29L, "선택 회사", null,
                                List.of(), List.of(), List.of(), 10, 60, new DartModels.Company("00126380", "선택 회사", "005930"))))))
                .title("반도체 산업 동향 · 상세 뉴스 리포트")
                .generatedAt(LocalDateTime.of(2026, 10, 15, 9, 0)).markdownBody("원본 보고서에만 남은 비공개 내용").build();
    }

    private static Finding finding(long id, String title, String summary) {
        return Finding.builder().id(id).article(Article.builder().id(100 + id).title(title).canonicalUrl("https://example.com/article-" + id)
                .sourceName("산업 뉴스").publishedAt(OffsetDateTime.parse("2026-10-15T08:00:00+09:00")).build())
                .summary(summary).keyPoints(List.of(new FindingKeyPoint("기사의 확인된 근거", List.of(0), "grounded"))).build();
    }

    private static ReportEventFeedbackProjection.View companyView(String company) {
        var content = new ReportContent(List.of(), List.of(new ReportContent.ImportantEvent(
                company + " 본문 회사 이슈", "저장한 뉴스 내용", "", List.of(1L))), List.of(), List.of());
        return view(content, List.of(finding(1, company + " 뉴스", "저장한 뉴스 내용")), Set.of());
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
