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
import com.example.be.domain.collection.feed.FeedFetch;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.collection.robots.RobotsPolicyService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
    private CollectionResultWriter resultWriter;

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

    /** 대역으로 두지 않으면 테스트가 실제 네트워크로 robots.txt를 부른다. */
    @MockitoBean
    private RobotsPolicyService robotsPolicyService;

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

        given(robotsPolicyService.evaluate(any())).willReturn(allowedRobots());
    }

    @Test
    void savesNewArticleAndRecordsObservation() {
        givenFeed(article("HBM4 양산 시작", "삼성전자가 HBM4를 양산한다"));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        execute(run, item, topic, source);
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

        execute(run, item, topic, source);
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
        execute(firstRun, newItem(firstRun), topic, source);
        flushAndClear();

        CollectionRun secondRun = newRun();
        CollectionRunItem secondItem = newItem(secondRun);
        execute(secondRun, secondItem, topic, source);
        flushAndClear();

        assertEquals(0, secondItem.getNewCount());
        assertEquals(0, secondItem.getUpdatedCount());
        assertEquals(1, runArticleRepository.countByRunIdAndChangeType(secondRun.getId(), ChangeType.UNCHANGED));
        assertEquals(1, articleRepository.findAll().stream()
                .filter(candidate -> articleUrl.equals(candidate.getCanonicalUrl()))
                .count());
    }

    @Test
    void deduplicatesTrackedUrlsAndStoresNormalizedCanonicalUrlAcrossRuns() {
        String firstTrackedUrl = articleUrl + "?utm_source=newsletter#headline";
        String duplicateTrackedUrl = articleUrl + "?fbclid=click-id";
        givenFeed(
                new CollectedArticle(
                        "HBM4 양산 시작", firstTrackedUrl, "삼성전자가 HBM4를 양산한다", null,
                        "www.hankyung.com", "ko"),
                new CollectedArticle(
                        "HBM4 양산 시작", duplicateTrackedUrl, "삼성전자가 HBM4를 양산한다", null,
                        "www.hankyung.com", "ko"));
        CollectionRun firstRun = newRun();
        CollectionRunItem firstItem = newItem(firstRun);

        execute(firstRun, firstItem, topic, source);
        flushAndClear();

        Article saved = articleRepository.findAll().stream()
                .filter(candidate -> articleUrl.equals(candidate.getCanonicalUrl()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, firstItem.getScannedCount());
        assertEquals(1, firstItem.getNewCount());
        assertEquals(1, firstItem.getSkippedCount());
        assertEquals(articleUrl, saved.getCanonicalUrl());
        assertEquals(1, runArticleRepository.findByRunIdOrderByIdAsc(firstRun.getId()).size());

        givenFeed(article("HBM4 양산 시작", "삼성전자가 HBM4를 양산한다"));
        CollectionRun secondRun = newRun();
        CollectionRunItem secondItem = newItem(secondRun);
        execute(secondRun, secondItem, topic, source);
        flushAndClear();

        assertEquals(0, secondItem.getNewCount());
        assertEquals(1,
                runArticleRepository.countByRunIdAndChangeType(secondRun.getId(), ChangeType.UNCHANGED));
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
        execute(firstRun, newItem(firstRun), topic, source);
        flushAndClear();

        Article first = articleRepository.findAll().stream()
                .filter(candidate -> articleUrl.equals(candidate.getCanonicalUrl()))
                .findFirst()
                .orElseThrow();
        first.applyFullText("정정 전 전문", FetchStatus.FULLTEXT, LocalDateTime.now());
        flushAndClear();

        givenFeed(article("HBM4 양산 시작 (정정)", "양산 일정이 앞당겨졌다"));
        CollectionRun secondRun = newRun();
        CollectionRunItem secondItem = newItem(secondRun);
        execute(secondRun, secondItem, topic, source);
        flushAndClear();

        Article updated = articleRepository.findAll().stream()
                .filter(candidate -> articleUrl.equals(candidate.getCanonicalUrl()))
                .findFirst()
                .orElseThrow();

        assertEquals("HBM4 양산 시작 (정정)", updated.getTitle());
        assertEquals(FetchStatus.METADATA_ONLY, updated.getFetchStatus());
        assertEquals("정정 전 전문", updated.getBody());
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

        execute(run, item, topic, limited);
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
        given(feedClient.fetch(any()))
                .willReturn(new FeedFetch(FetchResult.unreadable("피드 응답 404 NOT_FOUND"), false, null, null));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        execute(run, item, topic, source);

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

        execute(run, item, topic, source);

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

        execute(run, item, topic, source);
        flushAndClear();

        assertEquals(RunItemStatus.SUCCESS, item.getStatus());
        assertEquals(2, item.getScannedCount());
        assertEquals(1, item.getNewCount());
        assertEquals(1, runArticleRepository.findByRunIdOrderByIdAsc(run.getId()).size());
    }

    /**
     * robots.txt가 막은 소스는 요청하지 않는다. 정책대로 동작한 것이므로 실패가 아니라 SKIPPED다 —
     * FAILED로 적으면 실행이 매번 PARTIAL이 된다.
     */
    @Test
    void skipsSourceBlockedByRobots() {
        given(robotsPolicyService.evaluate(any())).willReturn(new RobotsDecision(
                false, Source.ROBOTS_STATUS_DISALLOWED, LocalDateTime.now(),
                "https://example.com/robots.txt", null, null));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        execute(run, item, topic, source);

        assertEquals(RunItemStatus.SKIPPED, item.getStatus());
        assertEquals(1, run.getWarningCount());
        assertEquals(CollectionRunWarning.CODE_ROBOTS_DISALLOWED, run.getWarnings().get(0).getCode());
        then(feedClient).shouldHaveNoInteractions();
    }

    /**
     * 304도 실패가 아니다. 바뀐 게 없다는 뜻이라 경고를 남기지 않는다.
     */
    @Test
    void skipsSourceWhenFeedIsNotModified() {
        given(feedClient.fetch(any()))
                .willReturn(new FeedFetch(FetchResult.ok(List.of()), true, "\"v1\"", null));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        execute(run, item, topic, source);

        assertEquals(RunItemStatus.SKIPPED, item.getStatus());
        assertEquals(0, run.getWarningCount());
        assertEquals("\"v1\"", source.getEtag());
    }

    /**
     * 다음 실행이 조건부 GET을 보낼 수 있도록 검증자를 소스에 남긴다.
     */
    @Test
    void storesValidatorsOnSource() {
        given(feedClient.fetch(any())).willReturn(new FeedFetch(
                FetchResult.ok(List.of(article("HBM4 양산 시작", "요약"))), false,
                "\"v2\"", "Mon, 10 Aug 2026 09:00:00 GMT"));
        CollectionRun run = newRun();

        execute(run, newItem(run), topic, source);

        assertEquals("\"v2\"", source.getEtag());
        assertEquals("Mon, 10 Aug 2026 09:00:00 GMT", source.getLastModified());
        assertNotNull(source.getLastFetchedAt());
    }

    private RobotsDecision allowedRobots() {
        return new RobotsDecision(true, Source.ROBOTS_STATUS_ALLOWED, LocalDateTime.now(),
                "https://example.com/robots.txt", null, null);
    }

    /**
     * 503 한 번에 저장해 둔 ETag가 사라지면 다음 실행부터 피드를 통째로 다시 받는다.
     */
    @Test
    void keepsStoredValidatorsWhenFetchFails() {
        source.applyFetchState("\"v1\"", "Mon, 10 Aug 2026 09:00:00 GMT", LocalDateTime.now());
        given(feedClient.fetch(any()))
                .willReturn(new FeedFetch(FetchResult.rateLimited("피드 응답 503"), false, null, null));
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        execute(run, item, topic, source);

        assertEquals(RunItemStatus.FAILED, item.getStatus());
        assertEquals("\"v1\"", source.getEtag());
        assertEquals("Mon, 10 Aug 2026 09:00:00 GMT", source.getLastModified());
    }

    /**
     * robots 판정도 저장돼야 목록 화면이 소스마다 robots.txt를 다시 받지 않는다.
     */
    @Test
    void storesRobotsDecisionOnSource() {
        givenFeed(article("HBM4 양산 시작", "요약"));
        CollectionRun run = newRun();

        execute(run, newItem(run), topic, source);

        assertEquals(Source.ROBOTS_STATUS_ALLOWED, source.getRobotsStatus());
        assertNotNull(source.getRobotsCheckedAt());
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

        execute(run, item, topic, searchSource);

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
                .given(feedClient).fetch(any());
        CollectionRun run = newRun();
        CollectionRunItem item = newItem(run);

        execute(run, item, topic, source);

        assertEquals(RunItemStatus.FAILED, item.getStatus());
        assertEquals(1, run.getWarningCount());
        assertTrue(run.getWarnings().get(0).getMessage().contains("응답하지 않는다"));
    }

    private void givenFeed(CollectedArticle... articles) {
        given(feedClient.fetch(any())).willReturn(new FeedFetch(FetchResult.ok(List.of(articles)), false, null, null));
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
        // 운영 경로가 조합을 id로 찾으므로 여기서 id가 붙어 있어야 한다. 저장은 run에서 cascade된다.
        entityManager.flush();
        return item;
    }

    /**
     * 운영 경로와 같은 진입점으로 부른다. 수집은 트랜잭션 밖에서 돌아 엔티티가 detached가 되므로
     * 실행·조합은 id로 넘기고 writer가 다시 읽는다(#27). 테스트만 엔티티로 부르면
     * 그 경로에서 나는 문제가 테스트에 걸리지 않는다.
     */
    private void execute(CollectionRun run, CollectionRunItem item, Topic itemTopic, Source itemSource) {
        collectionExecutor.execute(run.getId(), item.getId(), itemTopic, itemSource, run.isForceRefresh());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * 실행을 무조건 FAILED로 닫으면 앞에서 성공한 조합이 묻힌다. 안 끝난 조합만 실패로 닫고
     * 나머지는 저장된 결과 그대로 둬야 PARTIAL이 나온다.
     *
     * <p><b>{@code failRun}이 아니라 그것이 위임하는 {@code abortRun}을 부른다.</b> {@code failRun}은
     * {@code REQUIRES_NEW}라 이 테스트의 트랜잭션을 밀어내고 새 트랜잭션에서 실행을 찾는데,
     * 여기서 만든 실행은 아직 커밋 전이라 그쪽에서 보이지 않는다 — 조용히 아무것도 하지 않고 끝난다.
     * 여기서 고정하려는 것은 propagation이 아니라 PARTIAL / FAILED 판정이고, 그 로직은 둘이 공유한다.
     * {@code REQUIRES_NEW}로 커밋되는지는 커밋을 실제로 일으키는
     * {@code CollectionResultWriterIntegrationTests}가 본다.
     */
    @Test
    void abortRunKeepsPartialWhenSomeCombinationsAlreadySucceeded() {
        givenFeed(article("HBM4 양산 시작", "요약"));
        CollectionRun run = newRun();
        CollectionRunItem succeeded = newItem(run);
        CollectionRunItem stillRunning = newItem(run, otherSource());
        execute(run, succeeded, topic, source);
        runRepository.saveAndFlush(run);

        resultWriter.abortRun(run.getId(), CollectionRunWarning.CODE_RUN_REJECTED,
                "수집 작업이 거절되어 실행을 시작하지 못했습니다.");
        flushAndClear();

        CollectionRun reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertEquals(RunStatus.PARTIAL, reloaded.getStatus());
        assertNotNull(reloaded.getFinishedAt());
        assertEquals(RunItemStatus.SUCCESS, succeeded.getStatus());
        assertEquals(RunItemStatus.FAILED, stillRunning.getStatus());
    }

    private Source otherSource() {
        return sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("두 번째 소스")
                .urlTemplate(FEED_URL_PREFIX + UUID.randomUUID())
                .language("ko")
                .active(true)
                .build());
    }
}
