package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class AgentRunRecorderIntegrationTests {

    @Autowired
    private AgentRunRecorder recorder;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private CollectionRunRepository collectionRunRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void recordsMockAnalyzeOncePerIdempotencyKey() {
        long countBefore = agentRunRepository.count();
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
        AgentAnalyzeResponse response = response();

        recorder.recordSuccess(run.getId(), 10L, request, response, startedAt);
        recorder.recordSuccess(run.getId(), 10L, request, response, startedAt);
        entityManager.flush();
        entityManager.clear();

        AgentRun recorded = agentRunRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertEquals(AgentRunStatus.MOCK, recorded.getStatus());
        assertEquals(AgentTask.ANALYZE, recorded.getAgentTask());
        assertEquals("mock", recorded.getLlmProvider());
        assertEquals(AgentPlan.FREE, recorded.getLlmPlan());
        assertEquals(64, recorded.getRequestHash().length());
        assertEquals(countBefore + 1, agentRunRepository.count());
    }

    private AgentAnalyzeRequest request(Long runId) {
        return new AgentAnalyzeRequest(
                "integration:run:" + runId + ":article:10",
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
