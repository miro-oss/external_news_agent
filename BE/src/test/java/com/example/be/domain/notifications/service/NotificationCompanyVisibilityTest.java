package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NotificationCompanyVisibilityTest {
    private final Company retained = new Company("00000001", "허용 기업", null);
    private final Company excluded = new Company("00000002", "제외 기업", null);
    private final List<CompanyAttachment> companies = List.of(attachment(retained), attachment(excluded));

    @Test
    void unchangedNoNewsReportPreservesExistingCompanyAvailability() {
        assertEquals(companies, NotificationCompanyVisibility.visible(report(), view(List.of(), false, Set.of()), companies));
    }

    @Test
    void filteredCompaniesUseFrozenObservedTopicsInsteadOfTheArticlesFirstTopic() {
        var report = report();
        report.freezeReportCoverageSnapshot(new ReportCoverageSnapshot("v1", null, "수집 기준", List.of(),
                List.of(new ReportCoverageSnapshot.Binding(1L, 100L, 10L),
                        new ReportCoverageSnapshot.Binding(2L, 200L, 20L))));
        var finding = Finding.builder().id(1L).article(Article.builder().id(11L)
                .topic(Topic.builder().id(20L).build()).build()).build();
        assertEquals(List.of(companies.getFirst()), NotificationCompanyVisibility.visible(report,
                view(List.of(finding), true, Set.of(2L)), companies));
        assertTrue(NotificationCompanyVisibility.visible(report, view(List.of(finding), true, Set.of(1L)), companies).isEmpty());
    }

    @Test
    void fullTextFilteringWithoutAnyRetainedTopicOmitsCompanies() {
        assertTrue(NotificationCompanyVisibility.visible(report(), view(List.of(), true, Set.of()), companies).isEmpty());
    }

    @Test
    void legacyFilteredRunDoesNotMistakeFirstArticleTopicForReportObservation() {
        var finding = Finding.builder().id(1L).article(Article.builder().id(11L)
                .topic(Topic.builder().id(10L).build()).build()).build();
        assertTrue(NotificationCompanyVisibility.visible(report(), view(List.of(finding), true, Set.of()), companies).isEmpty());
    }

    @Test
    void frozenCompanyExclusionsApplyEvenWithoutAFilteredViewFlag() {
        var report = report();
        report.freezeCompanySelection(List.of(1L), List.of(2L), null);
        report.freezeReportCoverageSnapshot(new ReportCoverageSnapshot("v1", null, "수집 기준", List.of(),
                List.of(new ReportCoverageSnapshot.Binding(1L, 100L, 10L),
                        new ReportCoverageSnapshot.Binding(2L, 200L, 20L))));
        var kept = Finding.builder().id(1L).build();
        var suppressed = Finding.builder().id(2L).build();
        assertEquals(List.of(companies.getFirst()), NotificationCompanyVisibility.visible(report,
                view(List.of(kept, suppressed), false, Set.of()), companies));
    }

    @Test
    void filteredLegacyTopicWeeklyUsesItsPersistedTopicScope() {
        var report = NewsReport.builder().id(17L).reportScope(ReportScope.WEEKLY).topicId(10L)
                .topicDartCorpCode(retained.corpCode()).topicDartCorpName(retained.corpName()).build();
        assertEquals(List.of(companies.getFirst()), NotificationCompanyVisibility.visible(report,
                view(List.of(Finding.builder().id(1L).build()), true, Set.of()), companies));
        assertTrue(NotificationCompanyVisibility.visible(report, view(List.of(), true, Set.of()), companies).isEmpty());
    }

    private NewsReport report() {
        return NewsReport.builder().id(17L).reportScope(ReportScope.RUN).reportStatus(ReportStatus.PENDING).collectionContexts(List.of(
                new ReportCollectionContext(42L, List.of(topic(10L, retained), topic(20L, excluded))))).build();
    }

    private CollectionTopicSnapshot topic(long id, Company company) {
        return new CollectionTopicSnapshot(id, "주제", null, List.of(), List.of(), List.of(), 10, 60, company);
    }

    private ReportEventFeedbackProjection.View view(List<Finding> findings, boolean filtered, Set<Long> excludedIds) {
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", null), false, excludedIds, filtered);
    }

    private CompanyAttachment attachment(Company company) { return new CompanyAttachment(company, "PENDING", null, null); }
}
