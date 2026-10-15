package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.quota.AgentQuotaJdbcRepository;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.agent.service.ReportInsightAuditContext;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.settings.service.LlmPlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
        assertEquals("CHIP_MAKER", audit.get("audiences").get(0).asText());
        assertEquals(BigDecimal.ONE, run.getCredits());
        assertFalse(run.getActionPayload().contains("회사는 생산 계획"));
    }
    @Test void metadataAbsenceDoesNotClaimCompleteUsage() {
        var failure = new AgentClientException("PROVIDER_UNAVAILABLE", "unknown");
        recorder.recordReportInsightFailure(20L, request(), failure, context(), LocalDateTime.now());
        var audit = mapper.readTree(captured().getActionPayload());
        assertEquals("UNAVAILABLE", audit.get("metadataSource").asText());
        assertEquals("UNKNOWN", audit.get("usageCompleteness").asText());
        assertTrue(audit.get("validationFailure").isNull());
    }
    @Test void fullSuccessfulResponseIsCompleteAndIdentifiedAsResponse() {
        recorder.recordReportInsightSuccess(20L, request(), response(), context(), LocalDateTime.now());
        var audit = mapper.readTree(captured().getActionPayload());
        assertEquals("RESPONSE", audit.get("metadataSource").asText());
        assertEquals("COMPLETE", audit.get("usageCompleteness").asText());
        assertEquals("CHIP_MAKER", audit.get("audiences").get(0).asText());
        assertTrue(audit.get("validationFailure").isNull());
    }
    @Test void cacheAuditRecordsCompleteZeroUsageWithNoPlan() {
        recorder.recordReportInsightCacheHit(20L, 10L,
                context().withAudiences(List.of("CHIP_MAKER", "IT_INFRA")), LocalDateTime.now());
        var run = captured();
        var audit = mapper.readTree(run.getActionPayload());
        assertEquals("CACHE", audit.get("metadataSource").asText());
        assertEquals("COMPLETE", audit.get("usageCompleteness").asText());
        assertEquals(BigDecimal.ZERO, run.getCredits());
        assertNull(run.getLlmPlan());
        assertEquals(List.of("CHIP_MAKER", "IT_INFRA"), mapper.convertValue(audit.get("audiences"), List.class));
        assertTrue(audit.get("validationFailure").isNull());
    }
    @Test void persistsBoundedExplanationDiagnosticsWithoutFailureProse() {
        var diagnostics = new AgentClientException.ValidationFailure("EXPLAIN-002", 2,
                "OutputValidationError", 1, List.of("report_evidence_insufficient"),
                List.of(new AgentClientException.ValidationIssue("CHIP_MAKER", "assessments[501].reason",
                        "report_evidence_insufficient", List.of("501:0"))), false);
        var failure = new AgentClientException("SCHEMA_VIOLATION", "private provider prose", null,
                new AgentClientException.Usage(20L, 10L, BigDecimal.ONE, BigDecimal.ZERO),
                AgentClientException.TimeoutPhase.NONE,
                new AgentClientException.ExecutionMetadata("openai", "model", ReportInsightService.PROMPT_VERSION,
                        "AGENT_ERROR", "COMPLETE"), diagnostics);

        recorder.recordReportInsightFailure(20L, request(), failure, context(), LocalDateTime.now());

        var run = captured();
        var audit = mapper.readValue(run.getActionPayload(), ReportInsightAuditContext.class);
        assertEquals(diagnostics, audit.validationFailure());
        assertEquals(List.of(Audience.CHIP_MAKER), audit.audiences());
        assertEquals("COMPLETE", audit.usageCompleteness());
        assertFalse(run.getActionPayload().contains("private provider prose"));
        assertFalse(run.getActionPayload().contains(request().findings().getFirst().claims().getFirst().text()));
    }
    @Test void legacyAuditJsonKeepsUnknownAudienceAndMissingDiagnostics() {
        var old = mapper.readTree(mapper.writeValueAsString(context()));
        ((tools.jackson.databind.node.ObjectNode) old).remove("audiences");
        ((tools.jackson.databind.node.ObjectNode) old).remove("validationFailure");

        var restored = mapper.treeToValue(old, ReportInsightAuditContext.class);

        assertEquals(1, restored.schemaVersion());
        assertEquals(1, restored.requestedAudienceCount());
        assertEquals(List.of(), restored.audiences());
        assertNull(restored.validationFailure());
        assertEquals("a".repeat(64), restored.inputHash());
    }
    @Test void auditAudienceNamesRemainClosedAndCannotContainProse() {
        assertThrows(IllegalArgumentException.class,
                () -> context().withAudiences(List.of("CHIP_MAKER\nprivate prose")));
        assertThrows(IllegalArgumentException.class,
                () -> context().withAudiences(List.of("CHIP_MAKER", "CHIP_MAKER")));
    }
    @ParameterizedTest @CsvSource({"PARTIAL,1", "UNKNOWN,1", "PARTIAL,6", "UNKNOWN,6"})
    void incompleteFailureKeepsObservedAuditAndConservativeQuotaTogether(String completeness, String observed) {
        var quotaRepository = mock(AgentQuotaJdbcRepository.class);
        var quota = new AgentQuotaService(quotaRepository, new AgentProperties(), mock(LlmPlanService.class));
        var executions = new ReportInsightExecutionRecorder(recorder, quota, mock(ReportInsightPersistenceService.class));
        var reservation = new QuotaReservation(1L, null, "report-insight:10:hash", AgentTask.INSIGHT,
                AgentPlan.PAID, new BigDecimal("5"));
        var actual = new BigDecimal(observed);
        var failure = new AgentClientException("PROVIDER_UNAVAILABLE", "unobserved next call", null,
                new AgentClientException.Usage(20L, 10L, BigDecimal.ONE, actual),
                AgentClientException.TimeoutPhase.NONE, new AgentClientException.ExecutionMetadata(
                "openai", "model", ReportInsightService.PROMPT_VERSION, "AGENT_ERROR", completeness));
        when(repository.insertIfAbsent(any())).thenReturn(true);

        executions.failure(20L, request(), failure, context(), LocalDateTime.now(), reservation);

        var run = captured();
        assertEquals(actual, run.getCredits());
        assertEquals(completeness, mapper.readTree(run.getActionPayload()).get("usageCompleteness").asText());
        if (actual.compareTo(reservation.reservedUnits()) > 0) {
            // Released reservations leave the known audit credits authoritative in usage queries.
            verify(quotaRepository).release(eq(reservation), any(LocalDateTime.class));
            verify(quotaRepository, never()).consume(any(), any(), any());
        } else {
            verify(quotaRepository).consume(eq(reservation), eq(reservation.reservedUnits()), any(LocalDateTime.class));
            verify(quotaRepository, never()).release(any(), any());
        }
    }
    private AgentRun captured() {
        var captured = ArgumentCaptor.forClass(AgentRun.class);
        verify(repository).insertIfAbsent(captured.capture());
        return captured.getValue();
    }
    private ReportInsightAuditContext context() { return new ReportInsightAuditContext(1, "REPORT_INSIGHT_OBSERVATION",
            "a".repeat(64), ReportInsightService.PROMPT_VERSION, ReportInsightService.RUBRIC_VERSION, 2, 1, 0, true); }
}
