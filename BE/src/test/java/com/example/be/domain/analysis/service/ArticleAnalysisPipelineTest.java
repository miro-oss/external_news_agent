package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ArticleAnalysisPipelineTest {

    private final CollectionRunArticleRepository runArticleRepository = mock(CollectionRunArticleRepository.class);
    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final CollectionRunItemRepository runItemRepository = mock(CollectionRunItemRepository.class);
    private final IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
    private final ArticleAnalysisOrchestrator orchestrator = mock(ArticleAnalysisOrchestrator.class);
    private final FindingReuseCache reuseCache = mock(FindingReuseCache.class);
    private final TopicRelevanceGate relevanceGate = mock(TopicRelevanceGate.class);
    private final FindingWriter findingWriter = mock(FindingWriter.class);
    private final AnalysisSelectionProperties selectionProperties = new AnalysisSelectionProperties();
    private Map<String, Double> topicWeights = Map.of();
    private final ArticleAnalysisPipeline pipeline =
            new ArticleAnalysisPipeline(
                    runArticleRepository, runRepository, runItemRepository, issueArticleRepository,
                    orchestrator, reuseCache, findingWriter, selectionProperties,
                    new TopicFitScorer((language, keywords) -> topicWeights), relevanceGate);

    @BeforeEach
    void loadRunPlan() {
        topicWeights = Map.of();
        when(relevanceGate.assess(eq(42L), any(AgentPlan.class), anyList())).thenAnswer(invocation -> {
            List<TopicRelevanceGate.Candidate> candidates = invocation.getArgument(2);
            return candidates.stream().map(candidate -> new TopicRelevanceGate.Key(
                    candidate.article().getId(), candidate.topic() == null ? null : candidate.topic().getId()))
                    .collect(Collectors.toSet());
        });
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
        Article article = Article.builder().id(10L).title("기사").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
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
        Article article = Article.builder().id(10L).title("기사").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
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
    void selectsAndAnalyzesWithQueuedTopicSnapshotAfterLiveKeywordsChange() {
        Topic topic = Topic.builder().id(7L).name("메모리").queryText("HBM")
                .requiredKeywords(List.of()).optionalKeywords(List.of("HBM"))
                .excludedKeywords(List.of()).batchSize(100).intervalMinutes(1440).active(true).build();
        CollectionRunItem item = CollectionRunItem.builder().topic(topic)
                .source(Source.builder().id(1L).build()).build();
        item.captureTopicSnapshot();
        topic.update("메모리", "DRAM", List.of(), List.of("DRAM"), List.of(), 100, 1440, true);
        Article queuedMatch = Article.builder().id(10L).title("HBM 공급 확대").topic(topic).body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        Article liveMatch = Article.builder().id(11L).title("DRAM 공급 확대").topic(topic).body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(item));
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(liveMatch, topic, ChangeType.NEW), observation(queuedMatch, topic, ChangeType.NEW)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));
        selectionProperties.setIssueLimitPerRun(1);

        pipeline.analyze(42L);

        ArgumentCaptor<AnalysisContext> captor = ArgumentCaptor.forClass(AnalysisContext.class);
        verify(orchestrator).analyze(captor.capture());
        assertEquals(10L, captor.getValue().article().getId());
        assertEquals("HBM", captor.getValue().topic().getQueryText());
        assertEquals(List.of("HBM"), captor.getValue().topic().getOptionalKeywords());
        assertEquals("DRAM", topic.getQueryText());
    }

    @Test
    void recordsWarningAndContinuesWhenOneArticleFails() {
        Article failed = Article.builder().id(10L).title("실패").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        Article succeeded = Article.builder().id(11L).title("성공").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
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
    void recoveryRefreshesExistingFindingForObservedRepresentative() {
        Article article = Article.builder()
                .id(10L)
                .title("복구 대상 기사")
                .body("확보한 전문")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(article, ChangeType.NEW)));
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(42L, List.of(10L)))
                .thenReturn(List.of(observation(article, ChangeType.NEW)));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE))).thenReturn(result);

        pipeline.recover(42L, Set.of(10L));

        verify(findingWriter).recordTargetCount(42L, 1);
        verify(findingWriter).refresh(42L, 10L, ChangeType.NEW, inputHash(article), result);
        verify(findingWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void refreshesOnlyInvestigationTargetsWithoutOverwritingCoverageCount() {
        Article article = Article.builder()
                .id(10L)
                .title("조사로 전문을 확보한 기사")
                .body("새 전문")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(
                42L, List.of(10L)))
                .thenReturn(List.of(observation(article, ChangeType.UNCHANGED)));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE))).thenReturn(result);

        pipeline.analyzeInvestigation(42L, Set.of(10L));

        verify(findingWriter).refresh(42L, 10L, ChangeType.UPDATED, inputHash(article), result);
        verify(findingWriter, never()).recordTargetCount(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(runArticleRepository, never()).findRepresentativeAnalysisTargetsByRunId(42L);
    }

    @Test
    void analyzesHistoricalRepresentativeWhenOnlyNewMemberWasObserved() {
        Article representative = Article.builder().id(10L).title("기존 대표").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
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
        Article representative = Article.builder().id(10L).title("이번 실행의 새 대표").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
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
        Article article = Article.builder().id(10L).title("기사").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
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
    void completesWithZeroTargetsWhenObservedArticlesHaveNoUsableFullText() {
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(
                unavailableArticles().stream().map(article -> observation(article, ChangeType.NEW)).toList());

        pipeline.analyze(42L);

        verify(findingWriter).recordTargetCount(42L, 0);
        verifyNoMoreInteractions(findingWriter);
        verifyNoInteractions(orchestrator, reuseCache);
    }

    @Test
    void doesNotReviveMetadataOnlyHistoricalRepresentativeOnReobservation() {
        Article representative = unavailableArticles().getFirst();
        IssueArticle membership = IssueArticle.builder().article(representative)
                .role(IssueArticleRole.REPRESENTATIVE).build();
        when(issueArticleRepository.findRepresentativesForRun(42L)).thenReturn(List.of(membership));
        when(issueArticleRepository.findRepresentativesForRunAndObservedArticleIdIn(42L, List.of(19L)))
                .thenReturn(List.of(membership));

        pipeline.analyze(42L, Set.of(19L));

        verify(findingWriter).recordTargetCount(42L, 0);
        verifyNoMoreInteractions(findingWriter);
        verifyNoInteractions(orchestrator, reuseCache);
    }

    @Test
    void clusteringFailureDoesNotAnalyzeArticlesWithoutUsableFullText() {
        List<CollectionRunArticle> observations = unavailableArticles().stream()
                .map(article -> observation(article, ChangeType.UNCHANGED)).toList();
        when(runArticleRepository.findUnclusteredAnalysisTargetsByRunId(42L)).thenReturn(observations);
        when(runArticleRepository.findUnclusteredAnalysisTargetsByRunIdAndArticleIdIn(42L, List.of(10L)))
                .thenReturn(observations);

        pipeline.analyzeWithoutClustering(42L, Set.of(10L));

        verify(findingWriter).recordTargetCount(42L, 0);
        verifyNoMoreInteractions(findingWriter);
        verifyNoInteractions(orchestrator, reuseCache);
    }

    @Test
    void investigationDoesNotAnalyzeMissingFullTextOrItsHistoricalRepresentative() {
        List<Article> articles = unavailableArticles();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(42L, List.of(10L)))
                .thenReturn(articles.stream().map(article -> observation(article, ChangeType.UNCHANGED)).toList());
        when(issueArticleRepository.findRepresentativesForRunAndObservedArticleIdIn(42L, List.of(10L)))
                .thenReturn(List.of(IssueArticle.builder().article(articles.getFirst())
                        .role(IssueArticleRole.REPRESENTATIVE).build()));

        pipeline.analyzeInvestigation(42L, Set.of(10L));

        verifyNoInteractions(orchestrator, reuseCache, findingWriter);
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
        Article low = Article.builder().id(10L).title("HBM 소식").summary("요약").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        Article high = Article.builder().id(11L).title("삼성 HBM 양산").summary("요약").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        Article medium = Article.builder().id(12L).title("삼성 HBM").summary("요약").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(low, topic, ChangeType.NEW),
                observation(high, topic, ChangeType.NEW),
                observation(medium, topic, ChangeType.NEW)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));

        pipeline.analyze(42L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(orchestrator);
        order.verify(orchestrator).analyze(context(high, topic));
        order.verify(orchestrator).analyze(context(medium, topic));
        verify(orchestrator, never()).analyze(context(low, topic));
    }

    @Test
    void analyzesRareKeywordMatchBeforeTwoCommonMatches() {
        selectionProperties.setIssueLimitPerRun(1);
        topicWeights = Map.of("반도체", 1.0d, "ai", 1.0d, "hbm4", 4.0d);
        Topic topic = Topic.builder()
                .optionalKeywords(List.of("반도체", "AI", "HBM4"))
                .build();
        Article common = Article.builder().id(10L).title("반도체 AI 투자").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        Article rare = Article.builder().id(11L).title("HBM4 투자").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(common, topic, ChangeType.NEW),
                observation(rare, topic, ChangeType.NEW)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));

        pipeline.analyze(42L);

        verify(orchestrator).analyze(context(rare, topic));
        verify(orchestrator, never()).analyze(context(common, topic));
    }

    @Test
    void marksTopTwentyPercentByPersistedIssueImportanceForSelfCritique() {
        Topic topic = Topic.builder()
                .optionalKeywords(List.of("HBM", "삼성", "양산"))
                .build();
        List<Article> articles = List.of(
                Article.builder().id(10L).title("일반 소식").sourceName("A").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build(),
                Article.builder().id(11L).title("HBM 소식").sourceName("B").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build(),
                Article.builder().id(12L).title("삼성 소식").sourceName("C").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build(),
                Article.builder().id(13L).title("삼성 HBM 소식").sourceName("D").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build(),
                Article.builder().id(14L).title("삼성 HBM 양산").sourceName("E").body("확보한 전문").fetchStatus(FetchStatus.FULLTEXT).build());
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(articles.stream()
                        .map(article -> observation(article, topic, ChangeType.NEW))
                        .toList());
        when(issueArticleRepository.findIssueContextsByRepresentativeArticleIds(any()))
                .thenReturn(articles.stream()
                        .map(article -> IssueArticle.builder()
                                .issue(NewsIssue.builder()
                                        .id(100L + article.getId())
                                        .importanceScore(article.getId() == 10L
                                                ? new BigDecimal("90.00")
                                                : new BigDecimal("20.00"))
                                        .build())
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
        assertEquals(10L, eligible.getFirst().article().getId());
        assertTrue(eligible.getFirst().issue().present());
        assertEquals(new BigDecimal("90.00"), eligible.getFirst().issue().importanceScore());
    }

    @Test
    void rejectsIrrelevantCandidatesBeforeCacheAndDoesNotConsumeAnalysisLimit() {
        Topic topic = Topic.builder().id(7L).name("반도체 제조 장비")
                .optionalKeywords(List.of("공정", "라인")).build();
        Article unrelated = fullTextArticle(10L, "공정위 토스 오프라인 조사", topic);
        Article related = fullTextArticle(11L, "웨이퍼 식각 장비 증설", topic);
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(unrelated, topic, ChangeType.NEW), observation(related, topic, ChangeType.NEW)));
        when(relevanceGate.assess(eq(42L), eq(AgentPlan.FREE), anyList()))
                .thenReturn(Set.of(new TopicRelevanceGate.Key(11L, 7L)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));
        selectionProperties.setIssueLimitPerRun(1);

        pipeline.analyze(42L);

        verify(findingWriter).recordTargetCount(42L, 1);
        verify(orchestrator).analyze(context(related, topic));
        verify(orchestrator, never()).analyze(context(unrelated, topic));
        verify(reuseCache).lookupContexts(List.of(context(related, topic)), AgentPlan.FREE);
    }

    @Test
    void gatesSharedArticlePerObservedTopicBeforeSelectingAnalysisContext() {
        Topic unrelatedTopic = Topic.builder().id(7L).name("제조 장비")
                .optionalKeywords(List.of("공정", "라인")).build();
        Topic relatedTopic = Topic.builder().id(8L).name("플랫폼 규제").optionalKeywords(List.of()).build();
        Article article = fullTextArticle(10L, "공정위 토스 오프라인 조사", unrelatedTopic);
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L)).thenReturn(List.of(
                observation(article, unrelatedTopic, ChangeType.NEW),
                observation(article, relatedTopic, ChangeType.UPDATED)));
        when(relevanceGate.assess(eq(42L), eq(AgentPlan.FREE), anyList()))
                .thenReturn(Set.of(new TopicRelevanceGate.Key(10L, 8L)));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(any())).thenReturn(result);

        pipeline.analyze(42L);

        verify(relevanceGate).assess(42L, AgentPlan.FREE, List.of(
                new TopicRelevanceGate.Candidate(article, unrelatedTopic),
                new TopicRelevanceGate.Candidate(article, relatedTopic)));
        verify(orchestrator).analyze(context(article, relatedTopic));
        verify(findingWriter).write(42L, 10L, ChangeType.UPDATED,
                FindingReuseCache.inputHash(context(article, relatedTopic)), result);
    }

    @Test
    void rejectsHistoricalRepresentativesAndInvestigationRefreshBeforeCache() {
        Topic topic = Topic.builder().id(7L).name("반도체 장비").build();
        Article representative = fullTextArticle(10L, "토스 분쟁", topic);
        IssueArticle membership = IssueArticle.builder().article(representative)
                .issue(NewsIssue.builder().id(100L).topic(topic).build())
                .role(IssueArticleRole.REPRESENTATIVE).build();
        when(issueArticleRepository.findRepresentativesForRun(42L)).thenReturn(List.of(membership));
        when(issueArticleRepository.findRepresentativesForRunAndObservedArticleIdIn(42L, List.of(19L)))
                .thenReturn(List.of(membership));
        when(relevanceGate.assess(eq(42L), eq(AgentPlan.FREE), anyList())).thenReturn(Set.of());

        pipeline.analyze(42L);
        pipeline.analyzeInvestigation(42L, Set.of(19L));

        verify(findingWriter).recordTargetCount(42L, 0);
        verifyNoMoreInteractions(findingWriter);
        verifyNoInteractions(orchestrator, reuseCache);
        verify(relevanceGate, times(2)).assess(42L, AgentPlan.FREE,
                List.of(new TopicRelevanceGate.Candidate(representative, topic)));
    }

    @Test
    void clusteringFailureStillRequiresRelevantAssessment() {
        Topic topic = Topic.builder().id(7L).name("반도체 장비").build();
        Article article = fullTextArticle(10L, "토스 분쟁", topic);
        when(runArticleRepository.findUnclusteredAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(article, topic, ChangeType.NEW)));
        when(relevanceGate.assess(eq(42L), eq(AgentPlan.FREE), anyList())).thenReturn(Set.of());

        pipeline.analyzeWithoutClustering(42L, Set.of());

        verifyNoInteractions(orchestrator, reuseCache);
        verify(findingWriter).recordTargetCount(42L, 0);
        verifyNoMoreInteractions(findingWriter);
    }

    @Test
    void removesRejectedComparisonMembersAndOtherTopicIssueContexts() {
        Topic topic = Topic.builder().id(7L).name("반도체 장비").build();
        Topic otherTopic = Topic.builder().id(8L).name("플랫폼 규제").build();
        Article representative = fullTextArticle(10L, "장비 투자", topic);
        Article relatedMember = fullTextArticle(11L, "식각 장비 투자", topic);
        Article unrelatedMember = fullTextArticle(12L, "토스 분쟁", topic);
        NewsIssue issue = NewsIssue.builder().id(100L).topic(topic).build();
        NewsIssue otherIssue = NewsIssue.builder().id(200L).topic(otherTopic).build();
        when(runArticleRepository.findRepresentativeAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(representative, topic, ChangeType.NEW)));
        when(issueArticleRepository.findIssueContextsByRepresentativeArticleIds(Set.of(10L))).thenReturn(List.of(
                IssueArticle.builder().article(representative).issue(otherIssue)
                        .role(IssueArticleRole.REPRESENTATIVE).build(),
                IssueArticle.builder().article(representative).issue(issue)
                        .role(IssueArticleRole.REPRESENTATIVE).build(),
                IssueArticle.builder().article(relatedMember).issue(issue).role(IssueArticleRole.MEMBER).build(),
                IssueArticle.builder().article(unrelatedMember).issue(issue).role(IssueArticleRole.MEMBER).build()));
        when(relevanceGate.assess(eq(42L), eq(AgentPlan.FREE), anyList())).thenReturn(Set.of(
                new TopicRelevanceGate.Key(10L, 7L), new TopicRelevanceGate.Key(11L, 7L)));
        when(orchestrator.analyze(any())).thenReturn(mock(AnalysisResult.class));

        pipeline.analyze(42L);

        ArgumentCaptor<AnalysisContext> contexts = ArgumentCaptor.forClass(AnalysisContext.class);
        verify(orchestrator).analyze(contexts.capture());
        assertEquals(100L, contexts.getValue().issue().issueId());
        assertEquals(List.of(representative, relatedMember), contexts.getValue().issue().articles());
        verify(relevanceGate).assess(42L, AgentPlan.FREE, List.of(
                new TopicRelevanceGate.Candidate(representative, topic),
                new TopicRelevanceGate.Candidate(relatedMember, topic),
                new TopicRelevanceGate.Candidate(unrelatedMember, topic)));
    }

    private Article fullTextArticle(Long id, String title, Topic topic) {
        return Article.builder().id(id).title(title).topic(topic).body("확보한 전문")
                .fetchStatus(FetchStatus.FULLTEXT).build();
    }

    private AnalysisContext context(Article article, Topic topic) {
        return new AnalysisContext(42L, article, AgentPlan.FREE, IssueAnalysisContext.empty(), false, topic);
    }

    private List<Article> unavailableArticles() {
        return List.of(
                Article.builder().id(10L).title("메타데이터만 확보").fetchStatus(FetchStatus.METADATA_ONLY).build(),
                Article.builder().id(11L).title("본문 없음").fetchStatus(FetchStatus.FULLTEXT).build(),
                Article.builder().id(12L).title("공백 본문").body(" \n\t").fetchStatus(FetchStatus.FULLTEXT).build(),
                Article.builder().id(13L).title("재수집 실패").body("옛 전문").fetchStatus(FetchStatus.FETCH_FAILED).build());
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
