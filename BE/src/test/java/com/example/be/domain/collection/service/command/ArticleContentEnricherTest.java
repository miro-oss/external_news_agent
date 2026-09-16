package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.content.ArticleContentClient;
import com.example.be.domain.collection.content.ArticleContentResult;
import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passesTheArticleTitleWithOrWithoutRobotsChecks(boolean respectRobots) {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient contentClient = mock(ArticleContentClient.class);
        RobotsTxtClient robotsClient = mock(RobotsTxtClient.class);
        CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
        ArticleContentEnricher enricher = new ArticleContentEnricher(repository, contentClient, robotsClient,
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
