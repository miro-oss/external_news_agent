package com.example.be.domain.issues.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.repository.FindingToneSnapshot;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueQueryServiceImplTest {

    private final NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
    private final IssueArticleRepository membershipRepository = mock(IssueArticleRepository.class);
    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final IssueQueryServiceImpl service = new IssueQueryServiceImpl(
            issueRepository, membershipRepository, findingRepository, new IssueToneCalculator());

    @Test
    void loadsNarrowToneSnapshotsAndOnlyFetchesRepresentativeSummaryWhenMissing() {
        NewsIssue issue = issue();
        Article representative = article(10);
        Article member = article(11);
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of(
                membership(representative, IssueArticleRole.REPRESENTATIVE), membership(member, IssueArticleRole.MEMBER)));
        when(findingRepository.findLatestToneByArticleIds(List.of(10L, 11L))).thenReturn(List.of(
                new FindingToneSnapshot(100L, 10L, AnalysisSource.LLM, Sentiment.NEGATIVE,
                        List.of(new FindingKeyPoint("전문가는 부정적으로 평가했다.", List.of(0),
                                "grounded", null, "OPINION", "전문가")), List.of())));
        when(findingRepository.findLatestSummaryByArticleId(10L)).thenReturn(Optional.of("대표 기사 요약"));

        var result = service.getIssue(88L);

        assertEquals("대표 기사 요약", result.getSummary());
        assertEquals(2, result.getArticles().size());
        assertEquals(1, result.getToneDistribution().analyzedArticleCount());
        assertEquals(1, result.getToneDistribution().sampleCount());
        assertEquals(1, result.getToneDistribution().pessimisticCount());
        verify(findingRepository).findLatestToneByArticleIds(List.of(10L, 11L));
        verify(findingRepository).findLatestSummaryByArticleId(10L);
        verify(findingRepository, never()).findLatestByArticleIds(anyList());
    }

    @Test
    void existingIssueSummarySkipsSummaryQuery() {
        NewsIssue issue = NewsIssue.builder().id(88L).title("HBM 이슈").summary("저장된 이슈 요약")
                .status(IssueStatus.EMERGING).topic(Topic.builder().id(1L).name("반도체").build()).build();
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L))
                .thenReturn(List.of(membership(article(10), IssueArticleRole.REPRESENTATIVE)));
        when(findingRepository.findLatestToneByArticleIds(List.of(10L))).thenReturn(List.of());

        assertEquals("저장된 이슈 요약", service.getIssue(88L).getSummary());

        verify(findingRepository, never()).findLatestSummaryByArticleId(10L);
    }

    @Test
    void emptyIssueSkipsFindingQuery() {
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue()));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of());

        var result = service.getIssue(88L);

        assertNull(result.getRepresentativeArticleId());
        assertEquals(0, result.getToneDistribution().sampleCount());
        verify(findingRepository, never()).findLatestToneByArticleIds(anyList());
    }

    @Test
    void largeIssueSplitsLatestFindingQueriesBelowOracleInLimit() {
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue()));
        var memberships = LongStream.rangeClosed(1, 1001)
                .mapToObj(id -> membership(article(id), IssueArticleRole.MEMBER)).toList();
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(memberships);
        when(findingRepository.findLatestToneByArticleIds(anyList())).thenReturn(List.of());

        assertEquals(1001, service.getIssue(88L).getArticles().size());

        verify(findingRepository).findLatestToneByArticleIds(LongStream.rangeClosed(1, 900).boxed().toList());
        verify(findingRepository).findLatestToneByArticleIds(LongStream.rangeClosed(901, 1001).boxed().toList());
    }

    private NewsIssue issue() {
        return NewsIssue.builder().id(88L).title("HBM 이슈").status(IssueStatus.EMERGING)
                .topic(Topic.builder().id(1L).name("반도체").build()).build();
    }

    private Article article(long id) {
        return Article.builder().id(id).title("기사 " + id).sourceName("매체 " + id).build();
    }

    private IssueArticle membership(Article article, IssueArticleRole role) {
        return IssueArticle.builder().article(article).role(role).stance(IssueStance.ADDS)
                .stanceSource(IssueStanceSource.RULE).build();
    }
}
