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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
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
                        List.of(new AgentInsightRequest.SentencePayload(1, "근거 문장")))));
        AgentInsightResponse response = new AgentInsightResponse(
                List.of(new AgentInsightResponse.Insight(
                        "CHIP_MAKER", "인사이트", List.of(), List.of(), List.of(), BigDecimal.ONE)),
                new AgentInsightResponse.Meta(
                        "gemini", "gemini-test", "insight.ko.v1+perspective.ko.v1",
                        20L, 10L, BigDecimal.ZERO, BigDecimal.ONE, false, false));

        recorder.recordInsightSuccess(42L, 88L, request, response, LocalDateTime.now());

        AgentRun recorded = capturedRun();
        assertEquals(AgentTask.INSIGHT, recorded.getAgentTask());
        assertEquals(AgentTargetType.ISSUE, recorded.getTargetType());
        assertEquals(88L, recorded.getTargetId());
        assertEquals(AgentPlan.PAID, recorded.getLlmPlan());
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
