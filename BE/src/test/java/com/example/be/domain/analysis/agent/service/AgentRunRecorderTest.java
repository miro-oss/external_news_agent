package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceRequest;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceResponse;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentRunRecorderTest {

    private AgentRunJdbcRepository repository;
    private AgentRunRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRunJdbcRepository.class);
        recorder = new AgentRunRecorder(repository, new ObjectMapper());
    }

    @Test
    void recordsProviderEvidenceUsageAgainstArticle() {
        AgentEvidenceRequest request = request("run:42:article:10:evidence");
        AgentEvidenceResponse response = response(
                "gemini-3.6-flash", "evidence.ko.v2", 120L, 35L);

        recorder.recordEvidenceSuccess(42L, 10L, request, response, LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(AgentTask.VERIFY_EVIDENCE, recorded.getAgentTask());
        assertEquals(AgentTargetType.ARTICLE, recorded.getTargetType());
        assertEquals(10L, recorded.getTargetId());
        assertEquals(AgentRunStatus.SUCCESS, recorded.getStatus());
        assertEquals("gemini-3.6-flash", recorded.getLlmModel());
        assertEquals(120L, recorded.getInputTokens());
        assertEquals(35L, recorded.getOutputTokens());
        assertEquals(64, recorded.getRequestHash().length());
        assertNull(recorded.getActionPayload());
    }

    @Test
    void recordsRuleOnlyEvidenceAsZeroTokenSuccess() {
        AgentEvidenceRequest request = request("run:42:article:10:rule-evidence");
        AgentEvidenceResponse response = response(
                "evidence-rules-v3", "evidence.rules.v3", 0L, 0L);

        recorder.recordEvidenceSuccess(42L, 10L, request, response, LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(AgentRunStatus.SUCCESS, recorded.getStatus());
        assertEquals("evidence.rules.v3", recorded.getPromptVersion());
        assertEquals(0L, recorded.getInputTokens());
        assertEquals(0L, recorded.getOutputTokens());
        assertEquals(BigDecimal.ZERO, recorded.getCredits());
    }

    @Test
    void recordsEvidenceFailureUsage() {
        AgentEvidenceRequest request = request("run:42:article:10:failed-evidence");
        AgentClientException.Usage usage = new AgentClientException.Usage(
                45L, 12L, new BigDecimal("0.03"), BigDecimal.ZERO);

        recorder.recordEvidenceFailure(
                42L,
                10L,
                request,
                "PROVIDER_UNAVAILABLE",
                "provider down",
                usage,
                null,
                LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(AgentRunStatus.FAILED, recorded.getStatus());
        assertEquals("PROVIDER_UNAVAILABLE", recorded.getFailureCode());
        assertEquals(45L, recorded.getInputTokens());
        assertEquals(12L, recorded.getOutputTokens());
        assertEquals(0, new BigDecimal("0.03").compareTo(recorded.getCostUsd()));
        assertNull(recorded.getPromptVersion());
        assertNull(recorded.getActionPayload());
    }

    @Test
    void recordsInsightAgainstIssue() {
        AgentInsightRequest request = new AgentInsightRequest(
                "insight:issue:88:test",
                AgentPlan.PAID,
                List.of("CHIP_MAKER"),
                new AgentInsightRequest.TargetPayload("ISSUE", 88L),
                new AgentInsightRequest.TopicPayload("HBM", "HBM", List.of(), List.of(), List.of()),
                List.of(new AgentInsightRequest.FindingPayload(
                        501L, "기사", "https://example.com/501", "요약",
                        AgentInsightRequest.FindingRole.CURRENT, "2026-09-03",
                        List.of(new AgentInsightRequest.SentencePayload(1, "근거 문장")))));
        AgentInsightResponse response = new AgentInsightResponse(
                List.of(new AgentInsightResponse.Insight(
                        "CHIP_MAKER", "인사이트", List.of(), List.of(), List.of(), BigDecimal.ONE)),
                new AgentInsightResponse.Meta(
                        "gemini", "gemini-test", "insight.ko.v2+perspective.ko.v1",
                        20L, 10L, BigDecimal.ZERO, BigDecimal.ONE, false, false));

        InsightAuditContext context = InsightAuditContext.capture(
                "a".repeat(64), request.findings(), 1, 0, true,
                "insight.ko.v2+perspective.ko.v1", "configured-model");
        recorder.recordInsightSuccess(42L, 88L, request, response, context, LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(AgentTask.INSIGHT, recorded.getAgentTask());
        assertEquals(AgentTargetType.ISSUE, recorded.getTargetType());
        assertEquals(88L, recorded.getTargetId());
        assertEquals(AgentPlan.PAID, recorded.getLlmPlan());
        JsonNode payload = payload(recorded);
        assertEquals("INSIGHT_OBSERVATION", payload.path("type").asText());
        assertEquals(1, payload.path("schemaVersion").asInt());
        assertEquals("MISS", payload.path("cacheOutcome").asText());
        assertEquals(1, payload.path("submittedCurrentFindingCount").asInt());
        assertEquals(0, payload.path("submittedHistoryFindingCount").asInt());
        assertEquals("RESPONSE", payload.path("metadataSource").asText());
        assertEquals("COMPLETE", payload.path("usageCompleteness").asText());
    }

    @Test
    void recordsPartialCacheAndHistoryWithoutDuplicatingCostOrRawInput() {
        AgentInsightRequest request = insightRequest("partial-cache");
        InsightAuditContext context = InsightAuditContext.capture(
                "b".repeat(64), request.findings(), 3, 1, true,
                "expected-prompt", "configured-model");

        recorder.recordInsightSuccess(42L, 88L, request, insightResponse(), context, LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(new BigDecimal("0.012345"), recorded.getCostUsd());
        assertEquals("observed-model", recorded.getLlmModel());
        assertEquals("observed-prompt", recorded.getPromptVersion());
        JsonNode payload = payload(recorded);
        assertEquals("PARTIAL", payload.path("cacheOutcome").asText());
        assertEquals(3, payload.path("requestedAudienceCount").asInt());
        assertEquals(1, payload.path("cachedAudienceCount").asInt());
        assertEquals(2, payload.path("generationAudienceCount").asInt());
        assertEquals(1, payload.path("selectedCurrentFindingCount").asInt());
        assertEquals(1, payload.path("selectedHistoryFindingCount").asInt());
        assertEquals(1, payload.path("submittedCurrentFindingCount").asInt());
        assertEquals(1, payload.path("submittedHistoryFindingCount").asInt());
        assertEquals(true, payload.path("agentRequestIssued").asBoolean());
        assertEquals("expected-prompt", payload.path("expectedPromptVersion").asText());
        assertEquals("configured-model", payload.path("configuredModel").asText());
        assertEquals("COMPLETE", payload.path("usageCompleteness").asText());
        assertFalse(payload.has("costUsd"));
        assertFalse(payload.has("inputTokens"));
        assertFalse(recorded.getActionPayload().contains("원문은 저장하지 않는다"));
    }

    @Test
    void recordsActualFailureMetadataSeparatelyFromConfiguredValues() {
        AgentInsightRequest request = insightRequest("failed-insight");
        InsightAuditContext context = InsightAuditContext.capture(
                "c".repeat(64), request.findings(), 2, 0, true,
                "expected-prompt", "configured-model");
        AgentClientException.Usage usage = new AgentClientException.Usage(
                80L, 10L, new BigDecimal("0.005"), BigDecimal.ZERO);

        recorder.recordInsightFailure(42L, 88L, request,
                "SCHEMA_VIOLATION", "출력 검증 실패", usage, null, context,
                new AgentClientException.ExecutionMetadata(
                        "observed-provider", "observed-model", "observed-prompt", "AGENT_ERROR", "COMPLETE"),
                LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(AgentRunStatus.FAILED, recorded.getStatus());
        assertEquals("observed-provider", recorded.getLlmProvider());
        assertEquals("observed-model", recorded.getLlmModel());
        assertEquals("observed-prompt", recorded.getPromptVersion());
        assertEquals(usage.costUsd(), recorded.getCostUsd());
        JsonNode payload = payload(recorded);
        assertEquals("AGENT_ERROR", payload.path("metadataSource").asText());
        assertEquals("COMPLETE", payload.path("usageCompleteness").asText());
        assertEquals("configured-model", payload.path("configuredModel").asText());
        assertEquals("expected-prompt", payload.path("expectedPromptVersion").asText());
    }

    @Test
    void unavailableFailureMetadataDoesNotInventModelOrZeroCost() {
        AgentInsightRequest request = insightRequest("unavailable-insight");
        InsightAuditContext context = InsightAuditContext.capture(
                "d".repeat(64), request.findings(), 2, 0, true,
                "expected-prompt", "configured-model");

        recorder.recordInsightFailure(42L, 88L, request,
                "PROVIDER_UNAVAILABLE", "연결 실패", null, null, context, null, LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertNull(recorded.getLlmProvider());
        assertNull(recorded.getLlmModel());
        assertNull(recorded.getPromptVersion());
        assertNull(recorded.getCostUsd());
        assertNull(recorded.getInputTokens());
        assertEquals("UNAVAILABLE", payload(recorded).path("metadataSource").asText());
        assertEquals("UNKNOWN", payload(recorded).path("usageCompleteness").asText());
    }

    @Test
    void eachFullCacheRequestHasASeparateZeroUsageAuditOutsideFreeQuota() {
        InsightAuditContext context = InsightAuditContext.capture(
                "e".repeat(64), insightRequest("unused").findings(), 2, 2, false,
                "expected-prompt", null);
        LocalDateTime startedAt = LocalDateTime.now();

        recorder.recordInsightCacheHit(42L, 88L, context, startedAt);
        recorder.recordInsightCacheHit(42L, 88L, context, startedAt);

        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository, times(2)).insertIfAbsent(captor.capture());
        List<AgentRun> recorded = captor.getAllValues();
        assertNotEquals(recorded.get(0).getIdempotencyKey(), recorded.get(1).getIdempotencyKey());
        for (AgentRun run : recorded) {
            assertEquals(AgentRunStatus.REUSED, run.getStatus());
            assertEquals(AgentTask.INSIGHT, run.getAgentTask());
            assertEquals(AgentTargetType.ISSUE, run.getTargetType());
            assertNull(run.getLlmPlan());
            assertNull(run.getLlmProvider());
            assertNull(run.getLlmModel());
            assertEquals(BigDecimal.ZERO, run.getCostUsd());
            assertEquals(BigDecimal.ZERO, run.getCredits());
            assertEquals(0L, run.getInputTokens());
            assertEquals(0L, run.getOutputTokens());
            assertEquals(context.inputHash(), run.getRequestHash());
            JsonNode payload = payload(run);
            assertEquals("FULL", payload.path("cacheOutcome").asText());
            assertEquals("CACHE", payload.path("metadataSource").asText());
            assertEquals("COMPLETE", payload.path("usageCompleteness").asText());
            assertEquals(2, payload.path("cachedAudienceCount").asInt());
            assertEquals(0, payload.path("generationAudienceCount").asInt());
            assertEquals(1, payload.path("selectedCurrentFindingCount").asInt());
            assertEquals(1, payload.path("selectedHistoryFindingCount").asInt());
            assertEquals(0, payload.path("submittedCurrentFindingCount").asInt());
            assertEquals(0, payload.path("submittedHistoryFindingCount").asInt());
            assertFalse(payload.path("agentRequestIssued").asBoolean());
        }
    }

    @Test
    void keepsRepairUsagePartialEvenWhenAllFourStoredNumbersArePresent() {
        AgentInsightRequest request = insightRequest("partial-repair-usage");
        InsightAuditContext context = auditContext(request);
        AgentClientException.Usage usage = new AgentClientException.Usage(
                50L, 10L, new BigDecimal("0.001"), BigDecimal.ZERO);

        recorder.recordInsightFailure(42L, 88L, request, "PROVIDER_UNAVAILABLE", "repair 응답 미관측",
                usage, null, context, new AgentClientException.ExecutionMetadata(
                        "openai", "model", "prompt", "AGENT_ERROR", "PARTIAL"), LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(usage.costUsd(), recorded.getCostUsd());
        assertEquals("PARTIAL", payload(recorded).path("usageCompleteness").asText());
    }

    @Test
    void legacyFailureUsageRemainsUnknownWithoutExecutionMetadata() {
        AgentInsightRequest request = insightRequest("legacy-full-numbers");
        AgentClientException.Usage usage = new AgentClientException.Usage(
                50L, 10L, new BigDecimal("0.001"), BigDecimal.ZERO);

        recorder.recordInsightFailure(42L, 88L, request, "SCHEMA_VIOLATION", "실패",
                usage, null, auditContext(request), null, LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(usage.costUsd(), recorded.getCostUsd());
        assertEquals("UNKNOWN", payload(recorded).path("usageCompleteness").asText());
    }

    @ParameterizedTest
    @MethodSource("responseUsageCases")
    void responseFailureCompletenessReflectsAvailableUsageFields(AgentClientException.Usage usage,
                                                                 String expected) {
        AgentInsightRequest request = insightRequest("response-usage");

        recorder.recordInsightFailure(42L, 88L, request, "SCHEMA_VIOLATION", "실패",
                usage, null, auditContext(request), new AgentClientException.ExecutionMetadata(
                        "openai", "model", "prompt", "RESPONSE"), LocalDateTime.now());

        assertEquals(expected, payload(capturedRun()).path("usageCompleteness").asText());
    }

    private static Stream<Arguments> responseUsageCases() {
        return Stream.of(
                Arguments.of(new AgentClientException.Usage(10L, 5L, BigDecimal.ZERO, BigDecimal.ZERO), "COMPLETE"),
                Arguments.of(new AgentClientException.Usage(10L, 5L, null, BigDecimal.ZERO), "PARTIAL"),
                Arguments.of(new AgentClientException.Usage(null, null, null, null), "UNKNOWN"),
                Arguments.of(null, "UNKNOWN"));
    }

    @Test
    void successfulResponseWithMissingUsageIsMarkedPartial() {
        AgentInsightRequest request = insightRequest("success-partial-usage");
        AgentInsightResponse response = new AgentInsightResponse(List.of(), new AgentInsightResponse.Meta(
                "openai", "model", "prompt", 50L, 10L, null, BigDecimal.ZERO, false, false));

        recorder.recordInsightSuccess(42L, 88L, request, response, auditContext(request), LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertNull(recorded.getCostUsd());
        assertEquals("PARTIAL", payload(recorded).path("usageCompleteness").asText());
    }

    @ParameterizedTest
    @MethodSource("invalidFailureMetadataCases")
    void dropsInvalidFailureIdentityWithoutLosingUsage(String provider, String model, String promptVersion) {
        AgentInsightRequest request = insightRequest("invalid-failure-metadata");
        AgentClientException.Usage usage = new AgentClientException.Usage(
                50L, 10L, new BigDecimal("0.001"), BigDecimal.ZERO);

        recorder.recordInsightFailure(42L, 88L, request, "SCHEMA_VIOLATION", "메타 검증 실패",
                usage, null, auditContext(request), new AgentClientException.ExecutionMetadata(
                        provider, model, promptVersion, "RESPONSE"), LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertNull(recorded.getLlmProvider());
        assertNull(recorded.getLlmModel());
        assertNull(recorded.getPromptVersion());
        assertEquals(usage.inputTokens(), recorded.getInputTokens());
        assertEquals(usage.costUsd(), recorded.getCostUsd());
        assertEquals("RESPONSE", payload(recorded).path("metadataSource").asText());
        assertEquals("COMPLETE", payload(recorded).path("usageCompleteness").asText());
    }

    private static Stream<Arguments> invalidFailureMetadataCases() {
        return Stream.of(
                Arguments.of("p".repeat(31), "m".repeat(101), "v".repeat(51)),
                Arguments.of("공".repeat(11), "모".repeat(34), "프".repeat(17)),
                Arguments.of("provider\nname", "model\u0000name", "prompt\u0085version"),
                Arguments.of(" ", "\t", "\n"));
    }

    @Test
    void preservesValidFailureIdentityAtUtf8ColumnBoundaries() {
        AgentInsightRequest request = insightRequest("boundary-failure-metadata");
        String provider = "공".repeat(10);
        String model = "모".repeat(33) + "m";
        String prompt = "프".repeat(16) + "v2";

        recorder.recordInsightFailure(42L, 88L, request, "SCHEMA_VIOLATION", "실패",
                null, null, auditContext(request), new AgentClientException.ExecutionMetadata(
                        provider, model, prompt, "RESPONSE"), LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(provider, recorded.getLlmProvider());
        assertEquals(model, recorded.getLlmModel());
        assertEquals(prompt, recorded.getPromptVersion());
    }

    private InsightAuditContext auditContext(AgentInsightRequest request) {
        return InsightAuditContext.capture("f".repeat(64), request.findings(), 2, 0, true,
                "expected-prompt", "configured-model");
    }

    private JsonNode payload(AgentRun run) {
        return new ObjectMapper().readTree(run.getActionPayload());
    }

    private AgentInsightRequest insightRequest(String key) {
        return new AgentInsightRequest(
                key, AgentPlan.FREE, List.of("CHIP_MAKER", "MARKET_INVESTOR"),
                new AgentInsightRequest.TargetPayload("ISSUE", 88L),
                new AgentInsightRequest.TopicPayload("HBM", "HBM", List.of(), List.of(), List.of()),
                List.of(insightFinding(1L, AgentInsightRequest.FindingRole.CURRENT),
                        insightFinding(2L, AgentInsightRequest.FindingRole.HISTORY)));
    }

    private AgentInsightRequest.FindingPayload insightFinding(Long id, AgentInsightRequest.FindingRole role) {
        return new AgentInsightRequest.FindingPayload(id, "원문은 저장하지 않는다",
                "https://example.com/" + id, "요약", role, "2026-09-07",
                List.of(new AgentInsightRequest.SentencePayload(1, "원문은 저장하지 않는다")));
    }

    private AgentInsightResponse insightResponse() {
        return new AgentInsightResponse(List.of(), new AgentInsightResponse.Meta(
                "observed-provider", "observed-model", "observed-prompt",
                100L, 20L, new BigDecimal("0.012345"), BigDecimal.ZERO, false, false));
    }

    private AgentRun capturedRun() {
        ArgumentCaptor<AgentRun> captor = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository).insertIfAbsent(captor.capture());
        return captor.getValue();
    }

    private AgentEvidenceRequest request(String idempotencyKey) {
        return new AgentEvidenceRequest(
                idempotencyKey,
                AgentPlan.FREE,
                List.of(new AgentEvidenceRequest.ClaimPayload(
                        "0:0",
                        "핵심 주장",
                        "FACT",
                        null,
                        List.of(new AgentEvidenceRequest.SentencePayload(1, "근거 문장")))));
    }

    private AgentEvidenceResponse response(String model,
                                           String promptVersion,
                                           long inputTokens,
                                           long outputTokens) {
        return new AgentEvidenceResponse(
                List.of(new AgentEvidenceResponse.Result(
                        "0:0", "grounded", List.of(1), "검증 결과")),
                new AgentEvidenceResponse.Meta(
                        "gemini",
                        model,
                        promptVersion,
                        inputTokens,
                        outputTokens,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        false,
                        false));
    }
}
