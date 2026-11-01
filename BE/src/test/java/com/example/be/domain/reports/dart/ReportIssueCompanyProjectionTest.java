package com.example.be.domain.reports.dart;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.dto.res.ReportDartDTO.IssueLink;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportIssueCompanyProjectionTest {
    private final Filing recent = filing("20261029000001", "2026-10-29", false);
    private final Filing original = filing("20260814000001", "2026-08-14", false);
    private final Filing correction = filing("20261029000002", "2026-10-29", true);

    @Test void unionRetainsFinancialSourcesAndCorrectionReceiptsWhileMergingRoles() {
        var rows = ReportIssueCompanyProjection.filingRows(financials(), List.of(link(1, recent.rceptNo())));
        assertEquals(List.of(recent, correction, original), rows.stream().map(row -> row.filing()).toList());
        assertEquals(List.of("RECENT", "FINANCIAL_SOURCE"), rows.getFirst().roles());
        assertEquals("DIRECT_REFERENCE", rows.getFirst().relevance());
        assertEquals(List.of(1L), rows.getFirst().sourceFindingIds());
        assertEquals("COMPANY_REFERENCE", rows.get(1).relevance());
        assertEquals(List.of("FINANCIAL_SOURCE"), rows.getLast().roles());
        assertTrue(rows.get(1).filing().corrected());
        assertFalse(rows.getLast().filing().corrected());
    }

    @Test void removedDirectReferenceIsRecomputedEvenWhenTheSameCompanyHasOtherVisibleEvidence() {
        var direct = link(1, recent.rceptNo());
        var visible = link(2);
        var entry = attachment(List.of(direct, visible));
        var projected = ReportIssueCompanyProjection.project(report(ReportScope.DAILY),
                view(List.of(finding(1), finding(2)), Set.of(1L)), List.of(entry));
        assertEquals(List.of(visible), projected.getFirst().issueLinks());
        assertTrue(projected.getFirst().filingRows().stream().allMatch(row -> row.relevance().equals("COMPANY_REFERENCE")
                && row.sourceFindingIds().isEmpty()));
        assertEquals("DIRECT_REFERENCE", entry.filingRows().getFirst().relevance(), "stored response is immutable");
    }

    @Test void automaticCompaniesNeedRetainedOriginalArticleIdentityEvenWithoutFilterFlags() {
        var entry = attachment(List.of(link(1, recent.rceptNo())));
        var report = report(ReportScope.DAILY);
        assertTrue(ReportIssueCompanyProjection.project(report, view(List.of(), Set.of()), List.of(entry)).isEmpty());
        assertTrue(ReportIssueCompanyProjection.project(report, null, List.of(entry)).isEmpty());
        var replacedArticle = Finding.builder().id(1L).article(Article.builder().id(999L).build()).build();
        assertTrue(ReportIssueCompanyProjection.project(report, view(List.of(replacedArticle), Set.of()), List.of(entry)).isEmpty());
        var suppressed = NewsReport.builder().id(17L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.GENERATED)
                .companySuppressedFindingIds(List.of(1L)).build();
        assertTrue(ReportIssueCompanyProjection.project(suppressed, view(List.of(finding(1)), Set.of()), List.of(entry)).isEmpty());
    }

    @Test void explicitSelectionSurvivesAnUnfilteredViewButNeverRetainsInvisibleIssueLinks() {
        var projected = ReportIssueCompanyProjection.project(report(ReportScope.RUN, SAMSUNG),
                view(List.of(), Set.of()), List.of(attachment(List.of(link(1, recent.rceptNo())))));
        assertEquals(SAMSUNG, projected.getFirst().company());
        assertTrue(projected.getFirst().issueLinks().isEmpty());
        assertTrue(projected.getFirst().filingRows().stream().allMatch(row -> row.sourceFindingIds().isEmpty()));
    }

    @Test void newAggregateMetadataUsesOnlyFrozenRetainedCompaniesWithoutReadingLiveTopics() {
        var snapshot = new ReportIssueCompanySnapshot("report-issue-companies.v1", OffsetDateTime.now(), List.of(
                frozenLink(SAMSUNG, 1), frozenLink(HYNIX, 2), frozenLink(SAMSUNG, 3)));
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.GENERATED)
                .reportIssueCompanySnapshot(snapshot).build();
        assertEquals(List.of(SAMSUNG, HYNIX), ReportDartEligibility.companies(report));
        assertEquals(List.of(HYNIX), ReportIssueCompanyProjection.companies(report, view(List.of(finding(2)), Set.of())));
        assertTrue(ReportIssueCompanyProjection.companies(report, view(List.of(finding(2)), Set.of(2L))).isEmpty());
        assertEquals(List.of(SAMSUNG, HYNIX), ReportDartEligibility.companies(report), "projection never edits eligibility");
    }

    @Test void legacyConstructorOffersReferenceRowsWithoutInventingArticleLinks() {
        var entry = new CompanyAttachment(SAMSUNG, "READY", "완료", financials());
        assertTrue(entry.issueLinks().isEmpty());
        assertEquals(3, entry.filingRows().size());
        assertTrue(entry.filingRows().stream().allMatch(row -> row.relevance().equals("COMPANY_REFERENCE")));
    }

    private CompanyAttachment attachment(List<IssueLink> links) {
        return new CompanyAttachment(SAMSUNG, "READY", "완료", financials(), links,
                ReportIssueCompanyProjection.filingRows(financials(), links));
    }

    private FinancialData financials() {
        return new FinancialData("CFS", "KRW", OffsetDateTime.now(), List.of(
                new Quarter(2026, 2, "100", "10", null, false, List.of(original, recent))),
                List.of(recent, correction), List.of());
    }

    private IssueLink link(long id, String... receipts) {
        return new IssueLink(id, id + 100, id + 1000, "삼성전자", "삼성전자가 발표했다.", "https://example.com/" + id, List.of(receipts));
    }

    private ReportIssueCompanySnapshot.Link frozenLink(com.example.be.domain.dart.model.DartModels.Company company, long id) {
        return new ReportIssueCompanySnapshot.Link(company, id, id + 100, id + 1000, company.corpName(),
                company.corpName() + "의 공시", "https://example.com/" + id, List.of());
    }

    private Finding finding(long id) { return Finding.builder().id(id).article(Article.builder().id(id + 100).build()).build(); }

    private ReportEventFeedbackProjection.View view(List<Finding> findings, Set<Long> exclusions) {
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", null), false, exclusions, false);
    }

    private Filing filing(String receipt, String date, boolean corrected) {
        return new Filing(receipt, "공시 " + receipt, date, "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt, corrected);
    }
}
