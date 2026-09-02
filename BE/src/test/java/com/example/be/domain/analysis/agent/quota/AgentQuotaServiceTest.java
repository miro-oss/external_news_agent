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
        stubUsage(BigDecimal.ZERO, new BigDecimal("65"), new BigDecimal("65"), new BigDecimal("100"));
        QuotaReservation reservation = reservation(AgentTask.ANALYZE, AgentPlan.PAID);
        when(repository.findByIdempotencyKey("run:42:article:10"))
                .thenReturn(Optional.of(reservation));

        QuotaReservation result = service.reserve(
                42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID);

        assertEquals(reservation, result);
        InOrder order = inOrder(repository);
        order.verify(repository).lockSingletonSettings();
        order.verify(repository).releaseExpired(any(LocalDateTime.class), any(LocalDateTime.class));
        order.verify(repository).insert(
                eq(42L), eq("run:42:article:10"), eq(AgentTask.ANALYZE),
                eq(AgentPlan.PAID), eq(new BigDecimal("5")), any(LocalDateTime.class));
    }

    @Test
    void rejectsAnalysisAtSeventyCreditsButStillAllowsReport() {
        stubUsage(BigDecimal.ZERO, new BigDecimal("70"), new BigDecimal("70"), new BigDecimal("100"));

        assertThrows(QuotaExceededException.class, () -> service.reserve(
                42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID));
        verify(repository, never()).insert(
                any(), any(), any(), any(), any(), any());

        stubUsage(BigDecimal.ZERO, new BigDecimal("70"), new BigDecimal("70"), new BigDecimal("100"));
        QuotaReservation report = new QuotaReservation(
                2L, 42L, "run:42:report", AgentTask.REPORT, AgentPlan.PAID, new BigDecimal("5"));
        when(repository.findByIdempotencyKey("run:42:report"))
                .thenReturn(Optional.of(report));

        assertEquals(report, service.reserve(
                42L, "run:42:report", AgentTask.REPORT, AgentPlan.PAID));
    }

    @Test
    void selfCritiqueCannotConsumePaidReportReserve() {
        stubUsage(BigDecimal.ZERO, new BigDecimal("70"), new BigDecimal("70"),
                new BigDecimal("100"));

        assertThrows(QuotaExceededException.class, () -> service.reserve(
                42L,
                "run:42:issue:88:self-critique",
                AgentTask.SELF_CRITIQUE,
                AgentPlan.PAID));

        verify(repository, never()).insert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void insightUsesSharedWorkBudgetAndHasFifteenCreditCap() {
        stubUsage(BigDecimal.ZERO, new BigDecimal("11"), BigDecimal.ZERO,
                new BigDecimal("100"), new BigDecimal("11"));

        assertThrows(QuotaExceededException.class, () -> service.reserve(
                null,
                "insight:issue:88:cap",
                AgentTask.INSIGHT,
                AgentPlan.PAID));

        verify(repository, never()).insert(any(), any(), any(), any(), any(), any());
    }

    @Test
    void consumedInsightReducesRemainingAnalysisBudget() {
        stubUsage(BigDecimal.ZERO, new BigDecimal("70"), new BigDecimal("60"),
                new BigDecimal("100"), new BigDecimal("10"));

        assertThrows(QuotaExceededException.class, () -> service.reserve(
                42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID));
    }

    @Test
    void releasesUnavailableAndConnectTimeoutButConsumesReadTimeoutReservation() {
        QuotaReservation first = reservation(AgentTask.ANALYZE, AgentPlan.PAID);
        AgentClientException unavailable = new AgentClientException(
                "PROVIDER_UNAVAILABLE", "down");

        service.completeFailure(first, unavailable);

        verify(repository).release(eq(first), any(LocalDateTime.class));

        QuotaReservation second = new QuotaReservation(
                2L, 42L, "run:42:article:11", AgentTask.ANALYZE,
                AgentPlan.PAID, new BigDecimal("5"));
        AgentClientException connectTimeout = new AgentClientException(
                "PROVIDER_UNAVAILABLE", "connect timeout", null, null,
                AgentClientException.TimeoutPhase.CONNECT);

        service.completeFailure(second, connectTimeout);

        verify(repository).release(eq(second), any(LocalDateTime.class));

        QuotaReservation third = new QuotaReservation(
                3L, 42L, "run:42:article:12", AgentTask.ANALYZE,
                AgentPlan.PAID, new BigDecimal("5"));
        AgentClientException readTimeout = new AgentClientException(
                "PROVIDER_UNAVAILABLE", "read timeout", null, null,
                AgentClientException.TimeoutPhase.READ);

        service.completeFailure(third, readTimeout);

        verify(repository).consume(eq(third), eq(new BigDecimal("5")), any(LocalDateTime.class));
    }

    @Test
    void releasesOverCapFailureSoAgentRunActualCreditsRemainAuthoritative() {
        QuotaReservation reservation = reservation(AgentTask.ANALYZE, AgentPlan.PAID);
        AgentClientException failure = new AgentClientException(
                "BUDGET_EXCEEDED",
                "over cap",
                null,
                new AgentClientException.Usage(10L, 5L, BigDecimal.ONE, new BigDecimal("6")));

        service.completeFailure(reservation, failure);

        verify(repository).release(eq(reservation), any(LocalDateTime.class));
        verify(repository, never()).consume(eq(reservation), any(), any());
    }

    @Test
    void rejectsActualSuccessUsageAboveReservedMaximum() {
        QuotaReservation reservation = reservation(AgentTask.ANALYZE, AgentPlan.PAID);

        assertThrows(IllegalStateException.class,
                () -> service.completeSuccess(reservation, new BigDecimal("6")));

        verify(repository).release(eq(reservation), any(LocalDateTime.class));
    }

    @Test
    void settlesRuleOnlyEvidenceAtZeroWithoutConsumingFreeCall() {
        QuotaReservation reservation = new QuotaReservation(
                2L, 42L, "run:42:article:10:evidence:0:0",
                AgentTask.VERIFY_EVIDENCE, AgentPlan.FREE, BigDecimal.ONE);

        service.completeSuccess(reservation, BigDecimal.ZERO, false);

        verify(repository).consume(
                eq(reservation), eq(BigDecimal.ZERO), any(LocalDateTime.class));
    }

    @Test
    void stillConsumesOneFreeUnitWhenEvidenceProviderWasInvoked() {
        QuotaReservation reservation = new QuotaReservation(
                2L, 42L, "run:42:article:10:evidence:0:0",
                AgentTask.VERIFY_EVIDENCE, AgentPlan.FREE, BigDecimal.ONE);

        service.completeSuccess(reservation, BigDecimal.ZERO, true);

        verify(repository).consume(
                eq(reservation), eq(BigDecimal.ONE), any(LocalDateTime.class));
    }

    @Test
    void settlesRuleOnlySelfCritiqueAtZero() {
        QuotaReservation reservation = new QuotaReservation(
                3L, 42L, "run:42:issue:88:self-critique",
                AgentTask.SELF_CRITIQUE, AgentPlan.FREE, BigDecimal.ONE);

        service.completeSuccess(reservation, BigDecimal.ZERO, false);

        verify(repository).consume(
                eq(reservation), eq(BigDecimal.ZERO), any(LocalDateTime.class));
    }

    @Test
    void rejectsProviderFreeSettlementForNonEvidenceTask() {
        QuotaReservation reservation = reservation(AgentTask.ANALYZE, AgentPlan.FREE);

        assertThrows(IllegalArgumentException.class,
                () -> service.completeSuccess(reservation, BigDecimal.ZERO, false));

        verify(repository, never()).consume(eq(reservation), any(), any());
    }

    @Test
    void rejectsReuseOfSettledIdempotencyKeyBeforeCallingProvider() {
        when(repository.findStatusByIdempotencyKey("run:42:article:10"))
                .thenReturn(Optional.of("CONSUMED"));

        assertThrows(IllegalStateException.class, () -> service.reserve(
                42L, "run:42:article:10", AgentTask.ANALYZE, AgentPlan.PAID));

        verify(repository, never()).insert(any(), any(), any(), any(), any(), any());
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
        stubUsage(freeDaily, paidDaily, paidAnalysisDaily, paidMonthly, BigDecimal.ZERO);
    }

    private void stubUsage(BigDecimal freeDaily,
                           BigDecimal paidDaily,
                           BigDecimal paidAnalysisDaily,
                           BigDecimal paidMonthly,
                           BigDecimal paidInsightDaily) {
        when(repository.usage(eq(AgentPlan.FREE), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(freeDaily);
        when(repository.usage(eq(AgentPlan.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(paidDaily, paidMonthly);
        when(repository.analysisUsage(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(paidAnalysisDaily);
        when(repository.usage(
                eq(AgentPlan.PAID), any(LocalDateTime.class), any(LocalDateTime.class),
                eq(AgentTask.INSIGHT)))
                .thenReturn(paidInsightDaily);
    }

    private QuotaReservation reservation(AgentTask task, AgentPlan plan) {
        return new QuotaReservation(
                1L, 42L, "run:42:article:10", task, plan, new BigDecimal("5"));
    }
}
