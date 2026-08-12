package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleVersion;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4 스키마와 엔티티 매핑을 함께 검증한다. ddl-auto=validate라 매핑이 어긋나면 컨텍스트 로딩부터 실패한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CollectionRunRepositoryIntegrationTests {

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private CollectionRunItemRepository runItemRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleVersionRepository articleVersionRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Topic topic;
    private Source source;
    private Source otherSource;

    @BeforeEach
    void setUp() {
        topic = topicRepository.save(Topic.builder()
                .name("수집 실행 통합테스트 " + UUID.randomUUID())
                .queryText("HBM")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());

        source = sourceRepository.save(feedSource("수집 실행 통합테스트 소스"));
        otherSource = sourceRepository.save(feedSource("수집 실행 통합테스트 소스 2"));
    }

    private Source feedSource(String name) {
        return Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name(name)
                .urlTemplate("https://example.com/run-" + UUID.randomUUID())
                .country("KR")
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_UNKNOWN)
                .active(true)
                .build();
    }

    @Test
    void appliesSchemaMigration() {
        List<String> versions = jdbcTemplate.queryForList("""
                SELECT "version"
                FROM "flyway_schema_history"
                WHERE "success" = 1
                ORDER BY "installed_rank"
                """, String.class);

        assertTrue(versions.contains("4"));
    }

    @Test
    void savesRunWithBreakdownAndWarnings() {
        CollectionRun run = newRun("run-with-breakdown");
        run.addItem(item(source, RunItemStatus.SUCCESS, 50, 9, 2));
        run.addWarning(warning(CollectionRunWarning.CODE_FULLTEXT_BLOCKED, 5));

        CollectionRun saved = runRepository.save(run);
        flushAndClear();

        CollectionRun found = runRepository.findById(saved.getId()).orElseThrow();
        assertEquals(TriggerType.MANUAL, found.getTriggerType());
        assertEquals(1, found.getItems().size());
        assertEquals(1, found.getWarningCount());
        assertEquals(topic.getId(), found.getItems().get(0).getTopic().getId());
        assertEquals(CollectionRunWarning.CODE_FULLTEXT_BLOCKED, found.getWarnings().get(0).getCode());
    }

    /**
     * 조합 하나가 실패해도 나머지가 살아 있으면 실행은 PARTIAL이다. FAILED로 적으면 성공한 수집이 묻힌다.
     */
    @Test
    void aggregatesCountsAndResolvesPartialStatus() {
        CollectionRun run = newRun("run-partial");
        run.addItem(item(source, RunItemStatus.SUCCESS, 50, 9, 2));
        run.addItem(item(otherSource, RunItemStatus.FAILED, 0, 0, 0));

        run.finish(LocalDateTime.now());
        CollectionRun saved = runRepository.save(run);
        flushAndClear();

        CollectionRun found = runRepository.findById(saved.getId()).orElseThrow();
        assertEquals(RunStatus.PARTIAL, found.getStatus());
        assertEquals(50, found.getScannedCount());
        assertEquals(9, found.getNewCount());
        assertEquals(2, found.getUpdatedCount());
        assertEquals(39, found.getSkippedCount());
        assertNotNull(found.getFinishedAt());
    }

    @Test
    void resolvesFailedOnlyWhenEveryItemFailed() {
        CollectionRun run = newRun("run-failed");
        run.addItem(item(source, RunItemStatus.FAILED, 0, 0, 0));
        run.addItem(item(otherSource, RunItemStatus.FAILED, 0, 0, 0));

        run.finish(LocalDateTime.now());

        assertEquals(RunStatus.FAILED, run.getStatus());
    }

    @Test
    void findsInProgressRunByIdempotencyKey() {
        CollectionRun run = newRun("idem-key-" + UUID.randomUUID());
        run.start();
        runRepository.save(run);
        flushAndClear();

        assertTrue(runRepository.findFirstByIdempotencyKeyAndStatusIn(
                run.getIdempotencyKey(), List.of(RunStatus.PENDING, RunStatus.RUNNING)).isPresent());
        assertTrue(runRepository.findFirstByIdempotencyKeyAndStatusIn(
                run.getIdempotencyKey(), List.of(RunStatus.SUCCESS)).isEmpty());
    }

    /**
     * 버튼 연타로 동시에 들어와도 DB에서 막힌다. 애플리케이션 조회만으로는 경합을 못 막는다.
     */
    @Test
    void rejectsSecondInProgressRunWithSameIdempotencyKey() {
        String key = "duplicate-" + UUID.randomUUID();
        runRepository.save(newRunInProgress(key));
        flushAndClear();

        assertThrows(DataIntegrityViolationException.class,
                () -> runRepository.save(newRunInProgress(key)));
    }

    /**
     * 끝난 실행의 키는 다시 쓸 수 있어야 한다. 전역 유니크를 걸면 같은 키로 두 번 실행하지 못한다.
     */
    @Test
    void allowsReusingIdempotencyKeyAfterRunFinished() {
        String key = "reusable-" + UUID.randomUUID();
        CollectionRun finished = newRunInProgress(key);
        finished.finish(LocalDateTime.now());
        runRepository.save(finished);
        flushAndClear();

        runRepository.save(newRunInProgress(key));

        entityManager.flush();
    }

    @Test
    void findsInProgressRunsByTopic() {
        CollectionRun run = newRun("topic-conflict-" + UUID.randomUUID());
        run.start();
        run.addItem(item(source, RunItemStatus.RUNNING, 0, 0, 0));
        CollectionRun saved = runRepository.save(run);
        flushAndClear();

        List<CollectionRun> conflicts = runRepository.findInProgressByTopicIds(
                List.of(topic.getId()), List.of(RunStatus.PENDING, RunStatus.RUNNING));

        assertEquals(1, conflicts.size());
        assertEquals(saved.getId(), conflicts.get(0).getId());
        assertEquals(1, runItemRepository.findByRunIdOrderByIdAsc(saved.getId()).size());
    }

    @Test
    void countsWarningsWithoutLoadingThem() {
        CollectionRun run = newRun("warning-count");
        run.addWarning(warning(CollectionRunWarning.CODE_FULLTEXT_BLOCKED, 5));
        run.addWarning(warning(CollectionRunWarning.CODE_RATE_LIMITED, 0));
        CollectionRun saved = runRepository.save(run);
        flushAndClear();

        List<CollectionRunRepository.WarningCount> counts =
                runRepository.countWarnings(List.of(saved.getId()));

        assertEquals(1, counts.size());
        assertEquals(saved.getId(), counts.get(0).getRunId());
        assertEquals(2, counts.get(0).getWarningCount());
    }

    /**
     * 해외 기사는 오프셋이 의미를 갖는다. TIMESTAMP로 저장하면 +09:00이 사라져 발행 시각이 어긋난다.
     */
    @Test
    void keepsPublishedAtOffset() {
        CollectionRun run = runRepository.save(newRun("article-run"));
        OffsetDateTime publishedAt = OffsetDateTime.of(2026, 8, 10, 9, 0, 0, 0, ZoneOffset.ofHours(9));
        Article saved = articleRepository.save(article(run, randomHash(), publishedAt));
        flushAndClear();

        Article found = articleRepository.findById(saved.getId()).orElseThrow();
        assertTrue(publishedAt.isEqual(found.getPublishedAt()));
        assertEquals(FetchStatus.METADATA_ONLY, found.getFetchStatus());
        assertEquals(source.getId(), found.getSource().getId());
    }

    @Test
    void findsArticleByUrlHashAndRejectsDuplicate() {
        CollectionRun run = runRepository.save(newRun("dedupe-run"));
        String urlHash = randomHash();
        articleRepository.save(article(run, urlHash, OffsetDateTime.now()));
        flushAndClear();

        assertTrue(articleRepository.findByUrlHash(urlHash).isPresent());
        assertTrue(articleRepository.existsByUrlHash(urlHash));
        assertFalse(articleRepository.existsByUrlHash(randomHash()));

        assertThrows(DataIntegrityViolationException.class,
                () -> articleRepository.save(article(run, urlHash, OffsetDateTime.now())));
    }

    @Test
    void keepsPreviousStateAsVersionOnUpdate() {
        CollectionRun run = runRepository.save(newRun("version-run"));
        Article saved = articleRepository.save(article(run, randomHash(), OffsetDateTime.now()));
        flushAndClear();

        Article loaded = articleRepository.findById(saved.getId()).orElseThrow();
        assertTrue(loaded.hasSameContent("content-hash-v1"));

        articleVersionRepository.save(
                ArticleVersion.snapshotOf(loaded, run, ArticleVersion.FIRST_VERSION_NO, LocalDateTime.now()));
        loaded.applyUpdate("정정된 제목", "새 요약", "새 본문", "content-hash-v2",
                FetchStatus.FULLTEXT, run, LocalDateTime.now());
        flushAndClear();

        Article updated = articleRepository.findById(saved.getId()).orElseThrow();
        assertEquals("정정된 제목", updated.getTitle());
        assertFalse(updated.hasSameContent("content-hash-v1"));

        List<ArticleVersion> versions = articleVersionRepository.findByArticleIdOrderByVersionNoAsc(saved.getId());
        assertEquals(1, versions.size());
        assertEquals("수집 통합테스트 기사", versions.get(0).getTitle());
        assertEquals("content-hash-v1", versions.get(0).getContentHash());
        assertEquals(ArticleVersion.FIRST_VERSION_NO,
                articleVersionRepository.findFirstByArticleIdOrderByVersionNoDesc(saved.getId())
                        .orElseThrow().getVersionNo());
    }

    private CollectionRun newRun(String idempotencyKey) {
        return CollectionRun.builder()
                .status(RunStatus.PENDING)
                .triggerType(TriggerType.MANUAL)
                .idempotencyKey(idempotencyKey)
                .forceRefresh(false)
                .startedAt(LocalDateTime.now())
                .build();
    }

    private CollectionRun newRunInProgress(String idempotencyKey) {
        CollectionRun run = newRun(idempotencyKey);
        run.start();
        return run;
    }

    private CollectionRunItem item(Source source, RunItemStatus status, int scanned, int created, int updated) {
        return CollectionRunItem.builder()
                .topic(topic)
                .source(source)
                .status(status)
                .scannedCount(scanned)
                .newCount(created)
                .updatedCount(updated)
                .build();
    }

    private CollectionRunWarning warning(String code, int articleCount) {
        return CollectionRunWarning.builder()
                .source(source)
                .code(code)
                .message("통합테스트 경고")
                .articleCount(articleCount)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    private Article article(CollectionRun run, String urlHash, OffsetDateTime publishedAt) {
        return Article.builder()
                .topic(topic)
                .source(source)
                .urlHash(urlHash)
                .canonicalUrl("https://www.hankyung.com/article/" + urlHash)
                .title("수집 통합테스트 기사")
                .summary("요약")
                .contentHash("content-hash-v1")
                .language("ko")
                .sourceName("www.hankyung.com")
                .publishedAt(publishedAt)
                .fetchStatus(FetchStatus.METADATA_ONLY)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(LocalDateTime.now())
                .build();
    }

    private String randomHash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
