package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.dto.AgentSelfCritiqueResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.quota.AgentQuotaJdbcRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentQuotaJdbcRepository quotaRepository;

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
        assertNull(recorded.getActionPayload());
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
                List.of("STUB 1건 제외"));
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
        assertNull(recorded.getActionPayload());
    }

    @Test
    void recordsSelfCritiqueAgainstIssueTarget() {
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
        AgentAnalyzeRequest request = new AgentAnalyzeRequest(
                "integration:run:" + run.getId() + ":issue:88:self-critique",
                AgentPlan.FREE,
                new AgentAnalyzeRequest.ArticlePayload(
                        10L, "기사", "https://example.com/10", "ko", null, "기사 본문"),
                List.of(),
                new AgentAnalyzeRequest.TopicPayload(
                        "HBM", "HBM", List.of("HBM"), List.of(), List.of()),
                new AgentAnalyzeRequest.PreviousFindingPayload(
                        "최초 분석 결과를 담은 한국어 요약입니다.",
                        com.example.be.domain.analysis.agent.AgentSensitivityFixtures.analyze(3),
                        List.of(new AgentAnalyzeRequest.PreviousSectionPayload(
                                "핵심",
                                List.of(new AgentAnalyzeRequest.PreviousBulletPayload(
                                        "기사 핵심 주장",
                                        List.of(1),
                                        "weak",
                                        new BigDecimal("0.6"),
                                        "추가 검토가 필요합니다.",
                                        "FACT",
                                        null)))),
                        AgentAnalyzeResponse.CrossSource.empty()),
                true);
        AgentSelfCritiqueResponse response = new AgentSelfCritiqueResponse(
                List.of(new AgentSelfCritiqueResponse.Section(
                        "핵심",
                        List.of(new AgentSelfCritiqueResponse.Bullet(
                                "검토된 기사 핵심 주장",
                                List.of(1),
                                "grounded",
                                new BigDecimal("0.9"),
                                "원문에서 확인됩니다.",
                                "FACT",
                                null)))),
                "자기 검증을 반영한 한국어 요약입니다.",
                1,
                1,
                List.of("강한 표현"),
                new AgentAnalyzeResponse.Meta(
                        "gemini", "gemini-2.5-flash", "self-critique.ko.v1",
                        20L, 10L, BigDecimal.ZERO, BigDecimal.ZERO, false, false));

        recorder.recordSelfCritiqueSuccess(run.getId(), 88L, request, response, startedAt);
        entityManager.flush();
        entityManager.clear();

        AgentRun recorded = agentRunRepository.findByIdempotencyKey(
                request.idempotencyKey()).orElseThrow();
        assertEquals(AgentTask.SELF_CRITIQUE, recorded.getAgentTask());
        assertEquals(AgentTargetType.ISSUE, recorded.getTargetType());
        assertEquals(88L, recorded.getTargetId());
        assertEquals(AgentRunStatus.SUCCESS, recorded.getStatus());
        assertNull(recorded.getActionPayload());
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
                List.of("수집 또는 분석 제외 사항이 없습니다."));

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
        assertNull(recorded.getActionPayload());
    }

    @Test
    void persistsInsightObservationJsonOnceWithProviderCost() {
        LocalDateTime startedAt = LocalDateTime.of(1997, 2, 3, 4, 5, 6);
        CollectionRun run = insightCollectionRun(startedAt);
        AgentInsightRequest request = insightRequest(run.getId(), "success");
        InsightAuditContext context = InsightAuditContext.capture(
                "a".repeat(64), request.findings(), 3, 1, true,
                "expected-prompt", "configured-model");
        AgentInsightResponse response = new AgentInsightResponse(List.of(),
                new AgentInsightResponse.Meta("actual-provider", "actual-model", "actual-prompt",
                        120L, 25L, new BigDecimal("0.012345"), BigDecimal.ZERO, false, false));

        recorder.recordInsightSuccess(run.getId(), 88L, request, response, context, startedAt);
        recorder.recordInsightSuccess(run.getId(), 88L, request, response, context, startedAt);
        entityManager.flush();
        entityManager.clear();

        AgentRun recorded = agentRunRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertEquals("actual-model", recorded.getLlmModel());
        assertEquals("actual-prompt", recorded.getPromptVersion());
        assertEquals(0, new BigDecimal("0.012345").compareTo(recorded.getCostUsd()));
        assertEquals("PARTIAL", insightJsonValue(request.idempotencyKey(), "cacheOutcome"));
        assertEquals("INSIGHT_OBSERVATION", insightJsonValue(request.idempotencyKey(), "type"));
        assertEquals("1", insightJsonValue(request.idempotencyKey(), "schemaVersion"));
        assertEquals("1", insightJsonValue(request.idempotencyKey(), "selectedHistoryFindingCount"));
        assertEquals("1", insightJsonValue(request.idempotencyKey(), "submittedHistoryFindingCount"));
        assertEquals("3", insightJsonValue(request.idempotencyKey(), "requestedAudienceCount"));
        assertEquals("1", insightJsonValue(request.idempotencyKey(), "cachedAudienceCount"));
        assertEquals("2", insightJsonValue(request.idempotencyKey(), "generationAudienceCount"));
        assertEquals("RESPONSE", insightJsonValue(request.idempotencyKey(), "metadataSource"));
        assertEquals("COMPLETE", insightJsonValue(request.idempotencyKey(), "usageCompleteness"));
        assertEquals("configured-model", insightJsonValue(request.idempotencyKey(), "configuredModel"));
        assertNull(insightJsonValue(request.idempotencyKey(), "costUsd"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE collection_run_id = ? AND agent_task = 'INSIGHT'",
                Integer.class, run.getId()));
        assertEquals(0, new BigDecimal("0.012345").compareTo(jdbcTemplate.queryForObject(
                "SELECT SUM(cost_usd) FROM agent_runs WHERE collection_run_id = ? AND agent_task = 'INSIGHT'",
                BigDecimal.class, run.getId())));
    }

    @Test
    void persistsInsightFailureMetadataWithoutReplacingUnknownWithConfiguredValues() {
        LocalDateTime startedAt = LocalDateTime.of(1997, 2, 3, 4, 5, 6);
        CollectionRun run = insightCollectionRun(startedAt);
        AgentInsightRequest observedRequest = insightRequest(run.getId(), "failure-observed");
        AgentInsightRequest unknownRequest = insightRequest(run.getId(), "failure-unknown");
        InsightAuditContext context = InsightAuditContext.capture(
                "b".repeat(64), observedRequest.findings(), 2, 0, true,
                "expected-prompt", "configured-model");

        recorder.recordInsightFailure(run.getId(), 88L, observedRequest,
                "PERSISTENCE_FAILED", "저장 실패",
                new AgentClientException.Usage(75L, 15L, new BigDecimal("0.005"), BigDecimal.ZERO),
                null, context, new AgentClientException.ExecutionMetadata(
                        "actual-provider", "actual-model", "actual-prompt", "RESPONSE"), startedAt);
        recorder.recordInsightFailure(run.getId(), 88L, unknownRequest,
                "PROVIDER_UNAVAILABLE", "연결 실패", null, null, context, null, startedAt);
        entityManager.flush();
        entityManager.clear();

        AgentRun observed = agentRunRepository.findByIdempotencyKey(observedRequest.idempotencyKey()).orElseThrow();
        assertEquals(AgentRunStatus.FAILED, observed.getStatus());
        assertEquals("actual-provider", observed.getLlmProvider());
        assertEquals("actual-model", observed.getLlmModel());
        assertEquals("actual-prompt", observed.getPromptVersion());
        assertEquals("RESPONSE", insightJsonValue(observedRequest.idempotencyKey(), "metadataSource"));
        assertEquals("COMPLETE", insightJsonValue(observedRequest.idempotencyKey(), "usageCompleteness"));
        assertEquals("expected-prompt", insightJsonValue(observedRequest.idempotencyKey(), "expectedPromptVersion"));
        AgentRun unknown = agentRunRepository.findByIdempotencyKey(unknownRequest.idempotencyKey()).orElseThrow();
        assertNull(unknown.getLlmProvider());
        assertNull(unknown.getLlmModel());
        assertNull(unknown.getPromptVersion());
        assertNull(unknown.getCostUsd());
        assertEquals("UNAVAILABLE", insightJsonValue(unknownRequest.idempotencyKey(), "metadataSource"));
        assertEquals("UNKNOWN", insightJsonValue(unknownRequest.idempotencyKey(), "usageCompleteness"));
        assertEquals("configured-model", insightJsonValue(unknownRequest.idempotencyKey(), "configuredModel"));
    }

    @Test
    void repeatedFullCacheRequestsRemainDistinctWithoutConsumingLegacyFreeQuota() {
        LocalDateTime startedAt = LocalDateTime.of(1997, 2, 3, 4, 5, 6);
        CollectionRun run = insightCollectionRun(startedAt);
        InsightAuditContext context = InsightAuditContext.capture(
                "c".repeat(64), insightRequest(run.getId(), "unused").findings(), 2, 2, false,
                "expected-prompt", null);
        LocalDateTime from = startedAt.toLocalDate().atStartOfDay();
        BigDecimal quotaBefore = quotaRepository.usage(AgentPlan.FREE, from, from.plusDays(1));

        recorder.recordInsightCacheHit(run.getId(), 88L, context, startedAt);
        recorder.recordInsightCacheHit(run.getId(), 88L, context, startedAt);
        entityManager.flush();
        entityManager.clear();

        List<String> keys = jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM agent_runs WHERE collection_run_id = ? AND agent_task = 'INSIGHT'",
                String.class, run.getId());
        assertEquals(2, keys.size());
        assertNotEquals(keys.get(0), keys.get(1));
        for (String key : keys) {
            AgentRun cached = agentRunRepository.findByIdempotencyKey(key).orElseThrow();
            assertEquals(AgentRunStatus.REUSED, cached.getStatus());
            assertNull(cached.getLlmPlan());
            assertNull(cached.getLlmProvider());
            assertNull(cached.getLlmModel());
            assertEquals(0L, cached.getInputTokens());
            assertEquals(0L, cached.getOutputTokens());
            assertEquals(0, BigDecimal.ZERO.compareTo(cached.getCostUsd()));
            assertEquals(0, BigDecimal.ZERO.compareTo(cached.getCredits()));
            assertEquals(context.inputHash(), cached.getRequestHash());
            assertEquals("FULL", insightJsonValue(key, "cacheOutcome"));
            assertEquals("CACHE", insightJsonValue(key, "metadataSource"));
            assertEquals("COMPLETE", insightJsonValue(key, "usageCompleteness"));
            assertEquals("1", insightJsonValue(key, "selectedHistoryFindingCount"));
            assertEquals("0", insightJsonValue(key, "submittedHistoryFindingCount"));
            assertEquals("false", insightJsonValue(key, "agentRequestIssued"));
        }
        assertEquals(0, quotaBefore.compareTo(quotaRepository.usage(AgentPlan.FREE, from, from.plusDays(1))));
    }

    @Test
    void invalidFailureIdentityCannotDiscardOracleUsageAndPartialRepairRemainsPartial() {
        LocalDateTime startedAt = LocalDateTime.of(1997, 2, 3, 4, 5, 6);
        CollectionRun run = insightCollectionRun(startedAt);
        AgentInsightRequest invalidRequest = insightRequest(run.getId(), "invalid-metadata");
        AgentInsightRequest partialRequest = insightRequest(run.getId(), "partial-repair");
        InsightAuditContext context = InsightAuditContext.capture(
                "d".repeat(64), invalidRequest.findings(), 2, 0, true,
                "expected-prompt", "configured-model");
        AgentClientException.Usage usage = new AgentClientException.Usage(
                50L, 10L, new BigDecimal("0.001"), BigDecimal.ZERO);

        recorder.recordInsightFailure(run.getId(), 88L, invalidRequest,
                "SCHEMA_VIOLATION", "메타 검증 실패", usage, null, context,
                new AgentClientException.ExecutionMetadata(
                        "공".repeat(11), "model\nname", "v".repeat(51), "RESPONSE"), startedAt);
        recorder.recordInsightFailure(run.getId(), 88L, partialRequest,
                "PROVIDER_UNAVAILABLE", "repair 응답 미관측", usage, null, context,
                new AgentClientException.ExecutionMetadata(
                        "openai", "actual-model", "actual-prompt", "AGENT_ERROR", "PARTIAL"), startedAt);
        entityManager.flush();
        entityManager.clear();

        AgentRun invalid = agentRunRepository.findByIdempotencyKey(invalidRequest.idempotencyKey()).orElseThrow();
        assertNull(invalid.getLlmProvider());
        assertNull(invalid.getLlmModel());
        assertNull(invalid.getPromptVersion());
        assertEquals(0, usage.costUsd().compareTo(invalid.getCostUsd()));
        assertEquals(usage.inputTokens(), invalid.getInputTokens());
        assertEquals("COMPLETE", insightJsonValue(invalidRequest.idempotencyKey(), "usageCompleteness"));
        AgentRun partial = agentRunRepository.findByIdempotencyKey(partialRequest.idempotencyKey()).orElseThrow();
        assertEquals(0, usage.costUsd().compareTo(partial.getCostUsd()));
        assertEquals("PARTIAL", insightJsonValue(partialRequest.idempotencyKey(), "usageCompleteness"));
    }

    private CollectionRun insightCollectionRun(LocalDateTime startedAt) {
        return collectionRunRepository.save(CollectionRun.builder()
                .status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL)
                .startedAt(startedAt)
                .finishedAt(startedAt.plusMinutes(1))
                .build());
    }

    private AgentInsightRequest insightRequest(Long runId, String suffix) {
        return new AgentInsightRequest("integration:run:" + runId + ":insight:" + suffix,
                AgentPlan.FREE, List.of("CHIP_MAKER", "MARKET_INVESTOR"),
                new AgentInsightRequest.TargetPayload("ISSUE", 88L),
                new AgentInsightRequest.TopicPayload("HBM", "HBM", List.of(), List.of(), List.of()),
                List.of(insightFinding(1L, AgentInsightRequest.FindingRole.CURRENT),
                        insightFinding(2L, AgentInsightRequest.FindingRole.HISTORY)));
    }

    private AgentInsightRequest.FindingPayload insightFinding(Long id, AgentInsightRequest.FindingRole role) {
        return new AgentInsightRequest.FindingPayload(id, "계측 fixture 원문", "https://example.com/" + id,
                "요약", role, "2026-09-07",
                List.of(new AgentInsightRequest.SentencePayload(1, "계측 fixture 원문")));
    }

    private String insightJsonValue(String key, String field) {
        return jdbcTemplate.queryForObject(
                "SELECT JSON_VALUE(action_payload, '$." + field + "') FROM agent_runs WHERE idempotency_key = ?",
                String.class, key);
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
                        "산업 동향 보도", "neutral",
                        com.example.be.domain.analysis.agent.AgentSensitivityFixtures.analyze(1),
                        "reference", "제품/공정"),
                new AgentAnalyzeResponse.Entities(List.of(), List.of(), List.of()),
                List.of(),
                new AgentAnalyzeResponse.Meta(
                        "mock", "mock", "analyze.mock.v2", 0L, 0L,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }
}
