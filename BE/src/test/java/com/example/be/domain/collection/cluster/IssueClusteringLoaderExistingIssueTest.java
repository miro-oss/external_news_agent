package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueClusteringLoaderExistingIssueTest {
    private final CollectionRunArticleRepository observationRepository = mock(CollectionRunArticleRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueClusteringLoader loader = new IssueClusteringLoader(
            observationRepository, issueArticleRepository, new IssueClusteringProperties());
    private final Topic topic = Topic.builder().id(7L).name("반도체").build();
    private final Source source = Source.builder().id(9L).name("신문").reliabilityScore(new BigDecimal("0.8")).build();

    @Test
    void reobservedOldIssueRetainsItsFullTextRepresentativeOutsideTheRecentWindow() {
        var priorTime = OffsetDateTime.parse("2027-09-01T09:00:00+09:00");
        var fullText = article(101L, FetchStatus.FULLTEXT, "본문을 확보한 기사다.", priorTime);
        var metadata = article(102L, FetchStatus.METADATA_ONLY, null);
        var issue = NewsIssue.builder().id(74L).topic(topic).lastSeenAt(priorTime).build();
        var representative = membership(issue, fullText, IssueArticleRole.REPRESENTATIVE);
        var observedMember = membership(issue, metadata, IssueArticleRole.MEMBER);
        current(List.of(metadata), List.of(observedMember));
        when(issueArticleRepository.findRecentByTopicIds(eq(Set.of(7L)), any())).thenReturn(List.of());
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(74L))
                .thenReturn(List.of(representative, observedMember));

        List<ClusterArticle> snapshot = loader.load(42L);

        assertEquals(List.of(101L, 102L), snapshot.stream().map(ClusterArticle::articleId).toList());
        assertFalse(snapshot.getFirst().observedInRun());
        assertTrue(snapshot.getLast().observedInRun());
        assertTrue(snapshot.getFirst().hasFullText());
        var plan = new IssueClusterer(new IssueClusteringProperties(), new BreakingNewsDetector()).cluster(snapshot);
        assertEquals(1, plan.issues().size());
        assertEquals(74L, plan.issues().getFirst().existingIssueId());
        assertEquals(101L, plan.issues().getFirst().representativeArticleId());
        assertEquals(List.of(101L, 102L), plan.issues().getFirst().articleIds());
        verify(issueArticleRepository).findByIssueIdOrderByJoinedAtAsc(74L);
    }

    @Test
    void severalReobservedMembersLoadTheirMissingIssueOnlyOnceAndKeepCurrentValues() {
        var metadata = article(102L, FetchStatus.METADATA_ONLY, null);
        var updated = article(103L, FetchStatus.FULLTEXT, "이번에 확보한 실제 본문이다.");
        var staleCopy = article(103L, FetchStatus.METADATA_ONLY, null);
        var issue = NewsIssue.builder().id(74L).topic(topic).build();
        var first = membership(issue, metadata, IssueArticleRole.REPRESENTATIVE);
        var stale = membership(issue, staleCopy, IssueArticleRole.MEMBER);
        current(List.of(metadata, updated), List.of(first, stale));
        when(issueArticleRepository.findRecentByTopicIds(eq(Set.of(7L)), any())).thenReturn(List.of());
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(74L)).thenReturn(List.of(first, stale));

        List<ClusterArticle> snapshot = loader.load(42L);

        assertEquals(2, snapshot.size());
        assertTrue(snapshot.stream().allMatch(ClusterArticle::observedInRun));
        assertEquals(updated.getBody(), snapshot.stream().filter(article -> article.articleId() == 103L)
                .findFirst().orElseThrow().body());
        verify(issueArticleRepository, times(1)).findByIssueIdOrderByJoinedAtAsc(74L);
    }

    @Test
    void recentIssuesAlreadyContainTheirMembersAndNeedNoExtraLookup() {
        var fullText = article(101L, FetchStatus.FULLTEXT, "본문을 확보한 기사다.");
        var metadata = article(102L, FetchStatus.METADATA_ONLY, null);
        var issue = NewsIssue.builder().id(74L).topic(topic).build();
        var representative = membership(issue, fullText, IssueArticleRole.REPRESENTATIVE);
        var member = membership(issue, metadata, IssueArticleRole.MEMBER);
        current(List.of(metadata), List.of(member));
        when(issueArticleRepository.findRecentByTopicIds(eq(Set.of(7L)), any()))
                .thenReturn(List.of(representative, member));

        assertEquals(2, loader.load(42L).size());

        verify(issueArticleRepository, never()).findByIssueIdOrderByJoinedAtAsc(anyLong());
    }

    @Test
    void currentArticlesOtherTopicMembershipDoesNotExpandHistoricalLookup() {
        var observed = article(102L, FetchStatus.METADATA_ONLY, null);
        var otherTopic = Topic.builder().id(8L).name("제조").build();
        var localIssue = NewsIssue.builder().id(74L).topic(topic).build();
        var otherIssue = NewsIssue.builder().id(99L).topic(otherTopic).build();
        var localMembership = membership(localIssue, observed, IssueArticleRole.MEMBER);
        current(List.of(observed), List.of(localMembership,
                membership(otherIssue, observed, IssueArticleRole.REPRESENTATIVE)));
        when(issueArticleRepository.findRecentByTopicIds(eq(Set.of(7L)), any())).thenReturn(List.of());
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(74L)).thenReturn(List.of(localMembership));

        List<ClusterArticle> snapshot = loader.load(42L);

        assertEquals(1, snapshot.size());
        assertEquals(7L, snapshot.getFirst().topicId());
        assertEquals(74L, snapshot.getFirst().existingIssueId());
        verify(issueArticleRepository).findByIssueIdOrderByJoinedAtAsc(74L);
        verify(issueArticleRepository, never()).findByIssueIdOrderByJoinedAtAsc(99L);
    }

    @Test
    void newArticlesDoNotTriggerAnUnboundedHistoricalIssueLookup() {
        var observed = article(102L, FetchStatus.METADATA_ONLY, null);
        current(List.of(observed), List.of());
        when(issueArticleRepository.findRecentByTopicIds(eq(Set.of(7L)), any())).thenReturn(List.of());

        assertEquals(1, loader.load(42L).size());

        verify(issueArticleRepository, never()).findByIssueIdOrderByJoinedAtAsc(anyLong());
    }

    private void current(List<Article> articles, List<IssueArticle> memberships) {
        when(observationRepository.findClusterTargetsByRunId(42L)).thenReturn(articles.stream()
                .map(article -> CollectionRunArticle.builder().article(article).topic(topic)
                        .observedAt(LocalDateTime.of(2027, 10, 12, 12, 0)).build()).toList());
        when(issueArticleRepository.findByArticleIds(any())).thenReturn(memberships);
    }

    private Article article(Long id, FetchStatus status, String body) {
        return article(id, status, body, OffsetDateTime.parse("2027-10-12T09:00:00+09:00"));
    }

    private Article article(Long id, FetchStatus status, String body, OffsetDateTime publishedAt) {
        return Article.builder().id(id).topic(topic).source(source)
                .title("새빛전자 메모리 설비 확장 발표").summary("설비 확장 계획이다.")
                .body(body).fetchStatus(status)
                .publishedAt(publishedAt)
                .collectedAt(LocalDateTime.of(2027, 10, 12, 10, 0)).build();
    }

    private IssueArticle membership(NewsIssue issue, Article article, IssueArticleRole role) {
        return IssueArticle.builder().issue(issue).article(article).role(role).build();
    }
}
