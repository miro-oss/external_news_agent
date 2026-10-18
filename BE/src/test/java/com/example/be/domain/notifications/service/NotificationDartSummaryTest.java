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
    private final NewsReport report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).build();

    @Test
    void rendersLatestQuarterAndOneFilingWithActualKoreanFetchDate() {
        var financials = data("CFS", "KRW", List.of(quarter(1, "999999999999", "999999999999"),
                quarter(2, "123456789012", "-1234567890")), List.of(
                filing("반기보고서", FILING_URL, true), filing("이전 보고서", FILING_URL, false)));

        String html = render(attachment("삼성전자", "READY", financials));
        String text = Jsoup.parse(html).text();

        assertTrue(text.contains("삼성전자 2026년 2분기 · 연결 · 매출 1,234.6억원 · 영업이익 -12.3억원"));
        assertTrue(text.contains("최근 공시 · [정정] 반기보고서 · 2026-08-14"));
        assertTrue(text.contains("자료 확인 2026-10-15 (한국시간)"));
        assertFalse(text.contains("이전 보고서"));
        assertFalse(text.contains("2026년 1분기"));
        assertEquals(FILING_URL, Jsoup.parse(html).select("a").getFirst().attr("href"));
        assertEquals(REPORT_URL, Jsoup.parse(html).select("a").getLast().attr("href"));
        assertEquals("presentation", Jsoup.parse(html).selectFirst("table").attr("role"));
    }

    @Test
    void preservesLargeDecimalPrecisionAndSmallLossesWithoutRoundingThemToZero() {
        String html = render(attachment("정밀도 회사", "READY", data("OFS", "KRW",
                List.of(quarter(4, "10000000005000001", "-1")), List.of())));
        String text = Jsoup.parse(html).text();
        assertTrue(text.contains("2026년 4분기(산출값) · 별도 · 매출 100,000,000.1억원 · 영업이익 -0.00000001억원"));
    }

    @Test
    void doesNotFillMissingLatestNumbersWithOlderQuarterAndDistinguishesZero() {
        String html = render(attachment("부분 회사", "PARTIAL", data(null, "KRW", List.of(
                quarter(1, "10000000000", "5000000000"), quarter(2, null, "0")), List.of())));
        String text = Jsoup.parse(html).text();
        assertTrue(text.contains("2026년 2분기 · 재무 기준 미확인 · 매출 자료 없음 · 영업이익 0억원"));
        assertTrue(text.contains("일부 자료 없음"));
        assertFalse(text.contains("100억원"));
    }

    @Test
    void leavesNonWonAmountsInOriginalCurrencyAndExactScale() {
        String html = render(attachment("달러 회사", "READY", data("OFS", "USD",
                List.of(quarter(2, "10000000000000001.20", "-0.01")), List.of())));
        String text = Jsoup.parse(html).text();
        assertTrue(text.contains("매출 10,000,000,000,000,001.20 USD · 영업이익 -0.01 USD"));
        assertFalse(text.contains("억원"));
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
    void limitsCompaniesAndShowsTheRemainingCount() {
        var companies = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> attachment("회사 " + index, "PENDING", null)).toList();
        when(service.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, companies));
        String html = summary.render(report, REPORT_URL);
        assertTrue(html.contains("회사 1"));
        assertTrue(html.contains("회사 3"));
        assertFalse(html.contains("회사 4"));
        assertFalse(html.contains("회사 5"));
        assertTrue(html.contains("외 2개 회사"));
        assertTrue(html.contains("실적·공시 자세히"));
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
        assertEquals("회".repeat(39) + "…", Jsoup.parse(longCompany).selectFirst("strong").text());
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
        assertEquals(1, Jsoup.parse(html).select("a").size());
        assertEquals(REPORT_URL, Jsoup.parse(html).selectFirst("a").attr("href"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "//news.example/reports", "https://user@news.example/reports", "https://news.example/<script>", ""})
    void omitsUnsafeReportLinks(String reportUrl) {
        when(service.getStored(report)).thenReturn(new ReportDartDTO.Result(17L,
                List.of(attachment("회사", "PENDING", null))));
        String html = summary.render(report, reportUrl);
        assertTrue(Jsoup.parse(html).select("a").isEmpty());
        assertFalse(html.contains("실적·공시 자세히"));
    }

    @Test
    void topicWeeklyUsesStoredCompanyForLatestQuarterAndFirstFilingWithoutPreparingNewData() {
        var stored = new StoredReportDartFixture();
        var weekly = StoredReportDartFixture.topicWeekly();
        stored.ready(weekly);

        String html = new NotificationDartSummary(stored.service()).render(weekly, REPORT_URL);
        var document = Jsoup.parse(html);
        assertTrue(document.text().contains("SK하이닉스 2026년 2분기 · 연결 · 매출 1,234.6억원 · 영업이익 -12.3억원"));
        assertTrue(document.text().contains("반기보고서 (가상 검증 자료)"));
        assertTrue(document.text().contains("자료 확인 2026-10-15 (한국시간)"));
        assertFalse(document.text().contains("2026년 1분기"));
        assertFalse(document.text().contains("이전 공시"));
        assertEquals(List.of(StoredReportDartFixture.FILING_URL, REPORT_URL),
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

    private String render(CompanyAttachment company) {
        when(service.getStored(report)).thenReturn(new ReportDartDTO.Result(17L, List.of(company)));
        return summary.render(report, REPORT_URL);
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
