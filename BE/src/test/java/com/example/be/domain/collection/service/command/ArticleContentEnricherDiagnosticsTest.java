package com.example.be.domain.collection.service.command;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.content.ArticleContentClient;
import com.example.be.domain.collection.content.ArticleContentFailureReason;
import com.example.be.domain.collection.content.ArticleContentResult;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.be.domain.collection.content.ArticleContentFailureReason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArticleContentEnricherDiagnosticsTest {

    private final CollectionRunArticleRepository repository = mock(CollectionRunArticleRepository.class);
    private final ArticleContentClient client = mock(ArticleContentClient.class);
    private final RobotsTxtClient robots = mock(RobotsTxtClient.class);
    private final CollectionResultWriter writer = mock(CollectionResultWriter.class);
    private final Source source = Source.builder().id(8L)
            .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_IGNORE, 30, true)).build();
    private final Topic topic = Topic.builder().id(7L).optionalKeywords(List.of("금리")).build();
    private final ArticleContentEnricher enricher = new ArticleContentEnricher(repository, client,
            robots, writer, new CollectionPipelineProperties(),
            new TopicFitScorer((language, keywords) -> Map.of()));
    private final Logger logger = (Logger) LoggerFactory.getLogger(ArticleContentEnricher.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void collectLogs() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
        logs.stop();
    }

    @Test
    void aggregatesReasonsOnceAndNeverLogsUrlsTitlesOrBodies() {
        List<Article> articles = List.of(article(1L), article(2L), article(3L), article(4L), article(5L));
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(articles.stream().map(this::observation).toList());
        when(client.fetch(anyString(), isNull(), anyString(), eq(false)))
                .thenReturn(ArticleContentResult.failed(TLS_VALIDATION),
                        ArticleContentResult.failed(TLS_VALIDATION),
                        ArticleContentResult.failed(TIMEOUT),
                        ArticleContentResult.fullText("private-body-payload"),
                        ArticleContentResult.blocked());

        assertEquals(Set.of(4L), enricher.enrich(42L));

        assertDiagnostic(5, 1, Map.of(TLS_VALIDATION, 2, TIMEOUT, 1, HTTP_ACCESS_DENIED, 1));
        verify(writer).applyFullText(1L, FetchStatus.FETCH_FAILED, null);
        verify(writer).applyFullText(4L, FetchStatus.FULLTEXT, "private-body-payload");
        verify(writer).addFullTextBlockedWarning(42L, 8L, 1);
    }

    @Test
    void directArticleEnrichmentKeepsTheFailureReasonInItsSummary() {
        Article article = article(1L);
        when(repository.findForEnrichment(42L, 1L)).thenReturn(List.of(observation(article)));
        when(client.fetch(anyString(), isNull(), anyString(), eq(false)))
                .thenReturn(ArticleContentResult.failed(BODY_NOT_FOUND));

        assertEquals(Set.of(), enricher.enrichArticle(42L, 1L));

        assertDiagnostic(1, 0, Map.of(BODY_NOT_FOUND, 1));
        verify(writer).applyFullText(1L, FetchStatus.FETCH_FAILED, null);
    }

    @Test
    void cancellationStopsTheRemainingArticleRequests() {
        when(repository.findClusterTargetsByRunId(42L))
                .thenReturn(List.of(observation(article(1L)), observation(article(2L))));
        when(client.fetch(anyString(), isNull(), anyString(), eq(false)))
                .thenReturn(ArticleContentResult.failed(INTERRUPTED));

        assertEquals(Set.of(), enricher.enrich(42L));

        verify(client, times(1)).fetch(anyString(), isNull(), anyString(), eq(false));
        verify(writer, never()).applyFullText(eq(2L), any(), any());
        assertDiagnostic(1, 0, Map.of(INTERRUPTED, 1));
    }

    @Test
    void cancellationBeforeEnrichmentSkipsRobotsAndArticleNetworkRequests() {
        Source respectingSource = Source.builder().id(9L)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true)).build();
        Article article = Article.builder().id(1L).source(respectingSource).topic(topic)
                .title("private-title-payload").canonicalUrl("https://example.com/private-url-payload")
                .fetchStatus(FetchStatus.METADATA_ONLY).build();
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(List.of(observation(article)));

        try {
            Thread.currentThread().interrupt();
            assertEquals(Set.of(), enricher.enrich(42L));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(robots, client);
        } finally {
            Thread.interrupted();
        }
        assertDiagnostic(1, 0, Map.of(INTERRUPTED, 1));
    }

    @Test
    void invalidArticleUrlIsDiagnosedBeforeCallingTheClient() {
        Article article = Article.builder().id(1L).source(source).topic(topic)
                .title("private-title-payload").canonicalUrl("not a URI").fetchStatus(FetchStatus.METADATA_ONLY).build();
        when(repository.findClusterTargetsByRunId(42L)).thenReturn(List.of(observation(article)));

        enricher.enrich(42L);

        verifyNoInteractions(client);
        assertDiagnostic(1, 0, Map.of(INVALID_URL, 1));
    }

    private void assertDiagnostic(int attempted, int fullText, Map<ArticleContentFailureReason, Integer> reasons) {
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.getFirst();
        Object[] arguments = event.getArgumentArray();
        assertArrayEquals(new Object[]{42L, attempted, fullText, reasons}, arguments);
        String message = event.getFormattedMessage();
        assertFalse(message.contains("example.com"));
        assertFalse(message.contains("private-"));
        assertNull(event.getThrowableProxy());
    }

    private Article article(Long id) {
        return Article.builder().id(id).source(source).topic(topic)
                .title("private-title-payload").summary("private-summary-payload")
                .canonicalUrl("https://example.com/private-url-payload/" + id)
                .fetchStatus(FetchStatus.METADATA_ONLY).build();
    }

    private CollectionRunArticle observation(Article article) {
        return CollectionRunArticle.builder().article(article).topic(topic).build();
    }
}
