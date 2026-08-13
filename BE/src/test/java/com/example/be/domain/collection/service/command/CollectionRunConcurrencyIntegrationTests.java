package com.example.be.domain.collection.service.command;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.exception.RunException;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private TopicRepository topicRepository;

    @Autowired
    private SourceRepository sourceRepository;

    /** 실행이 실제로 도는 것까지는 볼 필요가 없다. 수집은 대역으로 막는다. */
    @MockitoBean
    private CollectionRunAsyncService runAsyncService;

    private Topic topic;
    private Source source;

    @BeforeEach
    void setUp() {
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
                .active(true)
                .build();
        newTopic.replaceSources(List.of(source));
        topic = topicRepository.save(newTopic);
    }

    @Test
    void createsOnlyOneRunWhenTwoRequestsHitTheSameTopic() throws Exception {
        List<Outcome> outcomes = runConcurrently(
                () -> start(null),
                () -> start(null));

        long created = outcomes.stream().filter(Outcome::created).count();
        long rejected = outcomes.stream().filter(outcome -> outcome.conflict).count();

        assertEquals(1, created, "같은 주제로 실행이 둘 만들어졌다");
        assertEquals(1, rejected, "지는 요청은 RUN409여야 한다");
        assertEquals(1, inProgressRunCount());
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

    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try {
            Future<Outcome> left = executor.submit(atBarrier(barrier, first));
            Future<Outcome> right = executor.submit(atBarrier(barrier, second));
            return List.of(left.get(), right.get());
        } finally {
            executor.shutdownNow();
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
     * 커밋되는 테스트라 남는다. 다음 실행이 "이미 수집 중"에 걸리지 않게 지운다.
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        List<CollectionRun> runs = runRepository.findInProgressByTopicIds(
                List.of(topic.getId()), RunStatus.IN_PROGRESS_STATUSES);
        runs.forEach(run -> runRepository.findById(run.getId())
                .ifPresent(found -> runRepository.delete(found)));
    }
}
