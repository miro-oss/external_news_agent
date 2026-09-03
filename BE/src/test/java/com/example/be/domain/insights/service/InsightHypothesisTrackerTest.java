package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingEntities;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.cluster.IssueClusteringProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.insights.entity.NewsInsight;
import com.example.be.domain.insights.repository.NewsInsightRepository;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InsightHypothesisTrackerTest {

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final NewsInsightRepository insightRepository = mock(NewsInsightRepository.class);
    private final NewsWatchRepository watchRepository = mock(NewsWatchRepository.class);
    private final NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
    private final IssueClusteringProperties clusteringProperties = new IssueClusteringProperties();
    private final AgentProperties agentProperties = new AgentProperties();
    private final InsightHypothesisTracker tracker = new InsightHypothesisTracker(
            findingRepository,
            insightRepository,
            watchRepository,
            issueRepository,
            clusteringProperties,
            agentProperties);

    private Topic topic;
    private NewsIssue issue;

    @BeforeEach
    void setUp() {
        topic = Topic.builder().id(7L).build();
        issue = NewsIssue.builder().id(88L).topic(topic).build();
        when(watchRepository.findActiveIssueIdsByWatchType(
                eq(WatchType.HYPOTHESIS), any(LocalDateTime.class)))
                .thenReturn(List.of(88L));
        when(issueRepository.findAllById(List.of(88L))).thenReturn(List.of(issue));
    }

    @Test
    void linksNewArticleWhenTopicMatchesAndTwoEntitiesOverlap() {
        LocalDateTime analyzedAt = LocalDateTime.now().minusHours(1);
        NewsInsight insight = insight(List.of("삼성전자", "HBM4"), List.of(10L));
        Finding finding = finding(
                20L,
                topic,
                new FindingEntities(List.of("삼성전자"), List.of("HBM4"), List.of()),
                analyzedAt);
        stubCandidates(insight, finding);

        int linked = tracker.track(42L);

        assertEquals(1, linked);
        assertEquals(List.of(20L), insight.getRelatedArticleIds());
    }

    @Test
    void ignoresInputArticleAndSingleEntityOverlap() {
        LocalDateTime analyzedAt = LocalDateTime.now().minusHours(1);
        NewsInsight inputArticleInsight = insight(List.of("삼성전자", "HBM4"), List.of(20L));
        NewsInsight weakOverlapInsight = insight(List.of("삼성전자", "EUV"), List.of(10L));
        Finding finding = finding(
                20L,
                topic,
                new FindingEntities(List.of("삼성전자"), List.of("HBM4"), List.of()),
                analyzedAt);
        when(insightRepository
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
                        eq(AgentTargetType.ISSUE), eq(List.of(88L)), any(LocalDateTime.class)))
                .thenReturn(List.of(inputArticleInsight, weakOverlapInsight));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));

        int linked = tracker.track(42L);

        assertEquals(0, linked);
        assertEquals(List.of(), inputArticleInsight.getRelatedArticleIds());
        assertEquals(List.of(), weakOverlapInsight.getRelatedArticleIds());
    }

    @Test
    void ignoresFindingFromDifferentTopic() {
        NewsInsight insight = insight(List.of("삼성전자", "HBM4"), List.of(10L));
        Finding finding = finding(
                20L,
                Topic.builder().id(8L).build(),
                new FindingEntities(List.of("삼성전자"), List.of("HBM4"), List.of()),
                LocalDateTime.now().minusHours(1));
        stubCandidates(insight, finding);

        int linked = tracker.track(42L);

        assertEquals(0, linked);
        assertEquals(List.of(), insight.getRelatedArticleIds());
    }

    private void stubCandidates(NewsInsight insight, Finding finding) {
        when(insightRepository
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
                        eq(AgentTargetType.ISSUE), eq(List.of(88L)), any(LocalDateTime.class)))
                .thenReturn(List.of(insight));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));
    }

    private NewsInsight insight(List<String> entities, List<Long> inputArticleIds) {
        return NewsInsight.builder()
                .targetType(AgentTargetType.ISSUE)
                .targetId(88L)
                .watchEntities(entities)
                .inputArticleIds(inputArticleIds)
                .relatedArticleIds(List.of())
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    private Finding finding(Long articleId,
                            Topic findingTopic,
                            FindingEntities entities,
                            LocalDateTime analyzedAt) {
        return Finding.builder()
                .article(Article.builder().id(articleId).topic(findingTopic).build())
                .analysisSource(AnalysisSource.LLM)
                .entities(entities)
                .analyzedAt(analyzedAt)
                .build();
    }
}
