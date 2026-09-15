package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ArticleBody;
import com.example.be.domain.collection.entity.ArticleVersion;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.ArticleVersionRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ArticleBodyStorageIntegrationTests {

    @Autowired
    private ArticleBodyStorage bodyStorage;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleVersionRepository versionRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @Transactional
    void differentUrlsRetainTheirArticlesAndShareOneBodyRow() {
        Fixture fixture = fixture();
        String body = "같은 전문을 전재한 기사 " + UUID.randomUUID();
        Article first = saveArticle(fixture, "첫 매체의 제목", body);
        Article second = saveArticle(fixture, "둘째 매체의 제목", body);
        flushAndClear();

        Article reloadedFirst = articleRepository.findById(first.getId()).orElseThrow();
        Article reloadedSecond = articleRepository.findById(second.getId()).orElseThrow();
        assertNotEquals(reloadedFirst.getId(), reloadedSecond.getId());
        assertNotEquals(reloadedFirst.getCanonicalUrl(), reloadedSecond.getCanonicalUrl());
        assertEquals("첫 매체의 제목", reloadedFirst.getTitle());
        assertEquals("둘째 매체의 제목", reloadedSecond.getTitle());
        assertEquals(body, reloadedFirst.getBody());
        assertEquals(body, reloadedSecond.getBody());
        String bodyHash = reloadedFirst.getStoredBody().getBodyHash();
        assertEquals(bodyHash, reloadedSecond.getStoredBody().getBodyHash());
        assertEquals(1, bodyRows(bodyHash));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM news_articles WHERE body_hash = ?", Integer.class, bodyHash));
    }

    @Test
    @Transactional
    void preservesWhitespaceAndDifferentTextWithoutMergingTheirBodies() {
        String body = "내용과 공백을 보존한다 " + UUID.randomUUID();
        List<String> values = List.of(body, body + " ", body + "\n", body + " 정정", " \n\t\r ", "");
        List<ArticleBody> stored = values.stream().map(bodyStorage::intern).toList();
        List<String> hashes = stored.stream().map(ArticleBody::getBodyHash).toList();
        assertEquals(values.size(), new HashSet<>(hashes).size());
        assertNull(bodyStorage.intern(null));
        flushAndClear();

        for (int index = 0; index < values.size(); index++) {
            ArticleBody reloaded = entityManager.find(ArticleBody.class, hashes.get(index));
            assertEquals(values.get(index), reloaded.getBody());
            assertEquals(hashes.get(index), bodyStorage.intern(values.get(index)).getBodyHash());
            assertEquals(1, bodyRows(hashes.get(index)));
        }
    }

    @Test
    @Transactional
    void sharesLongUnicodeClobsWithoutTruncatingOrNormalizingThem() {
        Fixture fixture = fixture();
        String body = "한글 기사 📰 café e\u0301 中文\r\n".repeat(3000) + UUID.randomUUID();
        Article first = saveArticle(fixture, "긴 원문", body);
        Article second = saveArticle(fixture, "긴 전재 기사", body);
        flushAndClear();

        Article reloadedFirst = articleRepository.findById(first.getId()).orElseThrow();
        Article reloadedSecond = articleRepository.findById(second.getId()).orElseThrow();
        assertTrue(body.length() > 32767);
        assertEquals(body, reloadedFirst.getBody());
        assertEquals(body, reloadedSecond.getBody());
        assertEquals(reloadedFirst.getStoredBody().getBodyHash(), reloadedSecond.getStoredBody().getBodyHash());
        assertEquals(1, bodyRows(reloadedFirst.getStoredBody().getBodyHash()));
    }

    @Test
    @Transactional
    void correctionKeepsOriginalBodyForOtherArticlesAndVersionHistory() {
        Fixture fixture = fixture();
        String originalBody = "정정 전 원문 " + UUID.randomUUID();
        String correctedBody = originalBody + "\n추가로 확인된 사실";
        Article corrected = saveArticle(fixture, "정정 전 제목", originalBody);
        Article syndicated = saveArticle(fixture, "전재된 제목", originalBody);
        String originalHash = corrected.getStoredBody().getBodyHash();
        ArticleVersion version = versionRepository.save(ArticleVersion.snapshotOf(
                corrected, null, ArticleVersion.FIRST_VERSION_NO, LocalDateTime.now()));
        corrected.applyUpdate("정정된 제목", "정정 요약", corrected.getBody(), "corrected-metadata",
                FetchStatus.FULLTEXT, null, LocalDateTime.now());
        corrected.applyStoredFullText(bodyStorage.intern(correctedBody), FetchStatus.FULLTEXT, LocalDateTime.now());
        flushAndClear();

        Article reloaded = articleRepository.findById(corrected.getId()).orElseThrow();
        ArticleVersion previous = versionRepository.findById(version.getId()).orElseThrow();
        assertEquals(correctedBody, reloaded.getBody());
        assertNotEquals(originalHash, reloaded.getStoredBody().getBodyHash());
        assertEquals(originalBody, articleRepository.findById(syndicated.getId()).orElseThrow().getBody());
        assertEquals(originalBody, previous.getBody());
        assertEquals(originalHash, previous.getStoredBody().getBodyHash());
        assertEquals("정정 전 제목", previous.getTitle());
        assertEquals(1, bodyRows(originalHash));
        assertEquals(1, bodyRows(reloaded.getStoredBody().getBodyHash()));
    }

    @Test
    @Transactional
    void failedRefreshKeepsTheExistingSharedBodyReference() {
        Article article = saveArticle(fixture(), "재수집 대상", "직전 전문 " + UUID.randomUUID());
        String body = article.getBody();
        String bodyHash = article.getStoredBody().getBodyHash();

        article.applyStoredFullText(null, FetchStatus.FETCH_FAILED, LocalDateTime.now());
        flushAndClear();

        Article reloaded = articleRepository.findById(article.getId()).orElseThrow();
        assertEquals(FetchStatus.FETCH_FAILED, reloaded.getFetchStatus());
        assertEquals(body, reloaded.getBody());
        assertEquals(bodyHash, reloaded.getStoredBody().getBodyHash());
        assertFalse(reloaded.hasFullText());
        assertEquals(1, bodyRows(bodyHash));
    }

    @Test
    void concurrentTransactionsReuseTheBodyAfterTheFirstInsertCommits() throws Exception {
        String body = "동시에 수집된 같은 전문 " + UUID.randomUUID();
        String hash = ArticleBody.of(body).getBodyHash();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> transactionTemplate.execute(status -> {
                ArticleBody stored = bodyStorage.intern(body);
                inserted.countDown();
                await(commitFirst);
                return stored.getBodyHash();
            }));
            assertTrue(inserted.await(10, TimeUnit.SECONDS));
            Future<String> second = executor.submit(() -> transactionTemplate.execute(status -> {
                secondStarted.countDown();
                return bodyStorage.intern(body).getBodyHash();
            }));
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS),
                    "두 번째 트랜잭션은 첫 저장의 커밋을 기다려야 한다");
            commitFirst.countDown();

            assertEquals(hash, first.get(20, TimeUnit.SECONDS));
            assertEquals(hash, second.get(20, TimeUnit.SECONDS));
            assertEquals(1, bodyRows(hash));
            assertEquals(body, jdbcTemplate.queryForObject(
                    "SELECT body FROM news_article_bodies WHERE body_hash = ?", String.class, hash));
        } finally {
            commitFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            jdbcTemplate.update("DELETE FROM news_article_bodies WHERE body_hash = ?", hash);
        }
    }

    private Fixture fixture() {
        String stamp = UUID.randomUUID().toString();
        Topic topic = topicRepository.save(Topic.builder()
                .name("공유 본문 주제 " + stamp)
                .batchSize(10).intervalMinutes(60).active(true).build());
        Source source = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("공유 본문 소스 " + stamp)
                .urlTemplate("https://example.test/shared-body/" + stamp + ".xml")
                .active(true).build());
        return new Fixture(topic, source);
    }

    private Article saveArticle(Fixture fixture, String title, String body) {
        String urlHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        return articleRepository.save(Article.builder()
                .topic(fixture.topic()).source(fixture.source())
                .urlHash(urlHash).canonicalUrl("https://example.test/article/" + urlHash)
                .title(title).storedBody(bodyStorage.intern(body))
                .fetchStatus(FetchStatus.FULLTEXT).collectedAt(LocalDateTime.now())
                .build());
    }

    private int bodyRows(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM news_article_bodies WHERE body_hash = ?", Integer.class, hash);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 저장 테스트가 다음 트랜잭션을 기다리지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 저장 테스트가 중단됐습니다.", exception);
        }
    }

    private record Fixture(Topic topic, Source source) {
    }
}
