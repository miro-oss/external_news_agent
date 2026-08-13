package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.content.ArticleContentClient;
import com.example.be.domain.collection.content.ArticleContentResult;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.robots.RobotsLookup;
import com.example.be.domain.collection.robots.RobotsRules;
import com.example.be.domain.collection.robots.RobotsTxtClient;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * HTTP만 대역으로 두고 저장은 실제 DB에 한다. 본문이 실제로 붙는지와, 붙이면서 다른 값을 망가뜨리지 않는지를 본다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ArticleContentEnricherIntegrationTests {

    private static final String BODY = "본문 ".repeat(100);

    @Autowired
    private ArticleContentEnricher enricher;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private CollectionRunArticleRepository runArticleRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private ArticleContentClient contentClient;

    @MockitoBean
    private RobotsTxtClient robotsTxtClient;

    private Topic topic;
    private CollectionRun run;

    @BeforeEach
    void setUp() {
        topic = topicRepository.save(Topic.builder()
                .name("본문 추출 통합테스트 " + UUID.randomUUID())
                .queryText("HBM")
                .requiredKeywords(List.of())
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());

        run = runRepository.save(CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey("enrich-" + UUID.randomUUID())
                .forceRefresh(false)
                .startedAt(LocalDateTime.now())
                .build());

        given(robotsTxtClient.lookup(anyString()))
                .willReturn(RobotsLookup.fetched("https://example.com/robots.txt", RobotsRules.permitAll()));
    }

    @Test
    void fillsBodyForArticlesObservedInThisRun() {
        Article article = observedArticle(source(true));
        given(contentClient.fetch(anyString(), any())).willReturn(ArticleContentResult.fullText(BODY));

        enricher.enrich(run.getId());
        flushAndClear();

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(FetchStatus.FULLTEXT, reloaded.getFetchStatus());
        assertTrue(reloaded.getBody().startsWith("본문"));
        assertNotNull(reloaded.getUpdatedAt());
    }

    /**
     * ★ 본문 해시로 content_hash를 덮으면, 다음 실행이 제목+요약 지문과 비교하게 되어
     * 바뀌지도 않은 기사가 매번 UPDATED로 찍힌다.
     */
    @Test
    void keepsContentHashSoNextRunDoesNotSeeAFalseUpdate() {
        Article article = observedArticle(source(true));
        String before = article.getContentHash();
        given(contentClient.fetch(anyString(), any())).willReturn(ArticleContentResult.fullText(BODY));

        enricher.enrich(run.getId());
        flushAndClear();

        assertEquals(before, articleRepository.findById(article.getId()).orElseThrow().getContentHash());
    }

    /**
     * plan-final §4-3. 페이월 매체는 정책으로 꺼 두므로 요청 자체를 하지 않는다.
     */
    @Test
    void skipsSourcesThatDoNotAllowFullText() {
        Article article = observedArticle(source(false));

        enricher.enrich(run.getId());
        flushAndClear();

        assertEquals(FetchStatus.METADATA_ONLY,
                articleRepository.findById(article.getId()).orElseThrow().getFetchStatus());
        then(contentClient).shouldHaveNoInteractions();
    }

    @Test
    void marksBlockedArticlesAndLeavesOneWarningPerSource() {
        Source source = source(true);
        observedArticle(source);
        observedArticle(source);
        given(contentClient.fetch(anyString(), any())).willReturn(ArticleContentResult.blocked());

        enricher.enrich(run.getId());
        flushAndClear();

        CollectionRun reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertEquals(1, reloaded.getWarningCount());
        CollectionRunWarning warning = reloaded.getWarnings().get(0);
        assertEquals(CollectionRunWarning.CODE_FULLTEXT_BLOCKED, warning.getCode());
        // 기사마다 경고를 남기면 상세 화면이 도배된다. 소스별로 묶고 건수만 센다.
        assertEquals(2, warning.getArticleCount());
    }

    /**
     * 전문을 못 받아도 기사를 버리지 않는다. 제목과 링크만으로도 목록에는 쓸모가 있다.
     */
    @Test
    void keepsArticleWhenRobotsBlocksFullText() {
        Article article = observedArticle(source(true));
        given(robotsTxtClient.lookup(anyString())).willReturn(RobotsLookup.fetched(
                "https://example.com/robots.txt",
                RobotsRules.parse("User-agent: *\nDisallow: /\n", "external-news-agent")));

        enricher.enrich(run.getId());
        flushAndClear();

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(FetchStatus.ROBOTS_DISALLOWED, reloaded.getFetchStatus());
        assertNull(reloaded.getBody());
        assertNotNull(reloaded.getTitle());
        then(contentClient).shouldHaveNoInteractions();
    }

    private Source source(boolean fullTextAllowed) {
        return sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("본문 추출 소스")
                .urlTemplate("https://example.com/enrich-" + UUID.randomUUID())
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, fullTextAllowed))
                .active(true)
                .build());
    }

    private Article observedArticle(Source source) {
        String urlHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        Article article = articleRepository.save(Article.builder()
                .topic(topic)
                .source(source)
                .urlHash(urlHash)
                .canonicalUrl("https://example.com/article/" + urlHash)
                .title("HBM4 양산 시작")
                .summary("요약")
                .contentHash("metadata-fingerprint")
                .language("ko")
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(LocalDateTime.now())
                .build());

        runArticleRepository.save(CollectionRunArticle.observe(
                run, article, topic, source, ChangeType.NEW, LocalDateTime.now()));
        return article;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
