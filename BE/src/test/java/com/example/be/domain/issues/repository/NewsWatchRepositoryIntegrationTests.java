package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus;
import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import com.example.be.domain.notifications.service.WatchAlertOutboxBatchClaimer;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class NewsWatchRepositoryIntegrationTests {

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private NewsIssueRepository issueRepository;

    @Autowired
    private NewsWatchRepository watchRepository;

    @Autowired
    private WatchAlertOutboxRepository watchAlertOutboxRepository;

    @Autowired
    private WatchAlertOutboxBatchClaimer batchClaimer;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @Transactional
    void locksBreakingAndDisputedWatchesAndExcludesThemDuringCooldown() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Topic topic = topicRepository.save(Topic.builder()
                .name("속보 watch 통합테스트 " + System.nanoTime())
                .batchSize(10).intervalMinutes(60).active(true).build());
        OffsetDateTime issueTime = now.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        NewsIssue issue = issueRepository.save(NewsIssue.builder()
                .title("HBM4 증설")
                .status(IssueStatus.EMERGING)
                .firstSeenAt(issueTime)
                .lastSeenAt(issueTime)
                .articleCount(1)
                .publisherCount(1)
                .independentContentCount(1)
                .topic(topic)
                .entities(List.of("HBM4"))
                .build());
        NewsWatch watch = watchRepository.save(NewsWatch.builder()
                .watchType(WatchType.BREAKING)
                .issue(issue)
                .expiresAt(now.plusHours(48))
                .active(true)
                .build());
        NewsWatch disputed = watchRepository.save(NewsWatch.builder()
                .watchType(WatchType.DISPUTED)
                .issue(issue)
                .expiresAt(now.plusHours(48))
                .active(true)
                .build());
        entityManager.flush();
        entityManager.clear();

        List<NewsWatch> eligible = watchRepository.findEligibleForNotification(issue.getId(), now);

        assertEquals(List.of(watch.getId(), disputed.getId()),
                eligible.stream().map(NewsWatch::getId).toList());
        eligible.forEach(value -> value.claimUntil(now.plusMinutes(30)));
        entityManager.flush();
        entityManager.clear();
        assertTrue(watchRepository.findEligibleForNotification(
                issue.getId(), now.plusMinutes(29)).isEmpty());
    }

    @Test
    @Transactional
    void claimsPendingAndStaleProcessingAlertsFromOutbox() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Fixture fixture = createFixture(now);
        NewsWatch watch = watchRepository.getReferenceById(fixture.watchId());
        OffsetDateTime queuedAt = now.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        WatchAlertOutbox alert = watchAlertOutboxRepository.save(WatchAlertOutbox.builder()
                .watch(watch)
                .issueTitle("HBM4 증설")
                .firstSeenAt(queuedAt.minusHours(1))
                .followUpCount(1)
                .publisherCount(2)
                .queuedAt(queuedAt)
                .status(WatchAlertDeliveryStatus.PENDING)
                .attemptCount(0)
                .build());
        entityManager.flush();
        entityManager.clear();

        List<Long> candidates = watchAlertOutboxRepository.findClaimableIds(now.minusMinutes(5), 0L, PageRequest.of(0, 100));
        List<WatchAlertOutbox> pending = watchAlertOutboxRepository.findClaimableByIdsForUpdate(candidates, now.minusMinutes(5));
        assertEquals(List.of(alert.getId()), pending.stream().map(WatchAlertOutbox::getId).toList());
        pending.getFirst().startProcessing(now.minusMinutes(10));
        entityManager.flush();
        entityManager.clear();

        List<WatchAlertOutbox> stale = watchAlertOutboxRepository.findClaimableByIdsForUpdate(candidates, now.minusMinutes(5));
        assertEquals(List.of(alert.getId()), stale.stream().map(WatchAlertOutbox::getId).toList());
    }

    @Test
    @Transactional
    void candidatePagesUseKeysetOrderAndExcludeRecentProcessingAndSentRows() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Fixture fixture = createFixture(now);
        WatchAlertOutbox first = createAlert(fixture, now);
        WatchAlertOutbox recent = createAlert(fixture, now);
        recent.startProcessing(now);
        WatchAlertOutbox stale = createAlert(fixture, now);
        stale.startProcessing(now.minusMinutes(10));
        WatchAlertOutbox sent = createAlert(fixture, now);
        sent.markSent(now);
        WatchAlertOutbox last = createAlert(fixture, now);
        entityManager.flush();
        entityManager.clear();

        List<Long> firstPage = watchAlertOutboxRepository.findClaimableIds(now.minusMinutes(5),
                first.getId() - 1, PageRequest.of(0, 2));
        List<Long> secondPage = watchAlertOutboxRepository.findClaimableIds(now.minusMinutes(5),
                firstPage.getLast(), PageRequest.of(0, 2));

        assertEquals(List.of(first.getId(), stale.getId()), firstPage);
        assertEquals(List.of(last.getId()), secondPage);
        watchAlertOutboxRepository.findById(last.getId()).orElseThrow().startProcessing(now);
        entityManager.flush();
        entityManager.clear();
        assertTrue(watchAlertOutboxRepository.findClaimableByIdsForUpdate(secondPage, now.minusMinutes(5)).isEmpty());
    }

    @Test
    void outboxLockRechecksEligibilityAfterAnotherWorkerCommitsItsClaim() throws Exception {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Fixture fixture = transactionTemplate.execute(status -> createFixture(now));
        Long alertId = transactionTemplate.execute(status -> createAlert(fixture, now).getId());
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReadCandidates = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> first = executor.submit(() -> transactionTemplate.execute(status -> {
                var locked = watchAlertOutboxRepository.findClaimableByIdsForUpdate(List.of(alertId), now.minusMinutes(5));
                firstLocked.countDown();
                await(releaseFirst);
                locked.getFirst().startProcessing(now);
                return locked.stream().map(WatchAlertOutbox::getId).toList();
            }));
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS));
            Future<List<Long>> second = executor.submit(() -> transactionTemplate.execute(status -> {
                var ids = watchAlertOutboxRepository.findClaimableIds(now.minusMinutes(5),
                        alertId - 1, PageRequest.of(0, 100));
                assertEquals(List.of(alertId), ids);
                secondReadCandidates.countDown();
                return watchAlertOutboxRepository.findClaimableByIdsForUpdate(ids, now.minusMinutes(5))
                        .stream().map(WatchAlertOutbox::getId).toList();
            }));
            assertTrue(secondReadCandidates.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertEquals(List.of(alertId), get(first));
            assertTrue(get(second).isEmpty());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            cleanupAlert(fixture, alertId);
        }
    }

    @Test
    void hiddenBatchReleasesLocksBeforeTheNextBatchOrCallingTransactionEnds() throws Exception {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Fixture fixture = transactionTemplate.execute(status -> createFixture(now));
        Long alertId = transactionTemplate.execute(status -> createAlert(fixture, now).getId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                var batch = batchClaimer.claimAfter(alertId - 1, now, 100);
                assertTrue(batch.snapshots().isEmpty());
                assertEquals(alertId.longValue(), batch.afterId());
                assertTrue(batch.exhausted());
                Future<List<Long>> nextWorker = executor.submit(() -> transactionTemplate.execute(other ->
                        watchAlertOutboxRepository.findClaimableByIdsForUpdate(List.of(alertId), now.minusMinutes(5))
                                .stream().map(WatchAlertOutbox::getId).toList()));
                try {
                    assertEquals(List.of(alertId), nextWorker.get(5, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new AssertionError("배치가 종료된 뒤에도 숨긴 알림 잠금이 유지됩니다.", exception);
                }
            });
            var unchanged = watchAlertOutboxRepository.findById(alertId).orElseThrow();
            assertEquals(WatchAlertDeliveryStatus.PENDING, unchanged.getStatus());
            assertEquals(0, unchanged.getAttemptCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            cleanupAlert(fixture, alertId);
        }
    }

    private WatchAlertOutbox createAlert(Fixture fixture, LocalDateTime now) {
        OffsetDateTime queuedAt = now.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        return watchAlertOutboxRepository.save(WatchAlertOutbox.builder()
                .watch(watchRepository.getReferenceById(fixture.watchId())).issueTitle("HBM4 증설")
                .firstSeenAt(queuedAt.minusHours(1)).followUpCount(1).publisherCount(2).queuedAt(queuedAt)
                .status(WatchAlertDeliveryStatus.PENDING).attemptCount(0).build());
    }

    private void cleanupAlert(Fixture fixture, Long alertId) {
        transactionTemplate.executeWithoutResult(status -> {
            watchAlertOutboxRepository.deleteById(alertId);
            watchRepository.deleteById(fixture.watchId());
            issueRepository.deleteById(fixture.issueId());
            topicRepository.deleteById(fixture.topicId());
        });
    }

    @Test
    void concurrentClaimWaitsForLockAndObservesCommittedCooldown() throws Exception {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Fixture fixture = transactionTemplate.execute(status -> createFixture(now));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<Long>> first = executor.submit(() -> transactionTemplate.execute(status -> {
                List<NewsWatch> eligible = watchRepository.findEligibleForNotification(
                        fixture.issueId(), now);
                firstLocked.countDown();
                await(releaseFirst);
                eligible.getFirst().claimUntil(now.plusMinutes(30));
                return eligible.stream().map(NewsWatch::getId).toList();
            }));
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS));

            Future<List<Long>> second = executor.submit(() -> {
                secondStarted.countDown();
                return transactionTemplate.execute(status -> watchRepository
                        .findEligibleForNotification(fixture.issueId(), now).stream()
                        .map(NewsWatch::getId)
                        .toList());
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertEquals(List.of(fixture.watchId()), get(first));
            assertTrue(get(second).isEmpty());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            transactionTemplate.executeWithoutResult(status -> {
                watchRepository.deleteById(fixture.watchId());
                issueRepository.deleteById(fixture.issueId());
                topicRepository.deleteById(fixture.topicId());
            });
        }
    }

    private Fixture createFixture(LocalDateTime now) {
        Topic topic = topicRepository.save(Topic.builder()
                .name("속보 watch 동시성테스트 " + System.nanoTime())
                .batchSize(10).intervalMinutes(60).active(true).build());
        OffsetDateTime issueTime = now.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        NewsIssue issue = issueRepository.save(NewsIssue.builder()
                .title("HBM4 동시성 증설")
                .status(IssueStatus.EMERGING)
                .firstSeenAt(issueTime)
                .lastSeenAt(issueTime)
                .articleCount(1)
                .publisherCount(1)
                .independentContentCount(1)
                .topic(topic)
                .entities(List.of("HBM4"))
                .build());
        NewsWatch watch = watchRepository.save(NewsWatch.builder()
                .watchType(WatchType.BREAKING)
                .issue(issue)
                .expiresAt(now.plusHours(48))
                .active(true)
                .build());
        watchRepository.flush();
        return new Fixture(topic.getId(), issue.getId(), watch.getId());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 latch 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단됨", exception);
        }
    }

    private List<Long> get(Future<List<Long>> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(5, TimeUnit.SECONDS);
    }

    private record Fixture(Long topicId, Long issueId, Long watchId) {
    }
}
