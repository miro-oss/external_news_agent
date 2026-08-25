package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

    @Test
    void recordsReportUsageAndRunTarget() {
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
        OffsetDateTime offsetStartedAt = startedAt.atZone(ApiTimeZone.ZONE)
                .toOffsetDateTime();
        AgentReportRequest request = new AgentReportRequest(
                "integration:run:" + run.getId() + ":report",
                AgentPlan.PAID,
                new AgentReportRequest.RunPayload(
                        run.getId(), offsetStartedAt, offsetStartedAt.plusMinutes(1), List.of("HBM")),
                List.of(),
                List.of(),
                new AgentReportRequest.SourceStatsPayload(1, 0, 0, 0, 1),
                List.of("STUB 1건 제외"),
                "TECHNOLOGY");
        AgentReportResponse response = new AgentReportResponse(
                "보고서",
                List.of("요약"),
                List.of(),
                List.of(),
                List.of("STUB 1건 제외"),
                "# 보고서",
                new AgentReportResponse.Meta(
                        "mindlogic-claude",
                        "configured-model",
                        "report.ko.v1",
                        100L,
                        20L,
                        new BigDecimal("0.01"),
                        BigDecimal.ONE,
                        false,
                        false));

        recorder.recordReportSuccess(run.getId(), request, response, startedAt);
        entityManager.flush();
        entityManager.clear();

        AgentRun recorded = agentRunRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertEquals(AgentTask.REPORT, recorded.getAgentTask());
        assertEquals(AgentTargetType.RUN, recorded.getTargetType());
        assertEquals(run.getId(), recorded.getTargetId());
        assertEquals(AgentRunStatus.SUCCESS, recorded.getStatus());
        assertEquals(AgentPlan.PAID, recorded.getLlmPlan());
        assertEquals(BigDecimal.ONE, recorded.getCredits());
    }

    @Test
    void recordsUsageFromFailedReportCall() {
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
        OffsetDateTime offsetStartedAt = startedAt.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        AgentReportRequest request = new AgentReportRequest(
                "integration:run:" + run.getId() + ":failed-report",
                AgentPlan.PAID,
                new AgentReportRequest.RunPayload(
                        run.getId(), offsetStartedAt, offsetStartedAt.plusMinutes(1), List.of("HBM")),
                List.of(),
                List.of(),
                new AgentReportRequest.SourceStatsPayload(1, 0, 0, 0, 0),
                List.of("수집 또는 분석 제외 사항이 없습니다."),
                "TECHNOLOGY");

        recorder.recordReportFailure(
                run.getId(),
                request,
                "SCHEMA_VIOLATION",
                "출력 검증 실패",
                new AgentClientException.Usage(
                        30L, 15L, new BigDecimal("0.25"), new BigDecimal("2")),
                null,
                startedAt);
        entityManager.flush();
        entityManager.clear();

        AgentRun recorded = agentRunRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertEquals(AgentRunStatus.FAILED, recorded.getStatus());
        assertEquals(30L, recorded.getInputTokens());
        assertEquals(15L, recorded.getOutputTokens());
        assertEquals(0, new BigDecimal("0.25").compareTo(recorded.getCostUsd()));
        assertEquals(0, new BigDecimal("2").compareTo(recorded.getCredits()));
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
                List.of(),
                new AgentAnalyzeResponse.Meta(
                        "mock", "mock", "analyze.mock.v2", 0L, 0L,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }
}
