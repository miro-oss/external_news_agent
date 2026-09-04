package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyReportSelectorTest {
    private final FindingRepository findings = mock(FindingRepository.class);
    private final IssueArticleRepository memberships = mock(IssueArticleRepository.class);
    private final DailyReportSelector selector = new DailyReportSelector(findings, memberships);
    private final Topic topic = Topic.builder().id(1L).build();

    @Test
    void countsLatestIssueExclusionsBeforeTopNWithoutCountingLegacyOrOldFindings() {
        Finding supported = finding(1, 1, "grounded");
        Finding withdrawn = finding(2, 2, "ungrounded");
        Finding stub = finding(3, 2, "grounded");
        org.springframework.test.util.ReflectionTestUtils.setField(stub, "analysisSource", AnalysisSource.STUB);
        Finding selected = finding(4, 1, "grounded");
        Finding legacy = finding(5, 1, "ungrounded");
        NewsIssue changed = issue(10, 100);
        var result = selector.selectWithStats(List.of(supported, withdrawn, stub, selected, legacy),
                List.of(link(supported, changed), link(withdrawn, changed), link(stub, issue(20, 90)),
                        link(selected, issue(30, 80))), 1);
        assertEquals(List.of(selected), result.findings());
        assertEquals(new ReportSourceStats(118, 1, 2, 0, 1, 1),
                result.applyTo(new ReportSourceStats(118, 1, 2, 0, 0, 0)));
    }

    @Test
    void selectsLatestPerIssueBeforeRankingAndDoesNotResurrectUnsupportedClaims() {
        Finding old = finding(1, 1, "grounded");
        Finding latest = finding(2, 2, "weak");
        Finding high = finding(3, 1, "grounded");
        Finding rejected = finding(4, 2, "ungrounded");
        Finding previouslySupported = finding(5, 1, "grounded");
        Finding legacy = finding(6, 1, "grounded");
        NewsIssue repeated = issue(10, 50);
        NewsIssue highest = issue(20, 90);
        NewsIssue withdrawn = issue(30, 100);
        List<IssueArticle> links = List.of(link(old, repeated), link(latest, repeated),
                link(high, highest), link(rejected, withdrawn), link(previouslySupported, withdrawn));

        assertEquals(List.of(3L, 2L), selector.select(
                List.of(old, high, rejected, previouslySupported, legacy, latest), links, 10)
                .stream().map(Finding::getId).toList());
        assertEquals(List.of(3L), selector.select(List.of(old, high, latest), links, 1)
                .stream().map(Finding::getId).toList());
    }

    @Test
    void excludesOtherTopicAndMergedIssuesAndBreaksImportanceTiesByIssueId() {
        Finding first = finding(1, 1, "grounded");
        Finding second = finding(2, 1, "grounded");
        NewsIssue otherTopic = NewsIssue.builder().id(99L).articleCount(1)
                .topic(Topic.builder().id(2L).build()).importanceScore(BigDecimal.TEN).build();
        NewsIssue merged = NewsIssue.builder().id(98L).articleCount(0).topic(topic).build();
        assertTrue(selector.select(List.of(first), List.of(link(first, otherTopic), link(first, merged)), 10).isEmpty());
        assertEquals(List.of(2L, 1L), selector.select(List.of(first, second),
                        List.of(link(first, issue(20, 50)), link(second, issue(10, 50))), 10)
                .stream().map(Finding::getId).toList());
    }

    @Test
    void queriesHalfOpenKoreanCalendarDay() {
        LocalDate date = LocalDate.of(2026, 9, 3);
        assertTrue(selector.select(date, 10).isEmpty());
        verify(findings).findDailyReportCandidates(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        verifyNoInteractions(memberships);
        assertThrows(IllegalArgumentException.class, () -> selector.select(List.of(), List.of(), 51));
    }

    private NewsIssue issue(long id, int importance) {
        return NewsIssue.builder().id(id).topic(topic).articleCount(1)
                .importanceScore(BigDecimal.valueOf(importance)).build();
    }

    private IssueArticle link(Finding finding, NewsIssue issue) {
        return IssueArticle.builder().article(finding.getArticle()).issue(issue).build();
    }

    private Finding finding(long id, int hour, String groundedness) {
        return Finding.builder().id(id).analysisSource(AnalysisSource.LLM)
                .run(CollectionRun.builder().id((long) hour)
                        .startedAt(LocalDate.of(2026, 9, 3).atTime(hour, 0)).build())
                .article(Article.builder().id(id).topic(topic).build())
                .keyPoints(List.of(new FindingKeyPoint("검증 대상 주장", List.of(0), groundedness)))
                .build();
    }
}
