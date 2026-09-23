package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.dto.AgentWeeklyReportRequest;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.service.LlmPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWeeklyReportOrchestratorTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private final AgentProperties properties = new AgentProperties();
    private final AgentClient client = mock(AgentClient.class);
    private final AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    private final AgentQuotaService quota = mock(AgentQuotaService.class);
    private final LlmPlanService plans = mock(LlmPlanService.class);
    private final WeeklyReportGenerator fallback = new WeeklyReportGenerator();
    private final AgentWeeklyReportOrchestrator subject = new AgentWeeklyReportOrchestrator(
            properties, client, recorder, fallback, quota, plans);
    private final QuotaReservation reservation = new QuotaReservation(
            1L, null, "weekly-report:77", AgentTask.REPORT, AgentPlan.FREE, BigDecimal.ONE);

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        when(plans.resolveRunPlan(null)).thenReturn(AgentPlan.FREE);
        when(quota.reserve(null, "weekly-report:77", AgentTask.REPORT, AgentPlan.FREE)).thenReturn(reservation);
    }

    @Test
    void generatedDocumentUsesFrozenInputSourcesAndRecordsQuotaAndAudit() {
        when(client.weeklyReport(any())).thenReturn(response(501L));
        ReportDocument result = subject.generate(77L, input(), LocalDateTime.now());
        assertEquals(ReportStatus.GENERATED, result.status());
        assertEquals(input().title(), result.title());
        assertEquals(input().sourceNotes(), result.structuredContent().sourceNotes());
        assertEquals(List.of(501L), result.reflectedFindingIds());
        assertFalse(result.markdownBody().contains("provider markdown"));
        var request = ArgumentCaptor.forClass(AgentWeeklyReportRequest.class);
        verify(client).weeklyReport(request.capture());
        assertEquals(MONDAY, request.getValue().reportDate());
        assertEquals(input().sources().getFirst().structuredContent(), request.getValue().sources().getFirst().structuredContent());
        verify(quota).completeSuccess(reservation, BigDecimal.ONE);
        verify(recorder).recordWeeklyReportSuccess(eq(request.getValue()), any(), any());
    }

    @Test
    void invalidFindingReferenceFallsBackAndStillAccountsReportedUsage() {
        when(client.weeklyReport(any())).thenReturn(response(999L));
        ReportDocument result = subject.generate(77L, input(), LocalDateTime.now());
        assertEquals(ReportStatus.FALLBACK, result.status());
        assertFalse(result.reflectedFindingIds().contains(999L));
        var failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(quota).completeObservedFailure(eq(reservation), failure.capture());
        assertEquals(BigDecimal.ONE, failure.getValue().getUsage().credits());
        verify(recorder).recordWeeklyReportFailure(any(), eq("SCHEMA_VIOLATION"), any(), any(), isNull(), any());
    }

    @Test
    void readTimeoutKeepsAuditUsageUnknownAndPassesTimeoutPhaseToQuotaSettlement() {
        when(client.weeklyReport(any())).thenThrow(new AgentClientException("PROVIDER_UNAVAILABLE", "timeout", null,
                null, AgentClientException.TimeoutPhase.READ));
        assertEquals(ReportStatus.FALLBACK, subject.generate(77L, input(), LocalDateTime.now()).status());
        var failure = ArgumentCaptor.forClass(AgentClientException.class);
        verify(quota).completeObservedFailure(eq(reservation), failure.capture());
        assertNull(failure.getValue().getUsage());
        assertTrue(failure.getValue().isReadTimeout());
        verify(recorder).recordWeeklyReportFailure(any(), any(), any(), isNull(), eq(AgentTimeoutPhase.READ), any());
    }

    @Test
    void paidQuotaUsesConfiguredFreeFallbackAndDistinctIdempotencyKey() {
        when(plans.resolveRunPlan(null)).thenReturn(AgentPlan.PAID);
        when(plans.paidExhaustedAction()).thenReturn(PaidExhaustedAction.FALLBACK_FREE);
        when(quota.reserve(null, "weekly-report:77", AgentTask.REPORT, AgentPlan.PAID))
                .thenThrow(new QuotaExceededException(AgentPlan.PAID, "exhausted"));
        var free = new QuotaReservation(2L, null, "weekly-report:77:fallback-free", AgentTask.REPORT,
                AgentPlan.FREE, BigDecimal.ONE);
        when(quota.reserve(null, free.idempotencyKey(), AgentTask.REPORT, AgentPlan.FREE)).thenReturn(free);
        when(client.weeklyReport(any())).thenReturn(response(501L));
        assertEquals(ReportStatus.GENERATED, subject.generate(77L, input(), LocalDateTime.now()).status());
        verify(client).weeklyReport(argThat(value -> value.plan() == AgentPlan.FREE
                && value.idempotencyKey().equals(free.idempotencyKey())));
    }

    @Test
    void disabledOrLegacyOnlyInputNeverUsesLlmOrQuota() {
        properties.setEnabled(false);
        assertEquals(ReportStatus.FALLBACK, subject.generate(77L, input(), LocalDateTime.now()).status());
        properties.setEnabled(true);
        var original = input();
        var legacy = new WeeklyReportInput(MONDAY, MONDAY.plusDays(6), List.of(
                new WeeklyReportInput.DailySource(10L, MONDAY, "old", "unsafe legacy body", null, List.of(501L))),
                original.missingReportDates());
        assertEquals(ReportStatus.FALLBACK, subject.generate(77L, legacy, LocalDateTime.now()).status());
        verifyNoInteractions(client, recorder, quota);
    }

    private WeeklyReportInput input() {
        var content = new ReportContent(List.of("생산 계획을 발표했습니다."), List.of(
                new ReportContent.ImportantEvent("생산 계획", "생산 계획을 발표했습니다.", "", List.of(501L))),
                List.of(), List.of("원본 일일 자료 범위"));
        return new WeeklyReportInput(MONDAY, MONDAY.plusDays(6), List.of(
                new WeeklyReportInput.DailySource(10L, MONDAY, "daily", "saved body", content, List.of(501L))),
                IntStream.rangeClosed(1, 6).mapToObj(MONDAY::plusDays).toList());
    }

    private AgentReportResponse response(Long id) {
        return new AgentReportResponse("임의 제목", List.of("생산 계획을 발표했습니다."), List.of(
                new AgentReportResponse.ImportantEvent("생산 계획", "생산 계획을 발표했습니다.",
                        "2026-09-14 일일 보고서: 생산 계획을 발표했습니다.", List.of(id))), List.of(), List.of("임의 범위"),
                "provider markdown", new AgentReportResponse.Meta("openai", "test-model", "weekly-report.ko.v1",
                10L, 5L, BigDecimal.ZERO, BigDecimal.ONE, false, false));
    }
}
