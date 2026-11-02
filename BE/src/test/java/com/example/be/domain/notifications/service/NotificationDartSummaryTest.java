package com.example.be.domain.notifications.service;

import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.domain.reports.dart.StoredReportDartFixture;
import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationDartSummaryTest {
    private static final String RECEIPT = "20260814001234";
    private static final String FILING_URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + RECEIPT;
    private static final String REPORT_URL = "https://news.example/#/reports?reportId=17";
    private final ReportDartService service = mock(ReportDartService.class);
    private final NotificationDartSummary summary = new NotificationDartSummary(service);
    private final NewsReport report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN)
            .collectionContexts(List.of(new com.example.be.domain.reports.entity.ReportCollectionContext(42L,
                    List.of(new com.example.be.domain.collection.entity.CollectionTopicSnapshot(29L, "선택 회사", null,
                            List.of(), List.of(), List.of(), 10, 60, new Company("00126380", "선택 회사", "005930")))))).build();

    @Test
    void rendersQuarterChartAndTableWithOneRecentFiling() {
        var financials = data("CFS", "KRW", List.of(quarter(1, "999999999999", "999999999999"),
                quarter(2, "123456789012", "-1234567890")), List.of(
                filing("반기보고서", FILING_URL, true), filing("이전 보고서", FILING_URL, false)));

        String html = render(attachment("삼성전자", "READY", financials));
        String text = Jsoup.parse(html).text();

        assertTrue(text.contains("삼성전자 연결재무제표 · 단위: 억원 (KRW)"));
        assertEquals(List.of("2026년 1분기 10,000 10,000", "2026년 2분기 1,234.57 -12.35"),
                Jsoup.parse(html).select("table:not([role]) tbody tr").eachText());
        assertFalse(Jsoup.parse(html).select("td[bgcolor='#d14b4b']").isEmpty());
        assertTrue(text.contains("최근 공시 · [정정] 반기보고서 · 2026-08-14"));
        assertFalse(text.contains("이전 보고서"));
        assertTrue(text.contains("2026년 1분기"));
        assertEquals(FILING_URL, Jsoup.parse(html).select("a").getFirst().attr("href"));
        assertEquals(1, Jsoup.parse(html).select("a").size());
        assertFalse(text.contains("첨부 PDF"));
        assertEquals("presentation", Jsoup.parse(html).selectFirst("table").attr("role"));
    }

    @Test
    void preservesLargeDecimalPrecisionAndSmallLossesWithoutRoundingThemToZero() {
        String html = render(attachment("정밀도 회사", "READY", data("OFS", "KRW",
                List.of(quarter(4, "10000000005000001", "-1")), List.of())));
        String text = Jsoup.parse(html).text();
        assertTrue(text.contains("별도재무제표 · 단위: 억원 (KRW)"));
        assertTrue(text.contains("2026년 4분기* 100,000,000.05 -0.00000001"));
        assertTrue(text.contains("* 산출값"));
    }

    @Test
    void doesNotFillMissingLatestNumbersWithOlderQuarterAndDistinguishesZero() {
        String html = render(attachment("부분 회사", "PARTIAL", data(null, "KRW", List.of(
                quarter(1, "10000000000", "5000000000"), quarter(2, null, "0")), List.of())));
        String text = Jsoup.parse(html).text();
        assertTrue(text.contains("재무 기준 미확인"));
        assertEquals(List.of("2026년 1분기 100 50", "2026년 2분기 자료 없음 0"),
                Jsoup.parse(html).select("table:not([role]) tbody tr").eachText());
        assertTrue(text.contains("일부 자료 없음"));
        assertEquals(2, Jsoup.parse(html).select("table[aria-label]").size());
    }

    @Test
    void leavesNonWonAmountsInOriginalCurrencyAndExactScale() {
        String html = render(attachment("달러 회사", "READY", data("OFS", "USD",
                List.of(quarter(2, "10000000000000001.20", "-0.01")), List.of())));
        String text = Jsoup.parse(html).text();
        assertTrue(text.contains("단위: USD"));
        assertTrue(text.contains("2026년 2분기 10,000,000,000,000,001.20 -0.01"));
        assertFalse(text.contains("억원"));
    }

    @Test
    void selectsLatestFourQuartersInChronologicalOrderAndNewestFilingFromUnsortedSavedData() {
        var quarters = List.of(quarter(4, "400000000", "-40000000"),
                new Quarter(2025, 4, "900000000", "90000000", null, true, List.of()),
                quarter(2, null, "0"), quarter(1, "100000000", "10000000"),
                quarter(3, "300000000", "30000000"));
        var latest = new Filing("20261114001234", "새 분기 공시", "2026-11-14",
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261114001234", false);
        String html = render(attachment("정렬 회사", "PARTIAL", data("CFS", "KRW", quarters,
                List.of(filing("이전 반기 공시", FILING_URL, false), latest))));
        var document = Jsoup.parse(html);
        assertEquals(List.of("2026년 1분기 1 0.1", "2026년 2분기 자료 없음 0", "2026년 3분기 3 0.3", "2026년 4분기* 4 -0.4"),
                document.select("table:not([role]) tbody tr").eachText());
        assertFalse(document.text().contains("2025"));
        assertFalse(document.text().contains("이전 반기 공시"));
        assertEquals(List.of(latest.url()), document.select("a").eachAttr("href"));
        assertEquals(6, document.select("table[aria-label]").size());
        assertEquals(1, document.select("td[bgcolor='#d14b4b']").size());
        assertTrue(document.select("script,svg,img,canvas").isEmpty());
        document.select("table[aria-label]").forEach(bar -> {
            int width = Integer.parseInt(bar.attr("width").replace("%", ""));
            assertTrue(width >= 1 && width <= 100);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED", "DISABLED"})
    void nonReadyStatesNeverRenderEvenPresentFinancialPayload(String status) {
        String html = render(attachment("준비 회사", status, data("CFS", "KRW",
                List.of(quarter(2, "100000000", "10000000")), List.of(filing("숨긴 공시", FILING_URL, false)))));
        assertTrue(html.contains("준비 회사"));
        assertFalse(html.contains("매출"));
        assertFalse(html.contains("숨긴 공시"));
        assertFalse(html.contains("자료 확인 2026"));
        assertTrue(html.contains(switch (status) {
            case "FAILED" -> "공시 자료 확인 실패";
            case "DISABLED" -> "공시 자료 확인 불가";
            default -> "공시 자료 준비 중";
        }));
    }

    @Test
    void absentFinancialQuarterStillShowsTheRecentFiling() {
        String html = render(attachment("공시 회사", "PARTIAL", data(null, null, List.of(),
                List.of(filing("주요사항보고서", FILING_URL, false)))));
        assertTrue(html.contains("분기 실적 자료 없음"));
        assertTrue(html.contains("주요사항보고서"));
    }

    @Test
    void includesEveryEligibleCompanyWithoutRepeatingPdfNavigation() {
        var companies = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> attachment("회사 " + index, "PENDING", null)).toList();
        String html = summary.render(REPORT_URL, Map.of(), companies);
        assertTrue(html.contains("회사 1"));
        assertTrue(html.contains("회사 3"));
        assertTrue(html.contains("회사 4"));
        assertTrue(html.contains("회사 5"));
        assertFalse(html.contains("외 2개 회사"));
        assertFalse(html.contains("첨부 PDF"));
        assertEquals(5, Jsoup.parse(html).select("h3").size());
    }

    @Test
    void escapesUntrustedCompanyAndFilingTextAndBoundsLongTitles() {
        String html = render(attachment("<script>alert('회사')</script> &", "READY", data("CFS", "KRW",
                List.of(quarter(2, "100000000", null)), List.of(filing("<img src=x onerror=alert(1)> & " + "길".repeat(100), FILING_URL, false)))));
        var document = Jsoup.parse(html);
        assertTrue(document.select("script,img").isEmpty());
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp;"));
        assertFalse(document.text().contains("길".repeat(100)));
        assertTrue(document.text().contains("…"));
        String longCompany = render(attachment("회".repeat(100), "PENDING", null));
        assertEquals("회".repeat(39) + "…", Jsoup.parse(longCompany).selectFirst("h3").text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "http://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814001234",
            "https://dart.fss.or.kr.evil.example/dsaf001/main.do?rcpNo=20260814001234",
            "https://user@dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814001234",
            "https://dart.fss.or.kr:8443/dsaf001/main.do?rcpNo=20260814001234",
            "https://dart.fss.or.kr/other?rcpNo=20260814001234",
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814001235",
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814001234&extra=1",
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260814001234#fragment"
    })
    void rejectsUnsafeOrMismatchedFilingLinksButKeepsTheirTitle(String url) {
        String html = render(attachment("회사", "READY", data("CFS", "KRW", List.of(),
                List.of(filing("원문 제목", url, false)))));
        assertTrue(html.contains("원문 제목"));
        assertTrue(Jsoup.parse(html).select("a").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "//news.example/reports", "https://user@news.example/reports", "https://news.example/<script>", ""})
    void omitsUnsafeReportLinks(String reportUrl) {
        String html = summary.render(reportUrl, Map.of(), List.of(attachment("회사", "PENDING", null)));
        assertTrue(Jsoup.parse(html).select("a").isEmpty());
        assertFalse(html.contains("실적·공시 자세히"));
    }

    @Test
    void topicWeeklyUsesStoredCompanyForLatestQuarterAndFirstFilingWithoutPreparingNewData() {
        var stored = new StoredReportDartFixture();
        var weekly = StoredReportDartFixture.topicWeekly();
        stored.ready(weekly);

        String html = new NotificationDartSummary(stored.service()).render(weekly, REPORT_URL, StoredReportDartFixture.readingView(), Map.of());
        var document = Jsoup.parse(html);
        assertTrue(document.text().contains("SK하이닉스 연결재무제표 · 단위: 억원 (KRW)"));
        assertTrue(document.text().contains("2026년 2분기 1,234.57 -12.35"));
        assertTrue(document.text().contains("반기보고서 (가상 검증 자료)"));
        assertTrue(document.text().contains("2026년 1분기"));
        assertFalse(document.text().contains("이전 공시"));
        assertEquals(List.of(StoredReportDartFixture.FILING_URL),
                document.select("a").eachAttr("href"));
        stored.verifyStoredReads(weekly.getId(), 1);
    }

    @Test
    void ineligibleReportsNeverReadAttachmentsOrInheritHistoricalCompany() {
        var stored = new StoredReportDartFixture();
        var actualSummary = new NotificationDartSummary(stored.service());
        for (var ineligible : StoredReportDartFixture.withoutEligibleCompany())
            assertEquals("", actualSummary.render(ineligible, REPORT_URL), ineligible.getTitle());
        stored.verifyNoPersistenceAccess();
    }

    @Test
    void reportWithoutCompanyAddsNoBlock() {
        when(service.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of()));
        assertEquals("", summary.render(report, REPORT_URL));
    }

    @Test
    void legacyUnlistedSelectionAddsNoCompanyBlockWithoutAnEvidenceView() {
        var unlisted = new Company("00999999", "비상장 선택 회사", "");
        var legacy = NewsReport.builder().id(18L).reportScope(ReportScope.WEEKLY).topicId(29L)
                .topicDartCorpCode(unlisted.corpCode()).topicDartCorpName(unlisted.corpName())
                .topicDartStockCode(unlisted.stockCode()).build();
        when(service.getStored(legacy)).thenReturn(new ReportDartDTO.Result(18L,
                List.of(new CompanyAttachment(unlisted, "READY", "저장 상태", data("CFS", "KRW",
                        List.of(quarter(2, "100000000", "10000000")), List.of(filing("숨길 공시", FILING_URL, false)))))));
        assertEquals("", summary.render(legacy, REPORT_URL));
    }

    private String render(CompanyAttachment company) {
        return summary.render(REPORT_URL, Map.of(), List.of(company));
    }

    private CompanyAttachment attachment(String name, String status, FinancialData financials) {
        return new CompanyAttachment(new Company("00126380", name, "005930"), status, "저장 상태", financials);
    }

    private FinancialData data(String basis, String currency, List<Quarter> quarters, List<Filing> filings) {
        return new FinancialData(basis, currency, OffsetDateTime.parse("2026-10-14T16:30:00Z"), quarters, filings, List.of());
    }

    private Quarter quarter(int quarter, String revenue, String profit) {
        return new Quarter(2026, quarter, revenue, profit, null, quarter == 4, List.of());
    }

    private Filing filing(String name, String url, boolean corrected) {
        return new Filing(RECEIPT, name, "2026-08-14", url, corrected);
    }
}
