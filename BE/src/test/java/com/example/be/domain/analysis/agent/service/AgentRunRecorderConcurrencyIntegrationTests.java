package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class AgentRunRecorderConcurrencyIntegrationTests {

    @Autowired
    private AgentRunRecorder recorder;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private CollectionRunRepository collectionRunRepository;

    @Test
    void concurrentDuplicateRecordingBecomesSuccessfulNoOp() throws Exception {
        LocalDateTime startedAt = LocalDateTime.now();
        CollectionRun run = collectionRunRepository.save(CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .forceRefresh(false)
                .startedAt(startedAt)
                .scannedCount(1)
                .newCount(1)
                .updatedCount(0)
                .skippedCount(0)
                .build());
        AgentAnalyzeRequest request = request(run.getId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> recordWhenReleased(
                    ready, start, run.getId(), request, startedAt));
            Future<?> second = executor.submit(() -> recordWhenReleased(
                    ready, start, run.getId(), request, startedAt));

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            AgentRun recorded = agentRunRepository
                    .findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow();
            assertEquals(request.idempotencyKey(), recorded.getIdempotencyKey());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            agentRunRepository.findByIdempotencyKey(request.idempotencyKey())
                    .ifPresent(agentRunRepository::delete);
            collectionRunRepository.deleteById(run.getId());
        }
    }

    private void recordWhenReleased(CountDownLatch ready,
                                    CountDownLatch start,
                                    Long runId,
                                    AgentAnalyzeRequest request,
                                    LocalDateTime startedAt) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행 시작을 기다리지 못했습니다.");
            }
            recorder.recordSuccess(runId, 10L, request, response(), startedAt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행이 중단됐습니다.", exception);
        }
    }

    private AgentAnalyzeRequest request(Long runId) {
        return new AgentAnalyzeRequest(
                "concurrent:" + UUID.randomUUID() + ":run:" + runId,
                AgentPlan.FREE,
                new AgentAnalyzeRequest.ArticlePayload(
                        10L, "기사", "https://example.com/10", "ko", null, "기사 본문"),
                new AgentAnalyzeRequest.TopicPayload("HBM", "HBM", List.of("HBM"), List.of(), List.of()),
                null);
    }

    private AgentAnalyzeResponse response() {
        return new AgentAnalyzeResponse(
                List.of("기사 본문"),
                List.of(new AgentAnalyzeResponse.Section(
                        "핵심",
                        List.of(new AgentAnalyzeResponse.Bullet(
                                "기사", List.of(1), "grounded", BigDecimal.ONE)))),
                "기사",
                new AgentAnalyzeResponse.Classification(
                        "산업 동향 보도", "neutral", "low", "reference", "제품/공정"),
                new AgentAnalyzeResponse.Entities(List.of(), List.of(), List.of()),
                new AgentAnalyzeResponse.Meta(
                        "mock", "mock", "analyze.mock.v1", 0L, 0L,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }
}
