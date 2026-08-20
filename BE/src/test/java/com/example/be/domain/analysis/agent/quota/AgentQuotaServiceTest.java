package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.settings.exception.LlmException;
import com.example.be.domain.settings.service.LlmPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentQuotaServiceTest {

    @Mock
    private AgentQuotaJdbcRepository repository;

    @Mock
    private LlmPlanService planService;

    private AgentProperties properties;
    private AgentQuotaService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setToken("agent-secret");
        service = new AgentQuotaService(repository, properties, planService);
    }

    @Test
    void serializesCheckBeforeInsertAndLeavesReportReserveUntouched() {
        stubUsage(BigDecimal.ZERO, new BigDecimal("69"), new BigDecimal("69"), new BigDecimal("100"));
        QuotaReservation reservation = reservation(AgentTask.ANALYZE, AgentPlan.PAID);
        when(repository.findByIdempotencyKey("run:42:article:10"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reservation));
        when(repository.countRunAnalysis(42L, AgentPlan.PAID)).thenReturn(19);

        QuotaReservation result = service.reserve(
                42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID);

        assertEquals(reservation, result);
        InOrder order = inOrder(repository);
        order.verify(repository).lockSingletonSettings();
        order.verify(repository).insert(
                eq(42L), eq("run:42:article:10"), eq(AgentTask.ANALYZE),
                eq(AgentPlan.PAID), eq(BigDecimal.ONE), any(LocalDateTime.class));
    }

    @Test
    void rejectsAnalysisAtSeventyCreditsButStillAllowsReport() {
        stubUsage(BigDecimal.ZERO, new BigDecimal("70"), new BigDecimal("70"), new BigDecimal("100"));
        when(repository.countRunAnalysis(42L, AgentPlan.PAID)).thenReturn(10);

        assertThrows(QuotaExceededException.class, () -> service.reserve(
                42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID));
        verify(repository, never()).insert(
                any(), any(), any(), any(), any(), any());

        stubUsage(BigDecimal.ZERO, new BigDecimal("70"), new BigDecimal("70"), new BigDecimal("100"));
        QuotaReservation report = new QuotaReservation(
                2L, 42L, "run:42:report", AgentTask.REPORT, AgentPlan.PAID, BigDecimal.ONE);
        when(repository.findByIdempotencyKey("run:42:report"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(report));

        assertEquals(report, service.reserve(
                42L, "run:42:report", AgentTask.REPORT, AgentPlan.PAID));
    }

    @Test
    void releasesKnownSafeFailuresButConsumesTimeoutReservation() {
        QuotaReservation first = reservation(AgentTask.ANALYZE, AgentPlan.PAID);
        AgentClientException unavailable = new AgentClientException(
                "PROVIDER_UNAVAILABLE", "down", null, null, false);

        service.completeFailure(first, unavailable);

        verify(repository).release(eq(first), any(LocalDateTime.class));

        QuotaReservation second = new QuotaReservation(
                2L, 42L, "run:42:article:11", AgentTask.ANALYZE, AgentPlan.PAID, BigDecimal.ONE);
        AgentClientException timeout = new AgentClientException(
                "PROVIDER_UNAVAILABLE", "timeout", null, null, true);

        service.completeFailure(second, timeout);

        verify(repository).consume(eq(second), eq(BigDecimal.ONE), any(LocalDateTime.class));
    }

    @Test
    void rejectsPaidRunBeforeStartWhenAnalysisBudgetIsGone() {
        stubUsage(BigDecimal.ZERO, new BigDecimal("70"), new BigDecimal("70"), new BigDecimal("100"));

        LlmException exception = assertThrows(
                LlmException.class,
                () -> service.assertRunCanStart(AgentPlan.PAID));

        assertEquals("QUOTA429", exception.getCode().getCode());
        assertEquals(AgentPlan.PAID.name(), exception.getResult().get("plan"));
        assertEquals(BigDecimal.ZERO, exception.getResult().get("dailyRemaining"));
    }

    private void stubUsage(BigDecimal freeDaily,
                           BigDecimal paidDaily,
                           BigDecimal paidAnalysisDaily,
                           BigDecimal paidMonthly) {
        when(repository.usage(eq(AgentPlan.FREE), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(freeDaily);
        when(repository.usage(eq(AgentPlan.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(paidDaily, paidMonthly);
        when(repository.usage(
                eq(AgentPlan.PAID), any(LocalDateTime.class), any(LocalDateTime.class), eq(AgentTask.ANALYZE)))
                .thenReturn(paidAnalysisDaily);
    }

    private QuotaReservation reservation(AgentTask task, AgentPlan plan) {
        return new QuotaReservation(
                1L, 42L, "run:42:article:10", task, plan, BigDecimal.ONE);
    }
}
