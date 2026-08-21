package com.example.be.domain.analysis.service;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private final ArticleAnalysisOrchestrator orchestrator = mock(ArticleAnalysisOrchestrator.class);
    private final FindingReuseCache reuseCache = mock(FindingReuseCache.class);
    private final FindingWriter findingWriter = mock(FindingWriter.class);
    private final ArticleAnalysisPipeline pipeline =
            new ArticleAnalysisPipeline(
                    runArticleRepository, runRepository, orchestrator, reuseCache, findingWriter);

    @BeforeEach
    void loadRunPlan() {
        when(runRepository.findById(42L)).thenReturn(java.util.Optional.of(
                CollectionRun.builder().id(42L).llmPlan(AgentPlan.FREE).build()));
        when(reuseCache.lookupAll(anyList(), any(AgentPlan.class))).thenAnswer(invocation -> {
            List<Article> articles = invocation.getArgument(0);
            Map<Long, FindingReuseCache.Lookup> lookups = new LinkedHashMap<>();
            articles.forEach(article -> lookups.put(
                    article.getId(), new FindingReuseCache.Lookup(inputHash(article), Optional.empty())));
            return Map.copyOf(lookups);
        });
    }

    @Test
    void analyzesSameArticleOnlyOnceAndPrefersUpdatedObservation() {
        Article article = Article.builder().id(10L).title("기사").build();
        when(runArticleRepository.findAnalysisTargetsByRunId(42L)).thenReturn(List.of(
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
        when(runArticleRepository.findAnalysisTargetsByRunId(42L))
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
        when(runArticleRepository.findAnalysisTargetsByRunId(42L)).thenReturn(List.of(
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
        verify(reuseCache).lookupAll(List.of(failed, succeeded), AgentPlan.FREE);
    }

    @Test
    void reanalyzesUnchangedArticleAfterFullTextRefresh() {
        Article article = Article.builder()
                .id(10L)
                .title("기사")
                .body("새로 확보한 전문")
                .fetchStatus(FetchStatus.FULLTEXT)
                .build();
        when(runArticleRepository.findAnalysisTargetsByRunId(42L)).thenReturn(List.of());
        when(runArticleRepository.findAnalysisTargetsByRunIdAndArticleIdIn(42L, Set.of(10L)))
                .thenReturn(List.of(observation(article, ChangeType.UNCHANGED)));
        AnalysisResult result = mock(AnalysisResult.class);
        when(orchestrator.analyze(new AnalysisContext(42L, article, AgentPlan.FREE))).thenReturn(result);

        pipeline.analyze(42L, Set.of(10L));

        verify(findingWriter).write(42L, 10L, ChangeType.UPDATED, inputHash(article), result);
    }

    @Test
    void skipsUpdatedArticleWhenOldBodyIsKeptAfterRefreshFailure() {
        Article article = Article.builder()
                .id(10L)
                .title("정정 기사")
                .body("직전 전문")
                .fetchStatus(FetchStatus.FETCH_FAILED)
                .build();
        when(runArticleRepository.findAnalysisTargetsByRunId(42L))
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
        when(runArticleRepository.findAnalysisTargetsByRunId(42L))
                .thenReturn(List.of(observation(article, ChangeType.UPDATED)));
        AnalysisResult reused = mock(AnalysisResult.class);
        when(reused.analysisSource()).thenReturn(AnalysisSource.REUSED);
        when(reuseCache.lookupAll(List.of(article), AgentPlan.FREE)).thenReturn(Map.of(
                10L, new FindingReuseCache.Lookup(inputHash(article), Optional.of(reused))));

        pipeline.analyze(42L);

        verify(orchestrator, never()).analyze(any());
        verify(findingWriter).write(42L, 10L, ChangeType.UPDATED, inputHash(article), reused);
    }

    private CollectionRunArticle observation(Article article, ChangeType changeType) {
        return CollectionRunArticle.builder()
                .article(article)
                .changeType(changeType)
                .build();
    }

    private String inputHash(Article article) {
        return FindingReuseCache.inputHash(article);
    }
}
