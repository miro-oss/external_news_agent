package com.example.be.domain.issues.service;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.repository.FindingToneSnapshot;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
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
        assertEquals(10L, result.getRepresentativeArticleId());
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

    @Test
    void hidesBodylessLinksAndDependentReferencesWhilePreservingCollectedCountsAndMemberships() {
        IssueCrossSource crossSource = new IssueCrossSource(List.of("저장된 합의"), List.of(
                new IssueCrossSource.SoleSource(10L, "숨긴 기사 관측"),
                new IssueCrossSource.SoleSource(11L, "본문 기사 관측"),
                new IssueCrossSource.SoleSource(99L, "멤버십 밖 참조")), List.of(
                new IssueCrossSource.Conflict(List.of(10L, 11L), "일부 기사만 숨겨진 충돌"),
                new IssueCrossSource.Conflict(List.of(11L, 12L), "본문 기사 간 충돌"),
                new IssueCrossSource.Conflict(List.of(11L, 99L), "멤버십 밖 충돌")), List.of("공식 입장"));
        NewsIssue issue = NewsIssue.builder().id(88L).title("반도체 이슈").status(IssueStatus.EMERGING)
                .topic(Topic.builder().id(1L).name("반도체").build())
                .articleCount(6).publisherCount(5).independentContentCount(4).crossSource(crossSource).build();
        IssueArticle hiddenRepresentative = membership(article(10L, FetchStatus.METADATA_ONLY, null, null),
                IssueArticleRole.REPRESENTATIVE);
        IssueArticle undated = membership(article(11L), IssueArticleRole.MEMBER);
        IssueArticle dated = membership(article(12L, FetchStatus.FULLTEXT, "실제 기사 본문",
                OffsetDateTime.parse("2026-09-12T10:00:00+09:00")), IssueArticleRole.MEMBER);
        var memberships = List.of(hiddenRepresentative, undated, dated,
                membership(article(13L, FetchStatus.FETCH_FAILED, "과거에 남은 본문", null), IssueArticleRole.MEMBER),
                membership(article(14L, FetchStatus.FULLTEXT, " \n\t ", null), IssueArticleRole.MEMBER),
                membership(article(15L, FetchStatus.FULLTEXT, null, null), IssueArticleRole.MEMBER));
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(memberships);
        when(findingRepository.findLatestSummaryByArticleId(12L)).thenReturn(Optional.of("본문 기사 분석 요약"));
        when(findingRepository.findLatestToneByArticleIds(List.of(10L, 11L, 12L, 13L, 14L, 15L)))
                .thenReturn(List.of(new FindingToneSnapshot(100L, 10L, AnalysisSource.LLM, Sentiment.NEGATIVE,
                        List.of(new FindingKeyPoint("전문가는 부정적으로 평가했다.", List.of(0),
                                "grounded", null, "OPINION", "전문가")), List.of())));

        var result = service.getIssue(88L);

        assertEquals(List.of(11L, 12L), result.getArticles().stream().map(value -> value.getId()).toList());
        assertEquals(12L, result.getRepresentativeArticleId());
        assertEquals(List.of("MEMBER", "REPRESENTATIVE"), result.getArticles().stream()
                .map(value -> value.getRole()).toList());
        assertEquals("본문 기사 분석 요약", result.getSummary());
        assertEquals(6, result.getArticleCount());
        assertEquals(5, result.getPublisherCount());
        assertEquals(4, result.getIndependentContentCount());
        assertEquals(1, result.getToneDistribution().analyzedArticleCount());
        assertEquals(1, result.getToneDistribution().sampleCount());
        assertEquals(List.of(new IssueCrossSource.SoleSource(11L, "본문 기사 관측")), result.getCrossSource().soleSource());
        assertEquals(List.of(new IssueCrossSource.Conflict(List.of(11L, 12L), "본문 기사 간 충돌")),
                result.getCrossSource().conflicts());
        assertEquals(List.of("저장된 합의"), result.getCrossSource().consensus());
        assertEquals(List.of("공식 입장"), result.getCrossSource().missingStakeholders());
        assertEquals(IssueArticleRole.REPRESENTATIVE, hiddenRepresentative.getRole());
        assertEquals(IssueArticleRole.MEMBER, dated.getRole());
        assertEquals(3, issue.getCrossSource().soleSource().size());
        assertEquals(3, issue.getCrossSource().conflicts().size());
        verify(findingRepository, never()).findLatestSummaryByArticleId(10L);
    }

    @Test
    void anIssueWithOnlyHiddenArticlesReturnsNoArticleLinksOrRepresentative() {
        NewsIssue issue = NewsIssue.builder().id(88L).title("수집된 이슈").status(IssueStatus.EMERGING)
                .topic(Topic.builder().id(1L).name("반도체").build())
                .articleCount(2).publisherCount(2).independentContentCount(2)
                .crossSource(new IssueCrossSource(List.of(),
                        List.of(new IssueCrossSource.SoleSource(10L, "저장된 관측")),
                        List.of(new IssueCrossSource.Conflict(List.of(10L, 11L), "저장된 충돌")), List.of()))
                .build();
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of(
                membership(article(10L, FetchStatus.ROBOTS_DISALLOWED, null, null), IssueArticleRole.REPRESENTATIVE),
                membership(article(11L, FetchStatus.FULLTEXT, "   ", null), IssueArticleRole.MEMBER)));

        var result = service.getIssue(88L);

        assertTrue(result.getArticles().isEmpty());
        assertNull(result.getRepresentativeArticleId());
        assertNull(result.getSummary());
        assertEquals(2, result.getArticleCount());
        assertEquals(2, result.getPublisherCount());
        assertEquals(2, result.getIndependentContentCount());
        assertTrue(result.getCrossSource().soleSource().isEmpty());
        assertTrue(result.getCrossSource().conflicts().isEmpty());
        verify(findingRepository, never()).findLatestSummaryByArticleId(anyLong());
        verify(findingRepository).findLatestToneByArticleIds(List.of(10L, 11L));
    }

    @Test
    void fallbackRepresentativeUsesEarliestKnownPublicationAndThenArticleId() {
        var earlier = OffsetDateTime.parse("2026-09-12T09:00:00+09:00");
        var memberships = List.of(
                membership(article(5L), IssueArticleRole.MEMBER),
                membership(article(14L, FetchStatus.FULLTEXT, "본문", earlier), IssueArticleRole.MEMBER),
                membership(article(13L, FetchStatus.FULLTEXT, "본문", earlier), IssueArticleRole.MEMBER),
                membership(article(12L, FetchStatus.FULLTEXT, "본문", earlier.plusHours(1)), IssueArticleRole.MEMBER));
        when(issueRepository.findById(88L)).thenReturn(Optional.of(issue()));
        when(membershipRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(memberships);

        assertEquals(13L, service.getIssue(88L).getRepresentativeArticleId());
        verify(findingRepository).findLatestSummaryByArticleId(13L);
    }

    private NewsIssue issue() {
        return NewsIssue.builder().id(88L).title("HBM 이슈").status(IssueStatus.EMERGING)
                .topic(Topic.builder().id(1L).name("반도체").build()).build();
    }

    private Article article(long id) {
        return article(id, FetchStatus.FULLTEXT, "기사 " + id + " 본문", null);
    }

    private Article article(long id, FetchStatus fetchStatus, String body, OffsetDateTime publishedAt) {
        return Article.builder().id(id).title("기사 " + id).sourceName("매체 " + id)
                .canonicalUrl("https://example.com/articles/" + id).fetchStatus(fetchStatus)
                .body(body).publishedAt(publishedAt).build();
    }

    private IssueArticle membership(Article article, IssueArticleRole role) {
        return IssueArticle.builder().article(article).role(role).stance(IssueStance.ADDS)
                .stanceSource(IssueStanceSource.RULE).build();
    }
}
