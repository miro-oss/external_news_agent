package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportChangesRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportChangesResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.DuplicateQuotaReservationException;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentReportComparisonAnalyzerTest {
    private final AgentClient client = mock(AgentClient.class);
    private final AgentQuotaService quota = mock(AgentQuotaService.class);
    private final LlmPlanService plans = mock(LlmPlanService.class);
    private final ReportComparisonRunRecorder recorder = mock(ReportComparisonRunRecorder.class);
    private final AgentProperties properties = new AgentProperties();
    private AgentReportComparisonAnalyzer analyzer;
    private final QuotaReservation reservation = new QuotaReservation(1L, null, "report-changes:101:v1",
            AgentTask.REPORT_CHANGES, AgentPlan.PAID, new BigDecimal("5"));
    private final List<ComparisonCandidate> candidates = List.of(new ComparisonCandidate("issue-88", "SAME_ISSUE",
            List.of(new ComparisonClaimInput("old", "하반기 양산 예정", List.of("하반기 양산 예정이라고 발표했다."))),
            List.of(new ComparisonClaimInput("new", "10월 양산 예정", List.of("10월 양산 예정이라고 발표했다.")))));

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        analyzer = new AgentReportComparisonAnalyzer(properties, client, quota, plans, recorder, JsonMapper.builder().build());
        when(plans.resolveRunPlan(null)).thenReturn(AgentPlan.PAID);
        when(quota.reserve(null, reservation.idempotencyKey(), AgentTask.REPORT_CHANGES, AgentPlan.PAID)).thenReturn(reservation);
    }

    @Test
    void reservesOnceAndRecordsValidatedResultAndActualUsage() {
        var response = response(new ComparisonAssessment("issue-88", "UPDATED", "양산 시점이 구체화되었습니다.", List.of("old"), List.of("new")));
        when(client.reportChanges(any())).thenReturn(response);
        assertEquals(response.items(), analyzer.analyze(101, 100, candidates));
        verify(quota).reserve(null, "report-changes:101:v1", AgentTask.REPORT_CHANGES, AgentPlan.PAID);
        verify(quota).completeSuccess(reservation, new BigDecimal("1"));
        verify(recorder).success(any(), eq(response), any());
    }

    @Test
    void rejectsCrossSideReferencesAndStillSettlesProviderUsage() {
        when(client.reportChanges(any())).thenReturn(response(new ComparisonAssessment("issue-88", "UPDATED", "잘못된 근거", List.of("new"), List.of("old"))));
        assertThrows(AgentClientException.class, () -> analyzer.analyze(101, 100, candidates));
        var failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(quota).completeFailure(eq(reservation), failure.capture());
        assertEquals(new BigDecimal("1"), failure.getValue().getUsage().credits());
        assertEquals("openai", failure.getValue().getExecutionMetadata().provider());
        assertEquals("test-model", failure.getValue().getExecutionMetadata().model());
        verify(recorder, never()).success(any(), any(), any());
    }

    @Test
    void cannotPromoteSameIssueOrMergeToRefutation() {
        var response = response(new ComparisonAssessment("issue-88", "REFUTATION", "반박", List.of("old"), List.of("new")));
        assertThrows(AgentClientException.class, () -> AgentReportComparisonAnalyzer.validateResponse(response, candidates));
    }

    @Test
    void refusesDuplicateReservationWithoutCallingProvider() {
        when(quota.reserve(null, reservation.idempotencyKey(), AgentTask.REPORT_CHANGES, AgentPlan.PAID))
                .thenThrow(new DuplicateQuotaReservationException(reservation.idempotencyKey(), "CONSUMED"));
        assertThrows(DuplicateQuotaReservationException.class, () -> analyzer.analyze(101, 100, candidates));
        verifyNoInteractions(client);
    }

    @Test
    void readTimeoutConsumesReservedExposureAndDoesNotRetryProvider() {
        when(client.reportChanges(any())).thenThrow(new AgentClientException("PROVIDER_UNAVAILABLE", "timeout", null,
                null, AgentClientException.TimeoutPhase.READ));
        assertThrows(AgentClientException.class, () -> analyzer.analyze(101, 100, candidates));
        var failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(quota).completeFailure(eq(reservation), failure.capture());
        assertEquals(reservation.reservedUnits(), failure.getValue().getUsage().credits());
        assertTrue(failure.getValue().isReadTimeout());
        verify(client, times(1)).reportChanges(any());
    }

    @Test
    void honorsConfiguredFreeFallbackOnlyBeforeAnyProviderCall() {
        when(quota.reserve(null, reservation.idempotencyKey(), AgentTask.REPORT_CHANGES, AgentPlan.PAID))
                .thenThrow(new QuotaExceededException(AgentPlan.PAID, "exhausted"));
        when(plans.paidExhaustedAction()).thenReturn(PaidExhaustedAction.FALLBACK_FREE);
        var fallback = new QuotaReservation(2L, null, reservation.idempotencyKey() + ":fallback-free", AgentTask.REPORT_CHANGES, AgentPlan.FREE, BigDecimal.ONE);
        when(quota.reserve(null, fallback.idempotencyKey(), AgentTask.REPORT_CHANGES, AgentPlan.FREE)).thenReturn(fallback);
        when(client.reportChanges(any())).thenReturn(response(new ComparisonAssessment("issue-88", "UNCHANGED", "변화 확인 안 됨", List.of("old"), List.of("new"))));
        analyzer.analyze(101, 100, candidates);
        var request = ArgumentCaptor.forClass(AgentReportChangesRequest.class);
        verify(client).reportChanges(request.capture());
        assertEquals(AgentPlan.FREE, request.getValue().plan());
        assertEquals(fallback.idempotencyKey(), request.getValue().idempotencyKey());
    }

    @Test
    void missingOrDuplicateCandidatesCannotBePublished() {
        assertThrows(AgentClientException.class, () -> AgentReportComparisonAnalyzer.validateResponse(response(), candidates));
        var item = new ComparisonAssessment("issue-88", "UNCHANGED", "동일", List.of(), List.of());
        assertThrows(AgentClientException.class, () -> AgentReportComparisonAnalyzer.validateResponse(response(item, item), candidates));
    }

    @Test
    void emptyComparisonDoesNotReserveBudgetOrCallProvider() {
        assertEquals(List.of(), analyzer.analyze(101, 100, List.of()));
        verifyNoInteractions(quota, client, recorder);
    }

    @Test
    void rejectsMissingFlagsAndSanitizesMalformedUsageWithoutReleasingExposure() {
        var metadata = new AgentReportChangesResponse.Meta("openai", "test-model",
                AgentReportComparisonAnalyzer.PROMPT_VERSION, -1L, 5L,
                new BigDecimal("-1"), new BigDecimal("-1"), null, null);
        when(client.reportChanges(any())).thenReturn(new AgentReportChangesResponse(List.of(), metadata));
        var failure = assertThrows(AgentClientException.class, () -> analyzer.analyze(101, 100, candidates));
        assertNull(failure.getUsage().inputTokens());
        assertNull(failure.getUsage().costUsd());
        assertEquals(reservation.reservedUnits(), failure.getUsage().credits());
        verify(quota).completeFailure(reservation, failure);
        verify(recorder).failure(any(), eq(failure), any());
    }

    @Test
    void missingMockOrTruncationFlagCannotPublishOtherwiseValidResponse() {
        var valid = response(new ComparisonAssessment("issue-88", "UNCHANGED", "동일", List.of("old"), List.of("new")));
        var meta = valid.meta();
        for (int missing = 0; missing < 2; missing++) {
            var incomplete = new AgentReportChangesResponse.Meta(meta.provider(), meta.model(), meta.promptVersion(),
                    meta.inputTokens(), meta.outputTokens(), meta.costUsd(), meta.credits(),
                    missing == 0 ? null : false, missing == 1 ? null : false);
            assertThrows(AgentClientException.class, () -> AgentReportComparisonAnalyzer.validateResponse(
                    new AgentReportChangesResponse(valid.items(), incomplete), candidates));
        }
    }

    @Test
    void invalidDatabaseMetadataStillReachesFailureSettlement() {
        var metadata = new AgentReportChangesResponse.Meta("openai", "모델".repeat(30),
                AgentReportComparisonAnalyzer.PROMPT_VERSION, 1L, 1L,
                new BigDecimal("1E+2147483647"), new BigDecimal("1E+2147483647"), false, false);
        var item = new ComparisonAssessment("issue-88", "UNCHANGED", "동일", List.of("old"), List.of("new"));
        when(client.reportChanges(any())).thenReturn(new AgentReportChangesResponse(List.of(item), metadata));
        var failure = assertThrows(AgentClientException.class, () -> analyzer.analyze(101, 100, candidates));
        assertNull(failure.getUsage().costUsd());
        assertEquals(reservation.reservedUnits(), failure.getUsage().credits());
        verify(quota).completeFailure(reservation, failure);
    }

    private AgentReportChangesResponse response(ComparisonAssessment... items) {
        return new AgentReportChangesResponse(List.of(items), new AgentReportChangesResponse.Meta("openai", "test-model",
                AgentReportComparisonAnalyzer.PROMPT_VERSION, 10L, 5L, new BigDecimal("0.001"), new BigDecimal("1"), false, false));
    }
}
