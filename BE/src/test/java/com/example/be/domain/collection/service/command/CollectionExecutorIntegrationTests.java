package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.connector.SearchConnector;
import com.example.be.domain.collection.connector.SearchConnectorRegistry;
import com.example.be.domain.collection.connector.dto.req.SearchQuery;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleVersion;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.feed.FeedClient;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.ArticleVersionRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.SearchProvider;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * 피드 HTTP만 대역으로 두고 나머지는 실제 DB에 쓴다. 판정 결과가 실제로 저장되는지가 이 작업의 핵심이다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CollectionExecutorIntegrationTests {

    private static final String FEED_URL_PREFIX = "https://example.com/executor-";

    @Autowired
    private CollectionExecutor collectionExecutor;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleVersionRepository articleVersionRepository;

    @Autowired
    private CollectionRunArticleRepository runArticleRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private FeedClient feedClient;

    @MockitoBean
    private SearchConnectorRegistry searchConnectorRegistry;

    private Topic topic;
    private Source source;
    private String articleUrl;

    @BeforeEach
    void setUp() {
        topic = topicRepository.save(Topic.builder()
                .name("엔진 통합테스트 " + UUID.randomUUID())
                .queryText("HBM")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of())
                .excludedKeywords(List.of("루머"))
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());

        source = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("엔진 통합테스트 소스")
                .urlTemplate(FEED_URL_PREFIX + UUID.randomUUID())
                .country("KR")
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_UNKNOWN)
                .active(true)
                .build());

        articleUrl = "https://www.hankyung.com/article/" + UUID.randomUUID();
    }

    @Test
    void savesNewArticleAndRecordsObservation() {
        givenFeed(article("HBM4 양산 시작", "삼성전자가 HBM4를 양산한다"));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        collectionExecutor.execute(run, item, topic, source);
        flushAndClear();

        Article saved = articleRepository.findAll().stream()
                .filter(candidate -> articleUrl.equals(candidate.getCanonicalUrl()))
                .findFirst()
                .orElseThrow();

        assertEquals("HBM4 양산 시작", saved.getTitle());
        assertEquals(FetchStatus.METADATA_ONLY, saved.getFetchStatus());
        assertEquals(64, saved.getUrlHash().length());
        assertNotNull(saved.getContentHash());
        assertEquals(RunItemStatus.SUCCESS, item.getStatus());
        assertEquals(1, item.getScannedCount());
        assertEquals(1, item.getNewCount());
        assertEquals(1, runArticleRepository.countByRunIdAndChangeType(run.getId(), ChangeType.NEW));
    }

    /**
     * FEED 소스에는 질의어가 없다. 키워드로 거르지 않으면 주제와 무관한 기사가 전부 들어온다.
     */
    @Test
    void dropsArticlesThatDoNotMatchTopicKeywords() {
        givenFeed(
                article("HBM4 양산 시작", "요약"),
                new CollectedArticle(
                        "파운드리 수주", "https://example.com/other", "요약", null, "example.com", "ko"),
                new CollectedArticle(
                        "HBM4 양산 루머", "https://example.com/rumor", "요약", null, "example.com", "ko"));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        collectionExecutor.execute(run, item, topic, source);
        flushAndClear();

        // scanned는 필터 전에 받은 건수다. 저장된 건 1건뿐이다.
        assertEquals(3, item.getScannedCount());
        assertEquals(1, item.getNewCount());
        assertEquals(2, item.getSkippedCount());
        assertEquals(1, runArticleRepository.findByRunIdOrderByIdAsc(run.getId()).size());
    }

    @Test
    void marksUnchangedWhenContentIsTheSame() {
        givenFeed(article("HBM4 양산 시작", "삼성전자가 HBM4를 양산한다"));
        CollectionRun firstRun = newRun();
        collectionExecutor.execute(firstRun, newItem(firstRun), topic, source);
        flushAndClear();

        CollectionRun secondRun = newRun();
        CollectionRunItem secondItem = newItem(secondRun);
        collectionExecutor.execute(secondRun, secondItem, topic, source);
        flushAndClear();

        assertEquals(0, secondItem.getNewCount());
        assertEquals(0, secondItem.getUpdatedCount());
        assertEquals(1, runArticleRepository.countByRunIdAndChangeType(secondRun.getId(), ChangeType.UNCHANGED));
        assertEquals(1, articleRepository.findAll().stream()
                .filter(candidate -> articleUrl.equals(candidate.getCanonicalUrl()))
                .count());
    }

    /**
     * 기사 정정은 드물지 않다. 직전 상태를 버전으로 남기지 않으면 무엇이 바뀌었는지 추적할 수 없다(§2-8).
     */
    @Test
    void keepsPreviousStateAsVersionWhenContentChanged() {
        givenFeed(article("HBM4 양산 시작", "삼성전자가 HBM4를 양산한다"));
        CollectionRun firstRun = newRun();
        collectionExecutor.execute(firstRun, newItem(firstRun), topic, source);
        flushAndClear();

        givenFeed(article("HBM4 양산 시작 (정정)", "양산 일정이 앞당겨졌다"));
        CollectionRun secondRun = newRun();
        CollectionRunItem secondItem = newItem(secondRun);
        collectionExecutor.execute(secondRun, secondItem, topic, source);
        flushAndClear();

        Article updated = articleRepository.findAll().stream()
                .filter(candidate -> articleUrl.equals(candidate.getCanonicalUrl()))
                .findFirst()
                .orElseThrow();

        assertEquals("HBM4 양산 시작 (정정)", updated.getTitle());
        assertEquals(1, secondItem.getUpdatedCount());
        assertEquals(1, runArticleRepository.countByRunIdAndChangeType(secondRun.getId(), ChangeType.UPDATED));

        List<ArticleVersion> versions = articleVersionRepository.findByArticleIdOrderByVersionNoAsc(updated.getId());
        assertEquals(1, versions.size());
        assertEquals("HBM4 양산 시작", versions.get(0).getTitle());
        assertEquals(ArticleVersion.FIRST_VERSION_NO, versions.get(0).getVersionNo());
    }

    @Test
    void limitsArticlesToCrawlPolicy() {
        Source limited = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("정책 제한 소스")
                .urlTemplate(FEED_URL_PREFIX + UUID.randomUUID())
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 2, true))
                .active(true)
                .build());
        givenFeed(
                article("HBM4 첫 번째", "요약"),
                new CollectedArticle(
                        "HBM4 두 번째", "https://example.com/2", "요약", null, "example.com", "ko"),
                new CollectedArticle(
                        "HBM4 세 번째", "https://example.com/3", "요약", null, "example.com", "ko"));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run, limited);

        collectionExecutor.execute(run, item, topic, limited);
        flushAndClear();

        assertEquals(3, item.getScannedCount());
        assertEquals(2, item.getNewCount());
    }

    /**
     * 404 난 피드가 SUCCESS 0건으로 기록되면 화면에서 "왜 기사가 없지?"를 설명할 수 없다.
     * 기사가 정말 0건인 피드와 읽기 실패는 다른 사건이다.
     */
    @Test
    void marksItemFailedWhenFeedCouldNotBeRead() {
        given(feedClient.fetch(anyString(), any()))
                .willReturn(FetchResult.unreadable("피드 응답 404 NOT_FOUND"));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        collectionExecutor.execute(run, item, topic, source);

        assertEquals(RunItemStatus.FAILED, item.getStatus());
        assertEquals(0, item.getScannedCount());
        assertEquals(1, run.getWarningCount());
        assertEquals(CollectionRunWarning.CODE_FEED_UNREADABLE, run.getWarnings().get(0).getCode());
    }

    /**
     * 항목 없는 정상 피드는 실패가 아니다.
     */
    @Test
    void marksItemSuccessWhenFeedIsLegitimatelyEmpty() {
        givenFeed();
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        collectionExecutor.execute(run, item, topic, source);

        assertEquals(RunItemStatus.SUCCESS, item.getStatus());
        assertEquals(0, run.getWarningCount());
    }

    /**
     * 한 기사를 여러 섹션에 중복 노출하는 피드가 있다. 접지 않으면 uq_run_article을 위반해
     * 조합 전체가 FAILED가 된다.
     */
    @Test
    void collapsesDuplicateUrlsWithinOneCombination() {
        givenFeed(
                article("HBM4 양산 시작", "요약"),
                article("HBM4 양산 시작", "요약"));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        collectionExecutor.execute(run, item, topic, source);
        flushAndClear();

        assertEquals(RunItemStatus.SUCCESS, item.getStatus());
        assertEquals(2, item.getScannedCount());
        assertEquals(1, item.getNewCount());
        assertEquals(1, runArticleRepository.findByRunIdOrderByIdAsc(run.getId()).size());
    }

    /**
     * 검색 커넥터도 피드와 같은 계약이다. 키가 없어 호출조차 못 한 소스가 SUCCESS 0건으로 기록되면
     * "네이버 키를 안 넣었다"는 사실이 아무 데도 남지 않는다.
     */
    @Test
    void marksItemFailedWhenSearchConnectorReports() {
        // (SEARCH, NAVER)에는 유니크 제약이 있고 로컬에 이미 등록돼 있을 수 있다. 있으면 그걸 쓴다.
        Source searchSource = sourceRepository.findAll().stream()
                .filter(candidate -> Source.KIND_SEARCH.equals(candidate.getSourceKind())
                        && SearchProvider.NAVER.name().equals(candidate.getUrlTemplate()))
                .findFirst()
                .orElseGet(() -> sourceRepository.save(Source.builder()
                        .sourceKind(Source.KIND_SEARCH)
                        .name("검색 소스")
                        .urlTemplate(SearchProvider.NAVER.name())
                        .language("ko")
                        .active(true)
                        .build()));
        given(searchConnectorRegistry.find(SearchProvider.NAVER))
                .willReturn(Optional.of(new StubConnector(
                        FetchResult.providerKeyMissing("NAVER_CLIENT_ID가 설정되지 않았다."))));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run, searchSource);

        collectionExecutor.execute(run, item, topic, searchSource);

        assertEquals(RunItemStatus.FAILED, item.getStatus());
        assertEquals(1, run.getWarningCount());
        assertEquals(CollectionRunWarning.CODE_PROVIDER_KEY_MISSING, run.getWarnings().get(0).getCode());
    }

    private record StubConnector(FetchResult result) implements SearchConnector {

        @Override
        public SearchProvider provider() {
            return SearchProvider.NAVER;
        }

        @Override
        public FetchResult search(SearchQuery query) {
            return result;
        }
    }

    /**
     * 조합 하나가 죽어도 실행은 계속돼야 한다. 사유는 경고로 남아 화면에서 보인다.
     */
    @Test
    void recordsWarningInsteadOfPropagatingFailure() {
        willThrow(new IllegalStateException("피드 서버가 응답하지 않는다"))
                .given(feedClient).fetch(anyString(), any());
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        collectionExecutor.execute(run, item, topic, source);

        assertEquals(RunItemStatus.FAILED, item.getStatus());
        assertEquals(1, run.getWarningCount());
        assertTrue(run.getWarnings().get(0).getMessage().contains("응답하지 않는다"));
    }

    private void givenFeed(CollectedArticle... articles) {
        given(feedClient.fetch(anyString(), any())).willReturn(FetchResult.ok(List.of(articles)));
    }

    private CollectedArticle article(String title, String summary) {
        return new CollectedArticle(
                title, articleUrl, summary, null, "www.hankyung.com", "ko");
    }

    private CollectionRun newRun() {
        CollectionRun run = CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey("executor-" + UUID.randomUUID())
                .forceRefresh(false)
                .startedAt(LocalDateTime.now())
                .build();
        return runRepository.save(run);
    }

    private CollectionRunItem newItem(CollectionRun run) {
        return newItem(run, source);
    }

    private CollectionRunItem newItem(CollectionRun run, Source itemSource) {
        CollectionRunItem item = CollectionRunItem.builder()
                .topic(topic)
                .source(itemSource)
                .status(RunItemStatus.RUNNING)
                .build();
        run.addItem(item);
        return item;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
