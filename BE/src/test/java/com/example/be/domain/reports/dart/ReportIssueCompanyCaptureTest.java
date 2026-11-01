package com.example.be.domain.reports.dart;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleBody;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.dart.company.DartCompanyRepository;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.reports.converter.ReportIssueCompanySnapshotConverter;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.reports.service.WeeklyReportSelection;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportIssueCompanyCaptureTest {
    private static final Company SAMSUNG = new Company("00126380", "삼성전자", "005930");
    private static final Company HYNIX = new Company("00164779", "SK하이닉스", "000660");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 9, 0);
    private final DartCompanyRepository catalog = mock(DartCompanyRepository.class);
    private final ReportIssueCompanyCapture capture = new ReportIssueCompanyCapture(catalog);

    @Test void capturesUniqueNamesWithSourceBoundaryAndFrozenIssueIdentity() {
        when(catalog.findExactCandidates(any())).thenReturn(List.of(SAMSUNG, HYNIX));
        var source = finding(101, "삼성전자는 투자했다. sk 하이닉스의 발표도 확인했다. 접수번호: 20261030000001", "삼성전자", "SK 하이닉스");
        var snapshot = capture.captureFindings(report(), List.of(source), NOW);
        assertTrue(snapshot.valid());
        assertEquals(List.of(SAMSUNG, HYNIX), snapshot.companies());
        assertEquals(7L, snapshot.links().getFirst().issueId());
        assertEquals("+09:00", snapshot.capturedAt().getOffset().toString());
        assertEquals(List.of("20261030000001"), snapshot.links().getFirst().rceptNos());
        assertEquals("https://news.test/101", snapshot.links().getFirst().evidenceUrl());
        source.getArticle().applyUpdate("제목", "", "새 분석에서는 기업이 사라짐", "changed", FetchStatus.FULLTEXT, null, NOW);
        assertTrue(snapshot.links().getFirst().evidenceText().contains("삼성전자는"));
    }

    @Test void neverMatchesParentNameInsideAnotherCompanyOrHallucinatedEntity() {
        when(catalog.findExactCandidates(any())).thenReturn(List.of(SAMSUNG));
        for (String body : List.of("삼성전자서비스는 수리를 맡았다.", "한국삼성전자는 별도 업체다.", "다른 회사의 기사이다.")) {
            assertTrue(capture.captureFindings(report(), List.of(finding(101, body, "삼성전자")), NOW).links().isEmpty());
        }
    }

    @Test void duplicateExactNameIsAmbiguousUntilAnAdjacentStockCodeDisambiguatesIt() {
        var other = new Company("99999999", "삼성전자", "999999");
        when(catalog.findExactCandidates(any())).thenReturn(List.of(SAMSUNG, other));
        assertTrue(capture.captureFindings(report(), List.of(finding(101, "삼성전자는 발표했다.", "삼성전자")), NOW).links().isEmpty());
        assertEquals(List.of(SAMSUNG), capture.captureFindings(report(),
                List.of(finding(101, "삼성전자(005930)는 발표했다.", "삼성전자")), NOW).companies());
    }

    @Test void nfkcEquivalentLegalNamesRemainAmbiguousAcrossDifferentCorporations() {
        when(catalog.findExactCandidates(any())).thenReturn(List.of(
                new Company("99999001", "ABC기업", "000001"), new Company("99999002", "ＡＢＣ기업", "000002")));
        assertTrue(capture.captureFindings(report(),
                List.of(finding(101, "ABC기업은 발표했다.", "ABC기업")), NOW).links().isEmpty());
    }

    @Test void shortGroupNameNeedsAdjacentStockEvidenceRatherThanNumberElsewhere() {
        var company = new Company("00181712", "SK", "034730");
        when(catalog.findExactCandidates(any())).thenReturn(List.of(company));
        assertTrue(capture.captureFindings(report(), List.of(finding(101, "SK는 발표했다. 다른 번호 034730.", "SK")), NOW).links().isEmpty());
        assertEquals(List.of(company), capture.captureFindings(report(),
                List.of(finding(101, "SK(종목코드: 034730)는 발표했다.", "SK")), NOW).companies());
    }

    @Test void contradictoryAdjacentStockCodeDoesNotFallBackToANameOnlyMatch() {
        when(catalog.findExactCandidates(any())).thenReturn(List.of(SAMSUNG));
        assertTrue(capture.captureFindings(report(),
                List.of(finding(101, "삼성전자(000660)는 발표했다.", "삼성전자")), NOW).links().isEmpty());
        assertTrue(capture.captureFindings(report(),
                List.of(finding(101, "삼성전자(005930)와 삼성전자(000660)는 발표했다.", "삼성전자")), NOW).links().isEmpty());
    }

    @Test void receiptsRequireOfficialViewerOrExplicitLabelAndPreserveCorrectionsSeparately() {
        assertEquals(List.of("20261030000001", "20261030000002"), ReportIssueCompanyCapture.receipts(
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261030000001&x=1 "
                + "접수 번호: 20261030000002 단순 숫자 20261030000003 "
                + "https://dart.fss.or.kr.evil.test/dsaf001/main.do?rcpNo=20261030000004 "
                + "https://user@dart.fss.or.kr/dsaf001/main.do?rcpNo=20261030000005 "
                + "https://dart.fss.or.kr/other?rcpNo=20261030000006 접수번호: 202610300000071"));
    }

    @Test void referencesNeverLeakAcrossArticlesInTheSameReport() {
        when(catalog.findExactCandidates(any())).thenReturn(List.of(SAMSUNG, HYNIX));
        var snapshot = capture.captureFindings(report(), List.of(
                finding(101, "삼성전자는 발표했다. 접수번호 20261030000001", "삼성전자"),
                finding(102, "SK하이닉스는 발표했다. 접수번호 20261030000002", "SK하이닉스")), NOW);
        assertEquals(List.of("20261030000001"), snapshot.links().getFirst().rceptNos());
        assertEquals(List.of("20261030000002"), snapshot.links().getLast().rceptNos());
    }

    @Test void ambiguousReceiptQueriesAreRejectedAndSentencePunctuationIsNotPartOfTheNumber() {
        assertEquals(List.of("20261030000003", "20261030000004"), ReportIssueCompanyCapture.receipts(
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261030000001&rcpNo=20261030000002 "
                + "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261030000001&rcpNo=invalid "
                + "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261030000003. "
                + "(https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261030000004)。"));
    }

    @Test void missingFullTextAndFailedCatalogNeverCreateIdentityEvidence() {
        when(catalog.findExactCandidates(any())).thenReturn(List.of(SAMSUNG));
        var source = Finding.builder().id(101L).article(Article.builder().id(101L).title("삼성전자")
                .fetchStatus(FetchStatus.METADATA_ONLY).build()).entities(new FindingEntities(List.of("삼성전자"), List.of(), List.of())).build();
        assertTrue(capture.captureFindings(report(), List.of(source), NOW).links().isEmpty());
        when(catalog.findExactCandidates(any())).thenThrow(new IllegalStateException("catalog unavailable"));
        assertNull(capture.captureFindings(report(), List.of(finding(101, "삼성전자는 발표했다.", "삼성전자")), NOW));
    }

    @Test void onlyDetachedGenerationInputsAreAcceptedAndMissingInputsAreNeverReloaded() {
        when(catalog.findExactCandidates(any())).thenReturn(List.of(SAMSUNG));
        var original = List.of(finding(101, "삼성전자는 발표했다.", "삼성전자"));
        assertNull(capture.captureSelected(report(), List.of(101L), null, NOW));
        verifyNoInteractions(catalog);
        assertEquals(1, capture.captureSelected(report(), List.of(101L), null, original, NOW).links().size());
        assertNull(capture.captureSelected(report(), List.of(101L, 102L), null, original, NOW));
    }

    @Test void weeklyUsesOnlyFrozenSelectedSourcesWithoutLiveCatalogOrFindings() {
        var snapshot = new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION, NOW.atOffset(java.time.ZoneOffset.ofHours(9)),
                List.of(link(SAMSUNG, 101), link(HYNIX, 102)));
        var source = new WeeklyReportInput.DailySource(1L, NOW.toLocalDate(), "제목", "본문", null,
                List.of(101L, 102L), null, snapshot);
        var input = new WeeklyReportInput(NOW.toLocalDate(), NOW.toLocalDate(), List.of(source), List.of());
        var report = NewsReport.builder().reportScope(ReportScope.WEEKLY).build();
        var merged = capture.captureSelected(report, List.of(101L), input, NOW);
        assertEquals(List.of(SAMSUNG), merged.companies());
        verifyNoInteractions(catalog);
        assertEquals(List.of(SAMSUNG), WeeklyReportSelection.withoutFindings(source, Set.of(102L)).companySnapshot().companies());
    }

    @Test void incompleteWeeklySourcesStayUnavailableRatherThanReconstructingCurrentCompanies() {
        var legacy = new WeeklyReportInput.DailySource(1L, NOW.toLocalDate(), "제목", "본문", null, List.of(101L));
        var input = new WeeklyReportInput(NOW.toLocalDate(), NOW.toLocalDate(), List.of(legacy), List.of());
        assertNull(capture.captureSelected(NewsReport.builder().reportScope(ReportScope.WEEKLY).build(), List.of(101L), input, NOW));
        verifyNoInteractions(catalog);
    }

    @Test void snapshotRoundTripAndCompletionFilteringDoNotAddEvidence() {
        var snapshot = new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION, NOW.atOffset(java.time.ZoneOffset.ofHours(9)),
                List.of(link(SAMSUNG, 101), link(HYNIX, 102)));
        var converter = new ReportIssueCompanySnapshotConverter();
        assertEquals(snapshot, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(snapshot)));
        assertNull(converter.convertToEntityAttribute(null));
        var report = report();
        report.freezeReportIssueCompanySnapshot(snapshot);
        report.freezeReportIssueCompanySnapshot(new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION, snapshot.capturedAt(), List.of()));
        report.retainIssueCompanyFindings(List.of(102L, 999L));
        assertEquals(List.of(HYNIX), report.getReportIssueCompanySnapshot().companies());
        report.invalidateIssueCompanySnapshot();
        assertNull(report.getReportIssueCompanySnapshot());
    }

    private static ReportIssueCompanySnapshot.Link link(Company company, long id) {
        return new ReportIssueCompanySnapshot.Link(company, id, id, 7L, company.corpName(), company.corpName() + " 발표",
                "https://news.test/" + id, List.of());
    }

    private static Finding finding(long id, String body, String... companies) {
        return Finding.builder().id(id).article(Article.builder().id(id).canonicalUrl("https://news.test/" + id)
                        .title("제목").storedBody(ArticleBody.of(body)).fetchStatus(FetchStatus.FULLTEXT).build())
                .entities(new FindingEntities(List.of(companies), List.of(), List.of())).build();
    }

    private static NewsReport report() {
        return NewsReport.builder().id(1L).reportStatus(ReportStatus.PENDING)
                .reportCoverageSnapshot(new ReportCoverageSnapshot("report-coverage.v1", NOW.atOffset(java.time.ZoneOffset.ofHours(9)),
                        "오늘", List.of(), List.of(new ReportCoverageSnapshot.Binding(101L, 7L, 3L))))
                .build();
    }
}
