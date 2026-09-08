package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 같은 주제를 노리는 요청 둘이 <b>실제로 동시에</b> 들어올 때 실행이 하나만 만들어지는지 본다.
 *
 * <p>사전 조회만으로는 못 막는다 — 첫 요청이 커밋되기 전이라 둘 다 빈 결과를 본다. 그래서
 * 대상 주제를 {@code PESSIMISTIC_WRITE}로 잠근다. 그 잠금이 실제로 직렬화하는지는
 * <b>커밋되는 트랜잭션 둘</b>이 필요해서 여기서만 확인할 수 있다 — 클래스에 {@code @Transactional}을 붙이지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CollectionRunConcurrencyIntegrationTests {

    @Autowired
    private CollectionRunCommandService runCommandService;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private CollectionRunQueueClaimer queueClaimer;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 실행이 실제로 도는 것까지는 볼 필요가 없다. 수집은 대역으로 막는다. */
    @MockitoBean
    private CollectionRunAsyncService runAsyncService;

    private Topic topic;
    private Source source;

    @BeforeEach
    void setUp() {
        // 준비 중 실패하면 소스만 커밋되는 부분 fixture를 남기지 않는다.
        transactionTemplate.executeWithoutResult(status -> {
            source = sourceRepository.save(Source.builder()
                    .sourceKind(Source.KIND_FEED)
                    .name("동시성 테스트 소스")
                    .urlTemplate("https://example.com/concurrency-" + UUID.randomUUID())
                    .language("ko")
                    .active(true)
                    .build());

            Topic newTopic = Topic.builder()
                    .name("동시성 테스트 주제 " + UUID.randomUUID())
                    .queryText("HBM")
                    .requiredKeywords(List.of())
                    .optionalKeywords(List.of())
                    .excludedKeywords(List.of())
                    .batchSize(10)
                    .intervalMinutes(60)
                    // 수동 실행은 허용하되 같은 DB의 스케줄러가 fixture를 수집하지 않게 한다.
                    .lastCollectedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                    .active(true)
                    .build();
            newTopic.replaceSources(List.of(source));
            topic = topicRepository.save(newTopic);
        });
    }

    @Test
    void queuesBothRequestsWhenDifferentKeysHitTheSameTopic() throws Exception {
        List<Outcome> outcomes = runConcurrently(
                () -> start(null),
                () -> start(null));

        long created = outcomes.stream().filter(Outcome::created).count();
        long rejected = outcomes.stream().filter(outcome -> outcome.conflict).count();

        assertEquals(2, created, "서로 다른 요청은 모두 대기 접수해야 한다");
        assertEquals(0, rejected, "같은 주제여도 요청을 거절하지 않는다");
        assertEquals(2, inProgressRunCount());
    }

    /**
     * 버튼 연타. 명세는 지는 요청에게도 200 + 기존 run을 주라고 한다.
     */
    @Test
    void returnsTheSameRunWhenTwoRequestsShareAnIdempotencyKey() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();

        List<Outcome> outcomes = runConcurrently(
                () -> start(key),
                () -> start(key));

        assertTrue(outcomes.stream().noneMatch(outcome -> outcome.conflict),
                "같은 키 연타는 충돌이 아니라 기존 실행을 돌려줘야 한다");
        assertEquals(1, outcomes.stream().map(outcome -> outcome.runId).distinct().count(),
                "두 요청이 같은 실행을 가리켜야 한다");
        assertEquals(1, inProgressRunCount());
    }

    @Test
    void concurrentDispatchersReserveOnlyOneRunForTheSameTopic() throws Exception {
        Outcome first = start("first-" + UUID.randomUUID());
        Outcome second = start("second-" + UUID.randomUUID());
        List<Outcome> claims = runConcurrently(this::claimOne, this::claimOne);
        List<Long> claimedIds = claims.stream().map(Outcome::runId).filter(java.util.Objects::nonNull).toList();
        assertEquals(1, claimedIds.size());
        Long claimed = claimedIds.getFirst();
        assertTrue(List.of(first.runId(), second.runId()).contains(claimed));
        assertEquals(RunStatus.RUNNING, runRepository.findById(claimed).orElseThrow().getStatus());
        transactionTemplate.executeWithoutResult(status ->
                runRepository.findById(claimed).orElseThrow().fail(LocalDateTime.now(ApiTimeZone.ZONE)));
        List<Long> next = queueClaimer.claimAvailable();
        assertEquals(1, next.size());
        assertTrue(!next.getFirst().equals(claimed));
    }

    private Outcome claimOne() {
        List<Long> ids = queueClaimer.claimAvailable();
        return new Outcome(ids.isEmpty() ? null : ids.getFirst(), "CLAIM", false);
    }

    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try {
            Future<Outcome> left = executor.submit(atBarrier(barrier, first));
            Future<Outcome> right = executor.submit(atBarrier(barrier, second));
            return List.of(
                    left.get(10, TimeUnit.SECONDS),
                    right.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "동시 요청이 종료되기 전에 fixture를 정리할 수 없습니다");
        }
    }

    private Callable<Outcome> atBarrier(CyclicBarrier barrier, Callable<Outcome> task) {
        return () -> {
            barrier.await();
            return task.call();
        };
    }

    private Outcome start(String idempotencyKey) {
        CollectionRunReqDTO.Create request = new CollectionRunReqDTO.Create();
        request.setTopicIds(List.of(topic.getId()));
        request.setIdempotencyKey(idempotencyKey);

        try {
            CollectionRunStartResult result = runCommandService.startManualRun(request);
            return new Outcome(result.response().getRunId(), result.successCode().getCode(), false);
        } catch (RunException exception) {
            return new Outcome(null, exception.getCode().getCode(), true);
        }
    }

    private long inProgressRunCount() {
        return runRepository.findInProgressByTopicIds(
                List.of(topic.getId()), RunStatus.IN_PROGRESS_STATUSES).size();
    }

    private record Outcome(Long runId, String code, boolean conflict) {

        boolean created() {
            return !conflict && "COMMON201".equals(code);
        }
    }

    /**
     * assertion 실패나 실행의 종료 상태와 관계없이 이번 fixture만 정리한다.
     */
    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            if (topic != null && topic.getId() != null) {
                Long topicId = topic.getId();
                List<Long> runIds = jdbcTemplate.queryForList(
                        "SELECT DISTINCT run_id FROM news_collection_run_items WHERE topic_id = ?",
                        Long.class, topicId);
                for (Long runId : runIds) {
                    jdbcTemplate.update("DELETE FROM news_collection_run_warnings WHERE run_id = ?", runId);
                    jdbcTemplate.update("DELETE FROM news_collection_run_items WHERE run_id = ?", runId);
                    jdbcTemplate.update("DELETE FROM news_collection_runs WHERE id = ?", runId);
                }
                jdbcTemplate.update("DELETE FROM news_topic_sources WHERE topic_id = ?", topicId);
                jdbcTemplate.update("DELETE FROM news_topics WHERE id = ?", topicId);
            }
            if (source != null && source.getId() != null) {
                jdbcTemplate.update("DELETE FROM news_sources WHERE id = ?", source.getId());
            }
        });
    }
}
