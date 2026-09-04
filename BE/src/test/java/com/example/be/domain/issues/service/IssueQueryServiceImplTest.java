package com.example.be.domain.issues.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
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
    void loadsLatestOwnFindingsOnceAndUsesThemForToneAndSummary() {
        NewsIssue issue = issue();
        Article representative = article(10);
        Article member = article(11);
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of(
                membership(representative, IssueArticleRole.REPRESENTATIVE), membership(member, IssueArticleRole.MEMBER)));
        when(findingRepository.findLatestByArticleIds(List.of(10L, 11L))).thenReturn(List.of(
                Finding.builder().id(100L).article(representative).summary("대표 기사 요약")
                        .sentiment(Sentiment.NEGATIVE).analysisSource(AnalysisSource.LLM)
                        .keyPoints(List.of(new FindingKeyPoint("전문가는 부정적으로 평가했다.", List.of(0),
                                "grounded", null, "OPINION", "전문가"))).build()));

        var result = service.getIssue(88L);

        assertEquals("대표 기사 요약", result.getSummary());
        assertEquals(2, result.getArticles().size());
        assertEquals(1, result.getToneDistribution().analyzedArticleCount());
        assertEquals(1, result.getToneDistribution().sampleCount());
        assertEquals(1, result.getToneDistribution().pessimisticCount());
        verify(findingRepository).findLatestByArticleIds(List.of(10L, 11L));
        verify(findingRepository, never()).findFirstByArticleIdOrderByIdDesc(10L);
    }

    @Test
    void emptyIssueSkipsFindingQuery() {
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue()));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of());

        var result = service.getIssue(88L);

        assertNull(result.getRepresentativeArticleId());
        assertEquals(0, result.getToneDistribution().sampleCount());
        verify(findingRepository, never()).findLatestByArticleIds(anyList());
    }

    @Test
    void largeIssueSplitsLatestFindingQueriesBelowOracleInLimit() {
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue()));
        var memberships = LongStream.rangeClosed(1, 1001)
                .mapToObj(id -> membership(article(id), IssueArticleRole.MEMBER)).toList();
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(memberships);
        when(findingRepository.findLatestByArticleIds(anyList())).thenReturn(List.of());

        assertEquals(1001, service.getIssue(88L).getArticles().size());

        verify(findingRepository).findLatestByArticleIds(LongStream.rangeClosed(1, 900).boxed().toList());
        verify(findingRepository).findLatestByArticleIds(LongStream.rangeClosed(901, 1001).boxed().toList());
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
