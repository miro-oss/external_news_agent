package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.issues.entity.ContentGroup;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.ContentGroupRepository;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueRelationRepository;
import com.example.be.domain.issues.repository.IssueStatusHistoryRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueClusterWriterTest {

    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final TopicRepository topicRepository = mock(TopicRepository.class);
    private final ContentGroupRepository contentGroupRepository = mock(ContentGroupRepository.class);
    private final NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final IssueRelationRepository issueRelationRepository = mock(IssueRelationRepository.class);
    private final IssueStatusHistoryRepository statusHistoryRepository = mock(IssueStatusHistoryRepository.class);
    private final IssueClusterWriter writer = new IssueClusterWriter(
            articleRepository,
            topicRepository,
            contentGroupRepository,
            issueRepository,
            issueArticleRepository,
            issueRelationRepository,
            statusHistoryRepository);

    @Test
    void mergesAllArticlesFromLosingContentGroupAndRefreshesFingerprint() {
        Topic topic = topic();
        Article first = article(1L, "대표", topic);
        Article second = article(2L, "현재 멤버", topic);
        Article historical = article(3L, "시간창 밖 멤버", topic);
        ContentGroup winner = group(10L, first, "0000000000000001");
        ContentGroup loser = group(20L, second, "0000000000000002");
        first.assignContentGroup(winner);
        second.assignContentGroup(loser);
        historical.assignContentGroup(loser);
        ClusterPlan plan = new ClusterPlan(
                List.of(new ClusterPlan.ContentGroupAssignment(
                        10L, List.of(20L), 2L, "abcdef0123456789", List.of(1L, 2L))),
                List.of(),
                List.of());

        when(articleRepository.findAllById(any())).thenReturn(List.of(first, second));
        when(contentGroupRepository.findById(10L)).thenReturn(Optional.of(winner));
        when(contentGroupRepository.findAllById(List.of(20L))).thenReturn(List.of(loser));
        when(articleRepository.findByContentGroupIdIn(List.of(20L)))
                .thenReturn(List.of(second, historical));

        writer.write(plan);

        assertSame(winner, first.getContentGroup());
        assertSame(winner, second.getContentGroup());
        assertSame(winner, historical.getContentGroup());
        assertSame(second, winner.getRepresentativeArticle());
        assertEquals("abcdef0123456789", winner.getSimhash());
        verify(contentGroupRepository).deleteAll(List.of(loser));
    }

    @Test
    void mergesIssueMembershipsAndSecondWriteIsIdempotent() {
        Topic topic = topic();
        Article first = article(1L, "기존 대표", topic);
        Article second = article(2L, "새 대표", topic);
        Article historical = article(3L, "시간창 밖 이력", topic);
        NewsIssue winner = issue(100L, topic, "승자");
        NewsIssue loser = issue(200L, topic, "패자");
        IssueArticle firstMembership = membership(11L, winner, first, IssueArticleRole.REPRESENTATIVE);
        IssueArticle secondMembership = membership(12L, loser, second, IssueArticleRole.REPRESENTATIVE);
        IssueArticle historicalMembership = membership(13L, loser, historical, IssueArticleRole.MEMBER);
        ClusterPlan.IssueAssignment merged = assignment(100L, List.of(200L), topic, second);
        ClusterPlan.IssueAssignment repeated = assignment(100L, List.of(), topic, second);

        when(articleRepository.findAllById(any())).thenReturn(List.of(first, second));
        when(topicRepository.findById(topic.getId())).thenReturn(Optional.of(topic));
        when(issueRepository.findById(100L)).thenReturn(Optional.of(winner));
        when(issueRepository.findById(200L)).thenReturn(Optional.of(loser));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(100L))
                .thenReturn(List.of(firstMembership))
                .thenReturn(List.of(firstMembership, secondMembership, historicalMembership));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(200L))
                .thenReturn(List.of(secondMembership, historicalMembership));
        when(issueRelationRepository.existsByFromIssueIdAndToIssueIdAndRelationType(any(), any(), any()))
                .thenReturn(false);

        writer.write(new ClusterPlan(List.of(), List.of(merged), List.of()));
        writer.write(new ClusterPlan(List.of(), List.of(repeated), List.of()));

        assertSame(winner, secondMembership.getIssue());
        assertSame(winner, historicalMembership.getIssue());
        assertEquals(IssueArticleRole.REPRESENTATIVE, secondMembership.getRole());
        assertEquals(IssueArticleRole.MEMBER, firstMembership.getRole());
        assertEquals(IssueStatus.RETRACTED, loser.getStatus());
        assertEquals(0, loser.getArticleCount());
        assertEquals(3, winner.getArticleCount());
        assertEquals(3, winner.getIndependentContentCount());
        verify(statusHistoryRepository, times(1)).save(any());
        verify(issueRelationRepository, times(1)).save(any());
        verify(issueArticleRepository, times(0)).save(any());
    }

    private ClusterPlan.IssueAssignment assignment(Long existingId,
                                                    List<Long> mergedIds,
                                                    Topic topic,
                                                    Article representative) {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-10T10:00:00+09:00");
        return new ClusterPlan.IssueAssignment(
                existingId, mergedIds, topic.getId(), representative.getId(),
                List.of(1L, 2L), List.of("HBM4", "삼성전자"), time, time, 2, 2);
    }

    private Topic topic() {
        return Topic.builder().id(7L).name("HBM").build();
    }

    private Article article(Long id, String title, Topic topic) {
        Source source = Source.builder().id(id).name("매체" + id).build();
        return Article.builder()
                .id(id)
                .title(title)
                .topic(topic)
                .source(source)
                .sourceName(source.getName())
                .fetchStatus(FetchStatus.FULLTEXT)
                .publishedAt(OffsetDateTime.parse("2026-08-10T10:00:00+09:00").plusMinutes(id))
                .build();
    }

    private ContentGroup group(Long id, Article representative, String simhash) {
        return ContentGroup.builder()
                .id(id)
                .representativeArticle(representative)
                .simhash(simhash)
                .createdAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .build();
    }

    private NewsIssue issue(Long id, Topic topic, String title) {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-10T10:00:00+09:00");
        return NewsIssue.builder()
                .id(id)
                .title(title)
                .status(IssueStatus.EMERGING)
                .topic(topic)
                .firstSeenAt(time)
                .lastSeenAt(time)
                .build();
    }

    private IssueArticle membership(Long id,
                                    NewsIssue issue,
                                    Article article,
                                    IssueArticleRole role) {
        return IssueArticle.builder()
                .id(id)
                .issue(issue)
                .article(article)
                .role(role)
                .stance(IssueStance.SUPPORTS)
                .stanceSource(IssueStanceSource.RULE)
                .stanceConfidence(BigDecimal.ONE)
                .joinedAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .build();
    }
}
