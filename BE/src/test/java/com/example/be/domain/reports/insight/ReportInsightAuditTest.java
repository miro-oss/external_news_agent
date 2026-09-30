package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.agent.service.ReportInsightAuditContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static com.example.be.domain.reports.insight.ReportInsightTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportInsightAuditTest {
    private final AgentRunJdbcRepository repository = mock(AgentRunJdbcRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRunRecorder recorder = new AgentRunRecorder(repository, mapper);

    @ParameterizedTest @ValueSource(strings = {"COMPLETE", "PARTIAL", "UNKNOWN"})
    void reportFailuresPreserveAgentErrorUsageCompletenessEvenWhenKnownTotalsArePresent(String completeness) {
        var failure = new AgentClientException("PROVIDER_UNAVAILABLE", "관측되지 않은 후속 호출", null,
                new AgentClientException.Usage(20L, 10L, BigDecimal.ONE, BigDecimal.ONE),
                AgentClientException.TimeoutPhase.NONE, new AgentClientException.ExecutionMetadata(
                "openai", "model", ReportInsightService.PROMPT_VERSION, "AGENT_ERROR", completeness));
        recorder.recordReportInsightFailure(20L, request(), failure, context(), LocalDateTime.now());
        var run = captured();
        var audit = mapper.readTree(run.getActionPayload());
        assertEquals(AgentTargetType.REPORT, run.getTargetType());
        assertEquals("AGENT_ERROR", audit.get("metadataSource").asText());
        assertEquals(completeness, audit.get("usageCompleteness").asText());
        assertEquals(BigDecimal.ONE, run.getCredits());
        assertFalse(run.getActionPayload().contains("회사는 생산 계획"));
    }
    @Test void metadataAbsenceDoesNotClaimCompleteUsage() {
        var failure = new AgentClientException("PROVIDER_UNAVAILABLE", "unknown");
        recorder.recordReportInsightFailure(20L, request(), failure, context(), LocalDateTime.now());
        var audit = mapper.readTree(captured().getActionPayload());
        assertEquals("UNAVAILABLE", audit.get("metadataSource").asText());
        assertEquals("UNKNOWN", audit.get("usageCompleteness").asText());
    }
    @Test void fullSuccessfulResponseIsCompleteAndIdentifiedAsResponse() {
        recorder.recordReportInsightSuccess(20L, request(), response(), context(), LocalDateTime.now());
        var audit = mapper.readTree(captured().getActionPayload());
        assertEquals("RESPONSE", audit.get("metadataSource").asText());
        assertEquals("COMPLETE", audit.get("usageCompleteness").asText());
    }
    @Test void cacheAuditRecordsCompleteZeroUsageWithNoPlan() {
        recorder.recordReportInsightCacheHit(20L, 10L, context(), LocalDateTime.now());
        var run = captured();
        var audit = mapper.readTree(run.getActionPayload());
        assertEquals("CACHE", audit.get("metadataSource").asText());
        assertEquals("COMPLETE", audit.get("usageCompleteness").asText());
        assertEquals(BigDecimal.ZERO, run.getCredits());
        assertNull(run.getLlmPlan());
    }
    private AgentRun captured() {
        var captured = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository).insertIfAbsent(captured.capture());
        return captured.getValue();
    }
    private ReportInsightAuditContext context() { return new ReportInsightAuditContext(1, "REPORT_INSIGHT_OBSERVATION",
            "a".repeat(64), ReportInsightService.PROMPT_VERSION, ReportInsightService.RUBRIC_VERSION, 2, 1, 0, true); }
}
