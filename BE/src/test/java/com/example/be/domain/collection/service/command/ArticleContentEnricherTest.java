package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.content.ArticleContentClient;
import com.example.be.domain.collection.content.ArticleContentResult;
import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsRules;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticleContentEnricherTest {

    private final CollectionRunItemRepository runItemRepository = mock(CollectionRunItemRepository.class);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passesTheArticleTitleWithOrWithoutRobotsChecks(boolean respectRobots) {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient contentClient = mock(ArticleContentClient.class);
        RobotsTxtClient robotsClient = mock(RobotsTxtClient.class);
        CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
        ArticleContentEnricher enricher = new ArticleContentEnricher(repository, runItemRepository, contentClient, robotsClient,
                resultWriter, new CollectionPipelineProperties(), equalWeightScorer());
        Topic topic = Topic.builder().id(7L).optionalKeywords(List.of("금리")).build();
        Source source = Source.builder().id(8L)
                .crawlPolicy(new CrawlPolicy(respectRobots ? CrawlPolicy.ROBOTS_MODE_RESPECT
                        : CrawlPolicy.ROBOTS_MODE_IGNORE, 30, true)).build();
        String title = "한국은행이 기준금리를 동결했다.";
        Article article = article(1L, "https://example.com/brief", title, source, topic);
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(List.of(observation(article, topic)));
        Duration delay = respectRobots ? Duration.ofSeconds(2) : null;
        if (respectRobots) {
            when(robotsClient.lookup(article.getCanonicalUrl())).thenReturn(RobotsLookup.fetched(
                    "https://example.com/robots.txt", new RobotsRules(List.of(), List.of(), delay)));
        }
        if (respectRobots) {
            when(contentClient.fetch(article.getCanonicalUrl(), delay, title))
                    .thenReturn(ArticleContentResult.fullText(title));
        } else {
            when(contentClient.fetch(article.getCanonicalUrl(), null, title, false))
                    .thenReturn(ArticleContentResult.fullText(title));
        }

        assertEquals(java.util.Set.of(1L), enricher.enrich(42L));

        if (respectRobots) {
            verify(contentClient).fetch(article.getCanonicalUrl(), delay, title);
        } else {
            verify(contentClient).fetch(article.getCanonicalUrl(), null, title, false);
        }
        verify(resultWriter).applyFullText(1L, FetchStatus.FULLTEXT, title);
        if (!respectRobots) {
            verifyNoInteractions(robotsClient);
        }
    }

    @Test
    void fetchesHigherTopicFitFirst() {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient contentClient = mock(ArticleContentClient.class);
        CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        ArticleContentEnricher enricher = new ArticleContentEnricher(
                repository,
                runItemRepository,
                contentClient,
                mock(RobotsTxtClient.class),
                resultWriter,
                properties,
                equalWeightScorer());
        Topic topic = Topic.builder().id(7L).optionalKeywords(List.of("HBM", "삼성")).build();
        Source source = Source.builder()
                .id(8L)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_IGNORE, 30, true))
                .build();
        Article low = article(1L, "https://example.com/low", "HBM 소식", source, topic);
        Article high = article(2L, "https://example.com/high", "삼성 HBM 양산", source, topic);
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(List.of(
                observation(low, topic), observation(high, topic)));
        List<String> fetchedUrls = new ArrayList<>();
        when(contentClient.fetch(any(), any(), any(), eq(false))).thenAnswer(invocation -> {
            fetchedUrls.add(invocation.getArgument(0));
            return ArticleContentResult.fullText("본문");
        });

        enricher.enrich(42L);

        assertEquals(List.of("https://example.com/high", "https://example.com/low"), fetchedUrls);
    }

    @Test
    void limitsFulltextRequestsAfterTopicFitOrdering() {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient contentClient = mock(ArticleContentClient.class);
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setFulltextLimitPerRun(1);
        ArticleContentEnricher enricher = new ArticleContentEnricher(
                repository,
                runItemRepository,
                contentClient,
                mock(RobotsTxtClient.class),
                mock(CollectionResultWriter.class),
                properties,
                equalWeightScorer());
        Topic topic = Topic.builder().id(7L).optionalKeywords(List.of("HBM", "삼성")).build();
        Source source = Source.builder()
                .id(8L)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_IGNORE, 30, true))
                .build();
        Article low = article(1L, "https://example.com/low", "HBM 소식", source, topic);
        Article high = article(2L, "https://example.com/high", "삼성 HBM 양산", source, topic);
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(List.of(
                observation(low, topic), observation(high, topic)));
        List<String> fetchedUrls = new ArrayList<>();
        when(contentClient.fetch(any(), any(), any(), eq(false))).thenAnswer(invocation -> {
            fetchedUrls.add(invocation.getArgument(0));
            return ArticleContentResult.fullText("본문");
        });

        enricher.enrich(42L);

        assertEquals(List.of("https://example.com/high"), fetchedUrls);
    }

    @Test
    void fetchesRareKeywordMatchBeforeTwoCommonMatches() {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient contentClient = mock(ArticleContentClient.class);
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setFulltextLimitPerRun(1);
        TopicFitScorer weightedScorer = new TopicFitScorer((language, keywords) ->
                Map.of("반도체", 1.0d, "ai", 1.0d, "hbm4", 4.0d));
        ArticleContentEnricher enricher = new ArticleContentEnricher(
                repository,
                runItemRepository,
                contentClient,
                mock(RobotsTxtClient.class),
                mock(CollectionResultWriter.class),
                properties,
                weightedScorer);
        Topic topic = Topic.builder()
                .id(7L)
                .optionalKeywords(List.of("반도체", "AI", "HBM4"))
                .build();
        Source source = Source.builder()
                .id(8L)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_IGNORE, 30, true))
                .build();
        Article common = article(
                1L, "https://example.com/common", "반도체 AI 투자", source, topic);
        Article rare = article(2L, "https://example.com/rare", "HBM4 투자", source, topic);
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(List.of(
                observation(common, topic), observation(rare, topic)));
        List<String> fetchedUrls = new ArrayList<>();
        when(contentClient.fetch(any(), any(), any(), eq(false))).thenAnswer(invocation -> {
            fetchedUrls.add(invocation.getArgument(0));
            return ArticleContentResult.fullText("본문");
        });

        enricher.enrich(42L);

        assertEquals(List.of("https://example.com/rare"), fetchedUrls);
    }

    @Test
    void balancesObservedSourcesAndTechnicalTopicsBeforeSmallFulltextLimit() {
        Topic models = scope(7L, "AI 모델");
        Topic agents = scope(9L, "AI Agent");
        Source prolific = source(8L);
        Source specialist = source(10L);
        List<CollectionRunArticle> observations = List.of(
                observed(1L, models, prolific), observed(2L, models, prolific),
                observed(3L, models, prolific),
                // Globally shared article keeps its original publisher; balance its observed source.
                CollectionRunArticle.builder().article(candidate(4L, models, prolific))
                        .topic(models).source(specialist).build(),
                observed(5L, agents, prolific), observed(6L, agents, prolific));
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(
                captured(models, prolific), captured(models, specialist), captured(agents, prolific)));

        assertEquals(List.of(url(1), url(4), url(5)), fetchUrls(observations, 3));
    }

    @Test
    void preservesEveryNontechnicalSlotWhenBalancingTechnicalSources() {
        Topic models = scope(7L, "AI 모델");
        Topic industry = scope(9L, "AI 산업");
        Source prolific = source(8L);
        Source specialist = source(10L);
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(
                captured(models, prolific), captured(models, specialist), captured(industry, prolific)));

        assertEquals(List.of(url(1), url(2), url(6), url(4)), fetchUrls(List.of(
                observed(1L, models, prolific), observed(2L, industry, prolific),
                observed(3L, models, prolific), observed(4L, industry, prolific),
                observed(5L, models, prolific), observed(6L, models, specialist)), 4));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesCapturedScopeInsteadOfAnEditedLiveTopic(boolean capturedTechnical) {
        Topic topic = scope(7L, capturedTechnical ? "AI 모델" : "AI 산업");
        Source prolific = source(8L);
        Source specialist = source(10L);
        List<CollectionRunItem> items = List.of(captured(topic, prolific), captured(topic, specialist));
        String editedScope = capturedTechnical ? "AI 산업" : "AI 모델";
        topic.update(editedScope, editedScope, List.of(), List.of(), List.of(), 100, 1440, true);
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(items);

        assertEquals(List.of(url(1), url(capturedTechnical ? 3 : 2)), fetchUrls(List.of(
                observed(1L, topic, prolific), observed(2L, topic, prolific),
                observed(3L, topic, specialist)), 2));
    }

    @Test
    void keepsLegacyTechnicalRunsWithoutCapturedScopeInTheirExistingOrder() {
        Topic topic = scope(7L, "AI 모델");
        Source prolific = source(8L);
        assertEquals(List.of(url(1), url(2)), fetchUrls(List.of(
                observed(1L, topic, prolific), observed(2L, topic, prolific),
                observed(3L, topic, source(10L))), 2));
    }

    @Test
    void cachedFulltextAndRepeatedObservationsDoNotConsumeHttpSlots() {
        Topic models = scope(7L, "AI 모델");
        Source prolific = source(8L);
        Source specialist = source(10L);
        Article cached = Article.builder().id(0L).canonicalUrl(url(0)).title("AI 기술 소식")
                .topic(models).source(specialist).fetchStatus(FetchStatus.FULLTEXT).body("저장한 전문").build();
        CollectionRunArticle first = observed(1L, models, prolific);
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(
                captured(models, prolific), captured(models, specialist)));

        assertEquals(List.of(url(1), url(3)), fetchUrls(List.of(
                observation(cached, models), first, first,
                CollectionRunArticle.builder().article(first.getArticle()).topic(models).source(specialist).build(),
                observed(2L, models, prolific), observed(3L, models, specialist)), 2));
    }

    private List<String> fetchUrls(List<CollectionRunArticle> observations, int limit) {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient client = mock(ArticleContentClient.class);
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setFulltextLimitPerRun(limit);
        ArticleContentEnricher enricher = new ArticleContentEnricher(repository, runItemRepository, client,
                mock(RobotsTxtClient.class), mock(CollectionResultWriter.class), properties, equalWeightScorer());
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(observations);
        List<String> fetched = new ArrayList<>();
        when(client.fetch(any(), any(), any(), eq(false))).thenAnswer(invocation -> {
            fetched.add(invocation.getArgument(0));
            return ArticleContentResult.fullText("확보한 전문");
        });
        enricher.enrich(42L);
        return fetched;
    }

    private Topic scope(Long id, String scope) {
        return Topic.builder().id(id).name(scope).queryText(scope).requiredKeywords(List.of())
                .optionalKeywords(List.of()).excludedKeywords(List.of()).build();
    }

    private Source source(Long id) {
        return Source.builder().id(id)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_IGNORE, 30, true)).build();
    }

    private CollectionRunItem captured(Topic topic, Source source) {
        CollectionRunItem item = CollectionRunItem.builder().topic(topic).source(source).build();
        item.captureTopicSnapshot();
        return item;
    }

    private CollectionRunArticle observed(Long id, Topic topic, Source source) {
        return CollectionRunArticle.builder().article(candidate(id, topic, source)).topic(topic).source(source).build();
    }

    private Article candidate(Long id, Topic topic, Source source) {
        return article(id, url(id), "AI 기술 소식", source, topic);
    }

    private String url(long id) {
        return "https://example.com/article/" + id;
    }

    private Article article(Long id, String url, String title, Source source, Topic topic) {
        return Article.builder()
                .id(id)
                .canonicalUrl(url)
                .title(title)
                .summary("요약")
                .source(source)
                .topic(topic)
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .build();
    }

    private CollectionRunArticle observation(Article article, Topic topic) {
        return CollectionRunArticle.builder().article(article).topic(topic).build();
    }

    private TopicFitScorer equalWeightScorer() {
        return new TopicFitScorer((language, keywords) -> java.util.Map.of());
    }
}
