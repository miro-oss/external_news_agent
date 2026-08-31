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
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.domain.issues.repository.ContentGroupRepository;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueRelationRepository;
import com.example.be.domain.issues.repository.IssueStatusHistoryRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private final NewsWatchRepository watchRepository = mock(NewsWatchRepository.class);
    private final WatchAlertOutboxRepository watchAlertOutboxRepository = mock(WatchAlertOutboxRepository.class);
    private final IssueClusterWriter writer = new IssueClusterWriter(
            articleRepository,
            topicRepository,
            contentGroupRepository,
            issueRepository,
            issueArticleRepository,
            issueRelationRepository,
            statusHistoryRepository,
            watchRepository,
            watchAlertOutboxRepository,
            new BreakingNewsDetector());

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

    @Test
    void explicitBreakingArticleCreatesBreakingMembershipAndWatchWithoutInitialAlert() {
        Topic topic = topic();
        Article breaking = article(1L, "[속보] 삼성전자 HBM4 증설", topic);
        NewsIssue created = issue(100L, topic, "임시 제목");
        OffsetDateTime time = breaking.getPublishedAt();
        ClusterPlan.IssueAssignment assignment = new ClusterPlan.IssueAssignment(
                null, List.of(), topic.getId(), breaking.getId(), List.of(breaking.getId()),
                List.of("HBM4", "삼성전자"), time, time, 1, 1);
        when(articleRepository.findAllById(any())).thenReturn(List.of(breaking));
        when(topicRepository.findById(topic.getId())).thenReturn(Optional.of(topic));
        when(issueRepository.save(any())).thenReturn(created);
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(100L)).thenReturn(List.of());
        when(issueArticleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(watchRepository.findByIssueIdAndWatchType(100L, WatchType.BREAKING))
                .thenReturn(Optional.empty());

        writer.write(new ClusterPlan(List.of(), List.of(assignment), List.of()));

        ArgumentCaptor<IssueArticle> membershipCaptor = ArgumentCaptor.forClass(IssueArticle.class);
        ArgumentCaptor<NewsWatch> watchCaptor = ArgumentCaptor.forClass(NewsWatch.class);
        verify(issueArticleRepository).save(membershipCaptor.capture());
        verify(watchRepository).save(watchCaptor.capture());
        assertEquals(IssueArticleRole.BREAKING, membershipCaptor.getValue().getRole());
        assertEquals("삼성전자 HBM4 증설", created.getTitle());
        assertEquals(WatchType.BREAKING, watchCaptor.getValue().getWatchType());
        assertTrue(watchCaptor.getValue().getExpiresAt()
                .isAfter(LocalDateTime.now(ApiTimeZone.ZONE).plusHours(47)));
    }

    @Test
    void recentShortFullTextWithoutMarkerRemainsRepresentative() {
        Topic topic = topic();
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-31T10:00:00+09:00");
        Source source = Source.builder().id(1L).name("전자신문").build();
        Article breaking = Article.builder()
                .id(1L).title("삼성전자 HBM4 증설").body("짧은 속보 본문")
                .topic(topic).source(source).sourceName(source.getName())
                .fetchStatus(FetchStatus.FULLTEXT).publishedAt(publishedAt)
                .collectedAt(LocalDateTime.of(2026, 8, 31, 11, 0)).build();
        NewsIssue created = issue(100L, topic, "임시 제목");
        ClusterPlan.IssueAssignment assignment = new ClusterPlan.IssueAssignment(
                null, List.of(), topic.getId(), breaking.getId(), List.of(breaking.getId()),
                List.of("HBM4", "삼성전자"), publishedAt, publishedAt, 1, 1);
        when(articleRepository.findAllById(any())).thenReturn(List.of(breaking));
        when(topicRepository.findById(topic.getId())).thenReturn(Optional.of(topic));
        when(issueRepository.save(any())).thenReturn(created);
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(100L)).thenReturn(List.of());
        when(issueArticleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        writer.write(new ClusterPlan(List.of(), List.of(assignment), List.of()));

        ArgumentCaptor<IssueArticle> membershipCaptor = ArgumentCaptor.forClass(IssueArticle.class);
        verify(issueArticleRepository).save(membershipCaptor.capture());
        assertEquals(IssueArticleRole.REPRESENTATIVE, membershipCaptor.getValue().getRole());
        verify(watchRepository, never()).save(any());
    }

    @Test
    void newFollowUpClaimsExistingWatchForThirtyMinuteCooldown() {
        Topic topic = topic();
        Article breaking = article(1L, "[속보] 삼성전자 HBM4 증설", topic);
        Article followUp = article(2L, "삼성전자 HBM4 증설 상세 발표", topic);
        NewsIssue issue = issue(100L, topic, "삼성전자 HBM4 증설");
        IssueArticle breakingMembership = membership(
                11L, issue, breaking, IssueArticleRole.BREAKING);
        NewsWatch watch = NewsWatch.builder()
                .id(50L)
                .watchType(WatchType.BREAKING)
                .issue(issue)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .active(true)
                .build();
        OffsetDateTime time = breaking.getPublishedAt();
        ClusterPlan.IssueAssignment assignment = new ClusterPlan.IssueAssignment(
                100L, List.of(), topic.getId(), followUp.getId(), List.of(1L, 2L),
                List.of("HBM4", "삼성전자"), time, followUp.getPublishedAt(), 2, 2);
        when(articleRepository.findAllById(any())).thenReturn(List.of(breaking, followUp));
        when(topicRepository.findById(topic.getId())).thenReturn(Optional.of(topic));
        when(issueRepository.findById(100L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(100L))
                .thenReturn(List.of(breakingMembership));
        when(issueArticleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(watchRepository.findEligibleBreakingForNotification(any(), any())).thenReturn(List.of(watch));
        when(watchRepository.findByIssueIdAndWatchType(100L, WatchType.BREAKING))
                .thenReturn(Optional.of(watch));

        writer.write(new ClusterPlan(List.of(), List.of(assignment), List.of()));

        ArgumentCaptor<IssueArticle> membershipCaptor = ArgumentCaptor.forClass(IssueArticle.class);
        ArgumentCaptor<WatchAlertOutbox> alertCaptor = ArgumentCaptor.forClass(WatchAlertOutbox.class);
        verify(issueArticleRepository).save(membershipCaptor.capture());
        verify(watchAlertOutboxRepository).save(alertCaptor.capture());
        assertEquals(1, alertCaptor.getValue().getFollowUpCount());
        assertEquals("삼성전자 HBM4 증설", alertCaptor.getValue().getIssueTitle());
        assertEquals(IssueArticleRole.REPRESENTATIVE, membershipCaptor.getValue().getRole());
        assertTrue(watch.getCooldownUntil()
                .isAfter(LocalDateTime.now(ApiTimeZone.ZONE).plusMinutes(29)));
        verify(watchRepository, never()).save(any());
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
