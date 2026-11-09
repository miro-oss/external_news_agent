package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.companyinterest.CompanyInterestSelection;
import com.example.be.domain.reports.comparison.ReportComparisonRepository;
import com.example.be.domain.reports.coverage.ReportCoverageCapture;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyReportPersistenceCoverageTest {
    @Test void legacyReservationBoundsRunsBeforeLatestIssueSelectionAndKeepsEveryPreRankingCandidate() {
        var date = LocalDate.of(2026, 11, 2);
        var now = date.plusDays(1).atTime(1, 0);
        var topic = Topic.builder().id(3L).build();
        var earlierRun = CollectionRun.builder().id(1L).startedAt(date.atTime(9, 0)).build();
        var laterRun = CollectionRun.builder().id(2L).startedAt(date.atTime(10, 0)).build();
        var olderIncluded = candidate(11L, earlierRun, topic);
        var newerOutside = candidate(12L, laterRun, topic);
        var anotherIncluded = candidate(13L, earlierRun, topic);
        var sameIssue = NewsIssue.builder().id(20L).topic(topic).articleCount(2).build();
        var otherIssue = NewsIssue.builder().id(21L).topic(topic).articleCount(1).build();
        var findingRepository = mock(FindingRepository.class);
        var memberships = mock(IssueArticleRepository.class);
        when(findingRepository.findDailyReportCandidates(date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(olderIncluded, newerOutside, anotherIncluded));
        var links = List.of(IssueArticle.builder().article(olderIncluded.getArticle()).issue(sameIssue).build(),
                IssueArticle.builder().article(newerOutside.getArticle()).issue(sameIssue).build(),
                IssueArticle.builder().article(anotherIncluded.getArticle()).issue(otherIssue).build());
        when(memberships.findByArticleIds(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return links.stream().filter(link -> ids.contains(link.getArticle().getId())).toList();
        });
        var interests = mock(CompanyInterestSelection.class);
        var selector = new DailyReportSelector(findingRepository, memberships, TopicRelevanceTestSupport.legacyPolicy(), interests, ReportRecencyTestSupport.policy());
        var reports = mock(NewsReportRepository.class);
        when(reports.saveAndFlush(any(NewsReport.class))).thenAnswer(call -> {
            NewsReport report = call.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(report, "id", 9L);
            return report;
        });
        var coverage = mock(ReportCoverageCapture.class);
        var keywords = mock(com.example.be.domain.reports.keyword.ReportKeywordCapture.class);
        var service = new DailyReportPersistenceService(mock(DailyReportJdbcRepository.class), reports,
                mock(CollectionRunRepository.class), mock(ReportComparisonRepository.class), interests, selector,
                coverage, keywords, mock(com.example.be.domain.reports.comparison.ReportComparisonCapture.class));

        var reservation = service.reserve(date, List.of(1L), List.of(11L), now);

        assertEquals(9L, reservation.reportId());
        var captured = ArgumentCaptor.forClass(DailyReportSelector.Prepared.class);
        verify(coverage).captureDaily(any(NewsReport.class), captured.capture(), eq(now));
        assertEquals(Set.of(11L, 13L), captured.getValue().candidates().stream().map(Finding::getId)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, captured.getValue().candidates().size(), "The limit is only for final ranking, not the frozen universe");
        verify(keywords).captureDaily(any(NewsReport.class), same(captured.getValue()), eq(now));
        verify(memberships).findByArticleIds(List.of(11L, 13L));
        verifyNoInteractions(interests);
    }

    private static Finding candidate(long id, CollectionRun run, Topic topic) {
        return Finding.builder().id(id).run(run).analysisSource(AnalysisSource.LLM)
                .article(Article.builder().id(id).topic(topic).body("확보된 원문").fetchStatus(FetchStatus.FULLTEXT).build())
                .keyPoints(List.of(new FindingKeyPoint("근거 있는 주장", List.of(0), "grounded"))).build();
    }

    @Test void persistsOnlyExplicitPreRankingCompanyExclusionsSeparatelyFromSelectedIds() {
        var reports = mock(NewsReportRepository.class);
        var interests = mock(CompanyInterestSelection.class);
        var service = new DailyReportPersistenceService(mock(DailyReportJdbcRepository.class), reports,
                mock(CollectionRunRepository.class), mock(ReportComparisonRepository.class), interests, mock(DailyReportSelector.class),
                mock(ReportCoverageCapture.class), mock(com.example.be.domain.reports.keyword.ReportKeywordCapture.class), mock(com.example.be.domain.reports.comparison.ReportComparisonCapture.class));
        var report = NewsReport.builder().id(9L).reportScope(ReportScope.DAILY).reportStatus(ReportStatus.PENDING).build();
        when(reports.findByIdForUpdate(9L)).thenReturn(Optional.of(report));
        var selected = Finding.builder().id(102L).build();
        var selection = new DailyReportSelector.Selection(List.of(selected), 0, 0, null, List.of(), List.of(101L));
        service.freezeSelection(9L, selection, LocalDateTime.of(2026, 10, 29, 9, 0));
        assertEquals(List.of(102L), report.getReflectedFindingIds());
        assertEquals(List.of(101L), report.getCompanySuppressedFindingIds());
    }
}
