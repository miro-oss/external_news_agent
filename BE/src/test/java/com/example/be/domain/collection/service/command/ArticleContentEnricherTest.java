package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.content.ArticleContentClient;
import com.example.be.domain.collection.content.ArticleContentResult;
import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleContentEnricherTest {

    @Test
    void fetchesHigherMetadataFitFirst() {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient contentClient = mock(ArticleContentClient.class);
        CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        ArticleContentEnricher enricher = new ArticleContentEnricher(
                repository, contentClient, mock(RobotsTxtClient.class), resultWriter, properties);
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
        when(contentClient.fetch(any(), any())).thenAnswer(invocation -> {
            fetchedUrls.add(invocation.getArgument(0));
            return ArticleContentResult.fullText("본문");
        });

        enricher.enrich(42L);

        assertEquals(List.of("https://example.com/high", "https://example.com/low"), fetchedUrls);
    }

    @Test
    void limitsFulltextRequestsAfterMetadataFitOrdering() {
        CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
        ArticleContentClient contentClient = mock(ArticleContentClient.class);
        CollectionPipelineProperties properties = new CollectionPipelineProperties();
        properties.setFulltextLimitPerRun(1);
        ArticleContentEnricher enricher = new ArticleContentEnricher(
                repository,
                contentClient,
                mock(RobotsTxtClient.class),
                mock(CollectionResultWriter.class),
                properties);
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
        when(contentClient.fetch(any(), any())).thenAnswer(invocation -> {
            fetchedUrls.add(invocation.getArgument(0));
            return ArticleContentResult.fullText("본문");
        });

        enricher.enrich(42L);

        assertEquals(List.of("https://example.com/high"), fetchedUrls);
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
}
