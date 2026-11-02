package com.example.be.domain.reports.dart;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Quarter;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.dto.res.ReportDartDTO.IssueLink;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
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

    @Test void explicitSelectionRequiresAVisibleTitleAndNeverRetainsInvisibleIssueLinks() {
        var projected = ReportIssueCompanyProjection.project(report(ReportScope.RUN, SAMSUNG),
                view(List.of(finding(2)), Set.of()), List.of(attachment(List.of(link(1, recent.rceptNo())))));
        assertEquals(SAMSUNG, projected.getFirst().company());
        assertTrue(projected.getFirst().issueLinks().isEmpty());
        assertTrue(projected.getFirst().filingRows().stream().allMatch(row -> row.sourceFindingIds().isEmpty()));
        assertTrue(ReportIssueCompanyProjection.project(report(ReportScope.RUN, SAMSUNG),
                view(List.of(), Set.of()), List.of(attachment(List.of()))).isEmpty());
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

    @Test void mixedLegacySnapshotsExposeOnlyListedCompaniesAndTheirStoredEvidence() {
        var unlisted = new Company("99999001", "비상장기업", "");
        var listed = new Company("99999002", "신규상장기업", "0068Y0");
        var beforeListing = new Company(listed.corpCode(), listed.corpName(), "");
        var snapshot = new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION, OffsetDateTime.now(),
                List.of(frozenLink(unlisted, 1), frozenLink(beforeListing, 4), frozenLink(SAMSUNG, 2), frozenLink(listed, 3)));
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.GENERATED)
                .reportIssueCompanySnapshot(snapshot).build();

        assertTrue(snapshot.valid());
        assertEquals(List.of(SAMSUNG, listed), ReportDartEligibility.companies(report));
        assertTrue(ReportIssueCompanyProjection.storedLinks(report, unlisted.corpCode()).isEmpty());
        assertEquals(1, ReportIssueCompanyProjection.storedLinks(report, SAMSUNG.corpCode()).size());
        assertEquals(List.of(SAMSUNG, listed), ReportIssueCompanyProjection.companies(report,
                view(List.of(finding(1), finding(2), finding(3)), Set.of())));
        assertEquals(4, snapshot.links().size(), "read projection does not rewrite historical input");
    }

    @Test void legacyUnlistedAttachmentsAreExcludedEvenWithVisibleEvidenceOrExplicitSelection() {
        var unlisted = new Company("99999001", "비상장기업", "");
        var entry = new CompanyAttachment(unlisted, "READY", "완료", financials(), List.of(link(1)), List.of());

        assertTrue(ReportIssueCompanyProjection.project(report(ReportScope.RUN, unlisted),
                view(List.of(finding(1)), Set.of()), List.of(entry)).isEmpty());
        assertTrue(ReportIssueCompanyProjection.project(topicWeekly(unlisted, ReportStatus.GENERATED),
                null, List.of(entry)).isEmpty());
    }

    @Test void onlyImportantEventTitlesQualifyNotSummaryWatchOrArticleTitles() {
        var article = Finding.builder().id(1L).article(Article.builder().id(101L).title("삼성전자 기사").build()).build();
        var event = new ReportContent.ImportantEvent("행안부 업무 개선", "삼성전자의 부수 언급", "삼성전자 설명", List.of(1L));
        var content = new ReportContent(List.of("삼성전자 핵심 요약"), List.of(event),
                List.of(new ReportContent.WatchItem("삼성전자 관찰", "후속 확인", List.of(1L))), List.of());
        var view = new ReportEventFeedbackProjection.View(List.of(article), new ReportReadingContent("삼성전자 원문", content), false);
        var selected = report(ReportScope.RUN, SAMSUNG);

        assertTrue(ReportIssueCompanyProjection.project(selected, view, List.of(attachment(List.of(link(1))))).isEmpty());
        var watchOnly = new ReportContent(List.of(), List.of(), content.watchItems(), List.of());
        assertTrue(ReportIssueCompanyProjection.project(selected,
                new ReportEventFeedbackProjection.View(List.of(article), new ReportReadingContent("", watchOnly), false),
                List.of(attachment(List.of(link(1))))).isEmpty());
        assertTrue(ReportIssueCompanyProjection.project(selected,
                new ReportEventFeedbackProjection.View(List.of(article), new ReportReadingContent("삼성전자", null), false),
                List.of(attachment(List.of(link(1))))).isEmpty());
    }

    @Test void titleMatchingAcceptsSafeNormalizationAndParticlesWithoutExpandingParentsOrStockPrefixes() {
        var lg = new Company("99999010", "LG", "003550");
        var kt = new Company("99999011", "KT", "030200");
        var samsungGroup = new Company("99999012", "삼성", "999912");
        var selected = report(ReportScope.RUN, lg, kt, samsungGroup, HYNIX, SAMSUNG);
        var entries = List.of(lg, kt, samsungGroup, HYNIX, SAMSUNG).stream()
                .map(company -> new CompanyAttachment(company, "READY", "완료", financials())).toList();
        for (String title : List.of("LG유플러스 발표", "KT클라우드 서비스", "삼성전자로지텍 투자", "A003550 지표", "0035500 지표")) {
            assertTrue(ReportIssueCompanyProjection.project(selected, titledView(title, List.of(1L), List.of(finding(1))), entries).isEmpty(), title);
        }
        for (var expected : List.of(new String[]{"ＬＧ의 투자", lg.corpCode()},
                new String[]{"SK 하이닉스는 생산 확대", HYNIX.corpCode()},
                new String[]{"삼성전자가 발표", SAMSUNG.corpCode()}, new String[]{"(003550) 분기 실적", lg.corpCode()})) {
            var projected = ReportIssueCompanyProjection.project(selected, titledView(expected[0], List.of(1L), List.of(finding(1))), entries);
            assertEquals(List.of(expected[1]), projected.stream().map(entry -> entry.company().corpCode()).toList(), expected[0]);
        }
    }

    @Test void aTitleNeedsEveryRetainedSourceAndCannotReuseAnExcludedCompanyMention() {
        var selected = report(ReportScope.RUN, SAMSUNG);
        assertTrue(ReportIssueCompanyProjection.project(selected,
                titledView("삼성전자 투자", List.of(1L, 999L), List.of(finding(1))), List.of(attachment(List.of(link(1))))).isEmpty());
        var hidden = new ReportEventFeedbackProjection.View(List.of(finding(1)),
                titledView("삼성전자 투자", List.of(1L), List.of(finding(1))).readingContent(), true, Set.of(1L));
        assertTrue(ReportIssueCompanyProjection.project(selected, hidden, List.of(attachment(List.of(link(1))))).isEmpty());
    }

    @Test void longerSavedNamesOwnTheirTitleSpanWithoutHidingIndependentHoldingCompanyMentions() {
        var sk = new Company("99999030", "SK", "034730");
        var lg = new Company("99999031", "LG", "003550");
        var chemicals = new Company("99999032", "LG화학", "051910");
        var report = report(ReportScope.RUN, sk, HYNIX, lg, chemicals);
        for (String title : List.of("SK 하이닉스는 생산 확대", "ＳＫ　하이닉스의 생산 확대")) {
            assertEquals(List.of(HYNIX), ReportIssueCompanyProjection.companies(report,
                    titledView(title, List.of(1L), List.of(finding(1)))), title);
        }
        assertEquals(List.of(chemicals), ReportIssueCompanyProjection.companies(report,
                titledView("ＬＧ 화학은 투자 확대", List.of(1L), List.of(finding(1)))));
        for (String title : List.of("SK와 SK 하이닉스는 생산 확대", "SK 하이닉스와 SK는 협력")) {
            assertEquals(List.of(sk, HYNIX), ReportIssueCompanyProjection.companies(report,
                    titledView(title, List.of(1L), List.of(finding(1)))), title);
        }
        assertEquals(List.of(lg, chemicals), ReportIssueCompanyProjection.companies(report,
                titledView("LG와 LG 화학의 투자 확대", List.of(1L), List.of(finding(1)))));
    }

    @Test void hiddenUnlistedSavedNamesStillPreventAParentCompanyInference() {
        var kt = new Company("99999033", "KT", "030200");
        var cloud = new Company("99999034", "KT클라우드", "");
        var selected = report(ReportScope.RUN, kt);
        var snapshot = new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION,
                OffsetDateTime.now(), List.of(frozenLink(cloud, 2)));
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).reportStatus(ReportStatus.GENERATED)
                .collectionContexts(selected.getCollectionContexts()).reportIssueCompanySnapshot(snapshot).build();

        assertTrue(ReportIssueCompanyProjection.companies(report,
                titledView("ＫＴ 클라우드는 서비스 확대", List.of(1L), List.of(finding(1)))).isEmpty());
        assertEquals(List.of(kt), ReportIssueCompanyProjection.companies(report,
                titledView("KT는 배당 발표", List.of(1L), List.of(finding(1)))));
        assertEquals(List.of(cloud), snapshot.companies(), "Disambiguation must not rewrite hidden saved identities");
    }

    @Test void verifiedSameIssueAliasDisambiguatesTheLongerCompanyNameAtOnlyItsOwnPosition() {
        var lg = new Company("99999035", "LG", "003550");
        var chemicals = new Company("99999036", "엘지화학", "051910");
        var selected = report(ReportScope.RUN, lg);
        var snapshot = new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION, OffsetDateTime.now(),
                List.of(new ReportIssueCompanySnapshot.Link(chemicals, 1L, 101L, 1001L, "LG화학",
                        "LG화학 실적 발표", "https://example.com/1", List.of())));
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.RUN).reportStatus(ReportStatus.GENERATED)
                .collectionContexts(selected.getCollectionContexts()).reportIssueCompanySnapshot(snapshot).build();

        assertEquals(List.of(chemicals), ReportIssueCompanyProjection.companies(report,
                titledView("ＬＧ 화학의 투자 확대", List.of(1L), List.of(finding(1)))));
        assertEquals(List.of(lg, chemicals), ReportIssueCompanyProjection.companies(report,
                titledView("LG와 ＬＧ 화학은 투자 확대", List.of(1L), List.of(finding(1)))));
    }

    @Test void aliasesNeedVerifiedSavedLinksFromTheSameVisibleIssueAndIncidentalLinksAreRemoved() {
        var company = new Company("99999020", "케이티", "030200");
        var frozen = new ReportIssueCompanySnapshot.Link(company, 1L, 101L, 1001L, "KT", "KT 발표", "https://example.com/1", List.of());
        var snapshot = new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION, OffsetDateTime.now(), List.of(frozen));
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.GENERATED)
                .reportIssueCompanySnapshot(snapshot).build();
        var links = ReportIssueCompanyProjection.storedLinks(report, company.corpCode());
        var entry = new CompanyAttachment(company, "READY", "완료", financials(), links, List.of());
        assertEquals(List.of(company), ReportIssueCompanyProjection.companies(report,
                titledView("ＫＴ는 투자", List.of(1L), List.of(finding(1)))));
        assertTrue(ReportIssueCompanyProjection.project(report,
                titledView("KT클라우드 투자", List.of(1L), List.of(finding(1))), List.of(entry)).isEmpty());
        assertTrue(ReportIssueCompanyProjection.project(report,
                titledView("KT 투자", List.of(2L), List.of(finding(1), finding(2))), List.of(entry)).isEmpty());
        assertTrue(ReportIssueCompanyProjection.project(report(ReportScope.RUN, company),
                titledView("KT 투자", List.of(1L), List.of(finding(1))), List.of(entry)).isEmpty(), "an unverified attachment alias cannot establish identity");

        var samsungSnapshot = new ReportIssueCompanySnapshot(ReportIssueCompanySnapshot.RULE_VERSION, OffsetDateTime.now(),
                List.of(frozenLink(SAMSUNG, 1), frozenLink(SAMSUNG, 2)));
        var samsungReport = NewsReport.builder().id(18L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.GENERATED)
                .reportIssueCompanySnapshot(samsungSnapshot).build();
        var mixed = new ReportEventFeedbackProjection.View(List.of(finding(1), finding(2)),
                new ReportReadingContent("", new ReportContent(List.of(), List.of(
                        new ReportContent.ImportantEvent("삼성전자 발표", "요약", "의미", List.of(1L)),
                        new ReportContent.ImportantEvent("행안부 발표", "삼성전자 부수 언급", "의미", List.of(2L))), List.of(), List.of())), false);
        var projected = ReportIssueCompanyProjection.project(samsungReport, mixed,
                List.of(new CompanyAttachment(SAMSUNG, "READY", "완료", financials(),
                        ReportIssueCompanyProjection.storedLinks(samsungReport, SAMSUNG.corpCode()), List.of())));
        assertEquals(List.of(1L), projected.getFirst().issueLinks().stream().map(IssueLink::findingId).toList());
        assertEquals(2, samsungSnapshot.links().size());
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
        var events = findings.stream().map(finding -> new ReportContent.ImportantEvent(
                "삼성전자 · SK하이닉스 · 신규상장기업 발표", "요약", "의미", List.of(finding.getId()))).toList();
        return new ReportEventFeedbackProjection.View(findings,
                new ReportReadingContent("", new ReportContent(List.of(), events, List.of(), List.of())), false, exclusions, false);
    }

    private ReportEventFeedbackProjection.View titledView(String title, List<Long> sources, List<Finding> findings) {
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", new ReportContent(List.of(),
                List.of(new ReportContent.ImportantEvent(title, "요약", "의미", sources)), List.of(), List.of())), false);
    }

    private Filing filing(String receipt, String date, boolean corrected) {
        return new Filing(receipt, "공시 " + receipt, date, "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt, corrected);
    }
}
