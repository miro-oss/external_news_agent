package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleAnalysisPipelineTest {

    private final CollectionRunArticleRepository runArticleRepository = mock(CollectionRunArticleRepository.class);
    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final ArticleAnalysisOrchestrator orchestrator = mock(ArticleAnalysisOrchestrator.class);
    private final FindingReuseCache reuseCache = mock(FindingReuseCache.class);
    private final FindingWriter findingWriter = mock(FindingWriter.class);
    private final AnalysisSelectionProperties selectionProperties = new AnalysisSelectionProperties();
    private Map<String, Double> topicWeights = Map.of();
    private final ArticleAnalysisPipeline pipeline =
            new ArticleAnalysisPipeline(
                    runArticleRepository, runRepository, issueArticleRepository,
                    orchestrator, reuseCache, findingWriter, selectionProperties,
                    new TopicFitScorer((language, keywords) -> topicWeights));

    @BeforeEach
    void loadRunPlan() {
        topicWeights = Map.of();
        when(runRepository.findById(42L)).thenReturn(java.util.Optional.of(
                CollectionRun.builder().id(42L).llmPlan(AgentPlan.FREE).build()));
        when(reuseCache.lookupContexts(anyList(), any(AgentPlan.class))).thenAnswer(invocation -> {
            List<AnalysisContext> contexts = invocation.getArgument(0);
            Map<Long, FindingReuseCache.Lookup> lookups = new LinkedHashMap<>();
            contexts.forEach(context -> lookups.put(
                    context.article().getId(),
                    new FindingReuseCache.Lookup(
                            FindingReuseCache.inputHash(context), Optional.empty())));
            return Map.copyOf(lookups);
        });
    }

    @Test
    void analyzesSameArticleOnlyOnceAndPrefersUpdatedObservation() {
        Article article = Article.builder().id(10L).title("기사").build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(article, ChangeType.NEW),
                observation(article, ChangeType.UPDATED)));
        AnalysisResult result = mock(AnalysisResult.class);
        AnalysisContext context = new AnalysisContext(42L, article, AgentPlan.FREE);
        when(orchestrator.analyze(context)).thenReturn(result);

        pipeline.analyze(42L);

        verify(orchestrator, times(1)).analyze(context);
        verify(findingWriter).write(42L, 10L, ChangeType.UPDATED, inputHash(article), result);
    }

    @Test
    void propagatesPaidPlanFromCollectionRun() {
        Article article = Article.builder().id(10L).title("기사").build();
        when(runRepository.findById(42L)).thenReturn(java.util.Optional.of(
                CollectionRun.builder().id(42L).llmPlan(AgentPlan.PAID).build()));
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(article, ChangeType.NEW)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));

        pipeline.analyze(42L);

        ArgumentCaptor<AnalysisContext> captor = ArgumentCaptor.forClass(AnalysisContext.class);
        verify(orchestrator).analyze(captor.capture());
        assertEquals(AgentPlan.PAID, captor.getValue().plan());
    }

    @Test
    void recordsWarningAndContinuesWhenOneArticleFails() {
        Article failed = Article.builder().id(10L).title("실패").build();
        Article succeeded = Article.builder().id(11L).title("성공").build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(failed, ChangeType.NEW),
                observation(succeeded, ChangeType.NEW)));
        when(orchestrator.analyze(new AnalysisContext(42L, failed, AgentPlan.FREE)))
                .thenThrow(new IllegalStateException("stub failure"));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, succeeded, AgentPlan.FREE))).thenReturn(result);

        pipeline.analyze(42L);

        verify(findingWriter).addFailureWarning(42L, 10L, "stub failure");
        verify(findingWriter, never()).write(eq(42L), eq(10L), any(), any(), any());
        verify(findingWriter).write(42L, 11L, ChangeType.NEW, inputHash(succeeded), result);
        verify(reuseCache).lookupContexts(anyList(), eq(AgentPlan.FREE));
    }

    @Test
    void reanalyzesUnchangedArticleAfterFullTextRefresh() {
        Article article = Article.builder()
                .id(10L)
                .title("기사")
                .body("새로 확보한 전문")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of());
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(
                42L, List.of(10L)))
                .thenReturn(List.of(observation(article, ChangeType.UNCHANGED)));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE))).thenReturn(result);

        pipeline.analyze(42L, Set.of(10L));

        verify(findingWriter).write(42L, 10L, ChangeType.UPDATED, inputHash(article), result);
    }

    @Test
    void analyzesHistoricalRepresentativeWhenOnlyNewMemberWasObserved() {
        Article representative = Article.builder().id(10L).title("기존 대표").build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of());
        when(issueArticleRepository.findRepresentativesForRun(42L)).thenReturn(List.of(
                IssueArticle.builder()
                        .article(representative)
                        .role(IssueArticleRole.REPRESENTATIVE)
                        .build()));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, representative, AgentPlan.FREE)))
                .thenReturn(result);

        pipeline.analyze(42L);

        verify(findingWriter).write(42L, 10L, ChangeType.UPDATED, inputHash(representative), result);
    }

    @Test
    void keepsNewChangeTypeWhenObservedRepresentativeIsAlsoBackfilled() {
        Article representative = Article.builder().id(10L).title("이번 실행의 새 대표").build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(representative, ChangeType.NEW)));
        when(issueArticleRepository.findRepresentativesForRun(42L)).thenReturn(List.of(
                IssueArticle.builder()
                        .article(representative)
                        .role(IssueArticleRole.REPRESENTATIVE)
                        .build()));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, representative, AgentPlan.FREE)))
                .thenReturn(result);

        pipeline.analyze(42L);

        verify(findingWriter).write(42L, 10L, ChangeType.NEW, inputHash(representative), result);
    }

    @Test
    void usesUnclusteredTargetsAfterClusteringFailure() {
        Article article = Article.builder().id(10L).title("기사").build();
        when(runArticleRepository.findUnclusteredAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(article, ChangeType.NEW)));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE))).thenReturn(result);

        pipeline.analyzeWithoutClustering(42L, Set.of());

        verify(findingWriter).recordTargetCount(42L, 0);
        verify(findingWriter).write(42L, 10L, ChangeType.NEW, inputHash(article), result);
    }

    @Test
    void skipsUpdatedArticleWhenOldBodyIsKeptAfterRefreshFailure() {
        Article article = Article.builder()
                .id(10L)
                .title("정정 기사")
                .body("직전 전문")
                .fetchStatus(FetchStatus.FETCH_FAILED)
                .build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(article, ChangeType.UPDATED)));

        pipeline.analyze(42L);

        verify(orchestrator, never()).analyze(new AnalysisContext(42L, article, AgentPlan.FREE));
        verify(findingWriter, never()).write(eq(42L), eq(10L), any(), any(), any());
    }

    @Test
    void keepsCurrentUpdatedChangeTypeWhenCacheHits() {
        Article article = Article.builder()
                .id(10L)
                .title("동일 기사")
                .summary("동일 요약")
                .body("동일 본문")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(article, ChangeType.UPDATED)));
        AnalysisResult reused = mock(AnalysisResult.class);
        when(reused.analysisSource()).thenReturn(AnalysisSource.REUSED);
        when(reuseCache.lookupContexts(
                List.of(new AnalysisContext(42L, article, AgentPlan.FREE)), AgentPlan.FREE)).thenReturn(Map.of(
                10L, new FindingReuseCache.Lookup(inputHash(article), Optional.of(reused))));

        pipeline.analyze(42L);

        verify(orchestrator, never()).analyze(any());
        verify(findingWriter).write(42L, 10L, ChangeType.UPDATED, inputHash(article), reused);
    }

    @Test
    void limitsIssuesAfterOrderingByTopicFit() {
        selectionProperties.setIssueLimitPerRun(2);
        Topic topic =
                Topic.builder()
                        .optionalKeywords(List.of("HBM", "삼성", "양산"))
                        .build();
        Article low = Article.builder().id(10L).title("HBM 소식").summary("요약").build();
        Article high = Article.builder().id(11L).title("삼성 HBM 양산").summary("요약").build();
        Article medium = Article.builder().id(12L).title("삼성 HBM").summary("요약").build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(low, topic, ChangeType.NEW),
                observation(high, topic, ChangeType.NEW),
                observation(medium, topic, ChangeType.NEW)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));

        pipeline.analyze(42L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(orchestrator);
        order.verify(orchestrator).analyze(new AnalysisContext(42L, high, AgentPlan.FREE));
        order.verify(orchestrator).analyze(new AnalysisContext(42L, medium, AgentPlan.FREE));
        verify(orchestrator, never()).analyze(new AnalysisContext(42L, low, AgentPlan.FREE));
    }

    @Test
    void analyzesRareKeywordMatchBeforeTwoCommonMatches() {
        selectionProperties.setIssueLimitPerRun(1);
        topicWeights = Map.of("반도체", 1.0d, "ai", 1.0d, "hbm4", 4.0d);
        Topic topic = Topic.builder()
                .optionalKeywords(List.of("반도체", "AI", "HBM4"))
                .build();
        Article common = Article.builder().id(10L).title("반도체 AI 투자").build();
        Article rare = Article.builder().id(11L).title("HBM4 투자").build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(common, topic, ChangeType.NEW),
                observation(rare, topic, ChangeType.NEW)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));

        pipeline.analyze(42L);

        verify(orchestrator).analyze(new AnalysisContext(42L, rare, AgentPlan.FREE));
        verify(orchestrator, never()).analyze(new AnalysisContext(42L, common, AgentPlan.FREE));
    }

    @Test
    void marksOnlyTopTwentyPercentOfIssuesForSelfCritique() {
        Topic topic = Topic.builder()
                .optionalKeywords(List.of("HBM", "삼성", "양산"))
                .build();
        List<Article> articles = List.of(
                Article.builder().id(10L).title("일반 소식").sourceName("A").build(),
                Article.builder().id(11L).title("HBM 소식").sourceName("B").build(),
                Article.builder().id(12L).title("삼성 소식").sourceName("C").build(),
                Article.builder().id(13L).title("삼성 HBM 소식").sourceName("D").build(),
                Article.builder().id(14L).title("삼성 HBM 양산").sourceName("E").build());
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(articles.stream()
                        .map(article -> observation(article, topic, ChangeType.NEW))
                        .toList());
        when(issueArticleRepository.findIssueContextsByRepresentativeArticleIds(any()))
                .thenReturn(articles.stream()
                        .map(article -> IssueArticle.builder()
                                .issue(NewsIssue.builder().id(100L + article.getId()).build())
                                .article(article)
                                .role(IssueArticleRole.REPRESENTATIVE)
                                .build())
                        .toList());
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));

        pipeline.analyze(42L);

        ArgumentCaptor<AnalysisContext> captor = ArgumentCaptor.forClass(AnalysisContext.class);
        verify(orchestrator, times(5)).analyze(captor.capture());
        List<AnalysisContext> eligible = captor.getAllValues().stream()
                .filter(AnalysisContext::selfCritiqueEligible)
                .toList();
        assertEquals(1, eligible.size());
        assertEquals(14L, eligible.getFirst().article().getId());
        assertTrue(eligible.getFirst().issue().present());
    }

    private CollectionRunArticle observation(Article article, ChangeType changeType) {
        return CollectionRunArticle.builder()
                .article(article)
                .changeType(changeType)
                .build();
    }

    private CollectionRunArticle observation(Article article,
                                             Topic topic,
                                             ChangeType changeType) {
        return CollectionRunArticle.builder()
                .article(article)
                .topic(topic)
                .changeType(changeType)
                .build();
    }

    private String inputHash(Article article) {
        return FindingReuseCache.inputHash(article);
    }
}
