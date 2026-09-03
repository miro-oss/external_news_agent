package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Audience;
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
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
            agentProperties,
            new InsightEntityNormalizer());

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
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
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

    @Test
    void ignoresOldArticleEvenWhenItWasReanalyzedAfterInsightCreation() {
        NewsInsight insight = insight(List.of("삼성전자", "HBM4"), List.of(10L));
        LocalDateTime oldPublishedAt = insight.getCreatedAt().minusHours(1);
        Finding finding = finding(
                20L,
                topic,
                new FindingEntities(List.of("삼성전자"), List.of("HBM4"), List.of()),
                oldPublishedAt,
                LocalDateTime.now(),
                AnalysisSource.LLM);
        stubCandidates(insight, finding);

        assertEquals(0, tracker.track(42L));
        assertEquals(List.of(), insight.getRelatedArticleIds());
    }

    @Test
    void ignoresStubFinding() {
        NewsInsight insight = insight(List.of("삼성전자", "HBM4"), List.of(10L));
        Finding finding = finding(
                20L,
                topic,
                new FindingEntities(List.of("삼성전자"), List.of("HBM4"), List.of()),
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now(),
                AnalysisSource.STUB);
        stubCandidates(insight, finding);

        assertEquals(0, tracker.track(42L));
        assertEquals(List.of(), insight.getRelatedArticleIds());
    }

    @Test
    void canonicalizesOrganizationAliasesOnBothSides() {
        NewsInsight insight = insight(List.of("SK하이닉스", "HBM4"), List.of(10L));
        Finding finding = finding(
                20L,
                topic,
                new FindingEntities(List.of("SK hynix"), List.of("HBM4"), List.of()),
                LocalDateTime.now().minusHours(1));
        stubCandidates(insight, finding);

        assertEquals(1, tracker.track(42L));
        assertEquals(List.of(20L), insight.getRelatedArticleIds());
    }

    @Test
    void updatesOnlyLatestInsightForSameIssueAndAudience() {
        LocalDateTime now = LocalDateTime.now();
        NewsInsight older = insight(1L, now.minusDays(2), List.of("삼성전자", "HBM4"));
        NewsInsight latest = insight(2L, now.minusDays(1), List.of("삼성전자", "HBM4"));
        Finding finding = finding(
                20L,
                topic,
                new FindingEntities(List.of("삼성전자"), List.of("HBM4"), List.of()),
                now.minusHours(1));
        when(insightRepository
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
                        eq(AgentTargetType.ISSUE), eq(List.of(88L)), any(LocalDateTime.class)))
                .thenReturn(List.of(older, latest));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));

        assertEquals(1, tracker.track(42L));
        assertEquals(List.of(), older.getRelatedArticleIds());
        assertEquals(List.of(20L), latest.getRelatedArticleIds());
    }

    @Test
    void removesEntitiesThatAreCommonAcrossTopicRun() {
        NewsInsight insight = insight(List.of("삼성전자", "HBM4"), List.of(10L));
        List<Finding> findings = new ArrayList<>();
        for (long articleId = 20L; articleId < 40L; articleId++) {
            findings.add(finding(
                    articleId,
                    topic,
                    new FindingEntities(List.of("삼성전자"), List.of("HBM4"), List.of()),
                    LocalDateTime.now().minusMinutes(1)));
        }
        when(insightRepository
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
                        eq(AgentTargetType.ISSUE), eq(List.of(88L)), any(LocalDateTime.class)))
                .thenReturn(List.of(insight));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(findings);

        assertEquals(0, tracker.track(42L));
        assertEquals(List.of(), insight.getRelatedArticleIds());
    }

    @Test
    void batchesActiveIssueQueriesBelowOracleInClauseLimit() {
        List<Long> issueIds = LongStream.rangeClosed(1, 901).boxed().toList();
        when(watchRepository.findActiveIssueIdsByWatchType(
                eq(WatchType.HYPOTHESIS), any(LocalDateTime.class)))
                .thenReturn(issueIds);
        when(issueRepository.findAllById(any())).thenReturn(List.of());

        assertEquals(0, tracker.track(42L));
        verify(issueRepository, times(2)).findAllById(any());
        verify(insightRepository, times(2))
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
                        eq(AgentTargetType.ISSUE), any(), any(LocalDateTime.class));
    }

    private void stubCandidates(NewsInsight insight, Finding finding) {
        when(insightRepository
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
                        eq(AgentTargetType.ISSUE), eq(List.of(88L)), any(LocalDateTime.class)))
                .thenReturn(List.of(insight));
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));
    }

    private NewsInsight insight(List<String> entities, List<Long> inputArticleIds) {
        return NewsInsight.builder()
                .id(1L)
                .targetType(AgentTargetType.ISSUE)
                .targetId(88L)
                .audience(Audience.CHIP_MAKER)
                .watchEntities(entities)
                .inputArticleIds(inputArticleIds)
                .relatedArticleIds(List.of())
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    private NewsInsight insight(Long id,
                                LocalDateTime createdAt,
                                List<String> entities) {
        return NewsInsight.builder()
                .id(id)
                .targetType(AgentTargetType.ISSUE)
                .targetId(88L)
                .audience(Audience.CHIP_MAKER)
                .watchEntities(entities)
                .inputArticleIds(List.of(10L))
                .relatedArticleIds(List.of())
                .createdAt(createdAt)
                .build();
    }

    private Finding finding(Long articleId,
                            Topic findingTopic,
                            FindingEntities entities,
                            LocalDateTime analyzedAt) {
        return finding(
                articleId,
                findingTopic,
                entities,
                analyzedAt,
                analyzedAt,
                AnalysisSource.LLM);
    }

    private Finding finding(Long articleId,
                            Topic findingTopic,
                            FindingEntities entities,
                            LocalDateTime publishedAt,
                            LocalDateTime analyzedAt,
                            AnalysisSource analysisSource) {
        return Finding.builder()
                .id(1_000L + articleId)
                .article(Article.builder()
                        .id(articleId)
                        .topic(findingTopic)
                        .publishedAt(publishedAt.atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                        .collectedAt(publishedAt)
                        .build())
                .analysisSource(analysisSource)
                .entities(entities)
                .analyzedAt(analyzedAt)
                .build();
    }
}
