package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentExploreRequest;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaJdbcRepository;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.settings.service.LlmPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueInvestigationQuotaRecoveryTest {

    private static final Long RUN_ID = 42L;
    private static final Long ISSUE_ID = 88L;
    private static final String KEY = "run:42:issue:88:investigation";
    private static final String STEP_KEY = KEY + ":step:1";
    private static final BigDecimal RESERVED_UNITS = new BigDecimal("5");

    @Mock
    private CollectionRunRepository runRepository;
    @Mock
    private IssueInvestigationContextService contextService;
    @Mock
    private IssueInvestigationJdbcRepository investigationRepository;
    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private AgentRunJdbcRepository agentRunJdbcRepository;
    @Mock
    private AgentClient agentClient;
    @Mock
    private IssueInvestigationGuard guard;
    @Mock
    private IssueInvestigationActionExecutor actionExecutor;
    @Mock
    private AgentQuotaJdbcRepository quotaRepository;
    @Mock
    private LlmPlanService planService;
    @Mock
    private AgentRunRecorder recorder;
    @Mock
    private CollectionResultWriter resultWriter;

    private IssueInvestigationOrchestrator orchestrator;
    private QuotaReservation reservation;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        AgentQuotaService quotaService = new AgentQuotaService(
                quotaRepository, properties, planService);
        orchestrator = new IssueInvestigationOrchestrator(
                properties, runRepository, contextService, investigationRepository,
                agentRunRepository, agentRunJdbcRepository, agentClient, guard,
                actionExecutor, quotaService, recorder, resultWriter);
        reservation = new QuotaReservation(
                1L, RUN_ID, STEP_KEY, AgentTask.INVESTIGATE, AgentPlan.PAID, RESERVED_UNITS);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(
                CollectionRun.builder().id(RUN_ID).llmPlan(AgentPlan.PAID).build()));
        when(contextService.candidates(RUN_ID)).thenReturn(List.of(context()));
        when(quotaRepository.findByIdempotencyKey(STEP_KEY))
                .thenReturn(Optional.of(reservation));
    }

    @ParameterizedTest(name = "recover {0}, timeout={1}, credits={2}")
    @MethodSource("failureCases")
    void recoversFailedAuditUsingSavedTimeoutAndUsageWithoutCallingAgent(
            String code, AgentTimeoutPhase phase, BigDecimal credits, BigDecimal consumedUnits) {
        prepareRecovery();
        AgentRun audit = AgentRun.builder()
                .idempotencyKey(STEP_KEY)
                .agentTask(AgentTask.INVESTIGATE)
                .status(AgentRunStatus.FAILED)
                .failureCode(code)
                .failureMessage("조사 호출 실패")
                .timeoutPhase(phase)
                .inputTokens(10L)
                .outputTokens(5L)
                .costUsd(new BigDecimal("0.01"))
                .credits(credits)
                .build();
        when(agentRunRepository.findByIdempotencyKey(STEP_KEY)).thenReturn(Optional.of(audit));

        orchestrator.investigate(RUN_ID);

        verifySettlement(consumedUnits);
        verifyFailedStep();
        verifyNoInteractions(agentClient, guard, actionExecutor, recorder);
        verify(quotaRepository, never()).insert(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "live {0}, timeout={1}, credits={2}")
    @MethodSource("failureCases")
    void liveFailureUsesTheSameQuotaPolicy(
            String code, AgentTimeoutPhase phase, BigDecimal credits, BigDecimal consumedUnits) {
        reserveInvestigation(state(null, "IN_PROGRESS", null));
        when(contextService.current(RUN_ID, ISSUE_ID)).thenReturn(context());
        when(investigationRepository.markInFlight(1L, 1)).thenReturn(true);
        AgentClientException.Usage usage = new AgentClientException.Usage(
                10L, 5L, new BigDecimal("0.01"), credits);
        AgentClientException failure = new AgentClientException(
                code, "조사 호출 실패", null, usage,
                phase == null ? AgentClientException.TimeoutPhase.NONE
                        : AgentClientException.TimeoutPhase.valueOf(phase.name()));
        when(agentClient.explore(any(AgentExploreRequest.class))).thenThrow(failure);

        orchestrator.investigate(RUN_ID);

        verifySettlement(consumedUnits);
        verifyFailedStep();
        verify(agentClient).explore(any(AgentExploreRequest.class));
        verify(recorder).recordInvestigationFailure(
                eq(RUN_ID), eq(ISSUE_ID), any(), eq(code), eq("조사 호출 실패"),
                eq(usage), eq(phase), any(LocalDateTime.class));
        verifyNoInteractions(guard, actionExecutor);
    }

    @Test
    void consumesUnknownInFlightOutcomeWithoutRetryingProvider() {
        prepareRecovery();
        when(agentRunRepository.findByIdempotencyKey(STEP_KEY)).thenReturn(Optional.empty());

        orchestrator.investigate(RUN_ID);

        verifySettlement(RESERVED_UNITS);
        verifyFailedStep();
        verifyNoInteractions(agentClient, guard, actionExecutor, recorder);
    }

    private static Stream<Arguments> failureCases() {
        return Stream.of(
                Arguments.of("PROVIDER_UNAVAILABLE", AgentTimeoutPhase.READ, null, RESERVED_UNITS),
                Arguments.of("PROVIDER_UNAVAILABLE", AgentTimeoutPhase.CONNECT, null, null),
                Arguments.of("PROVIDER_UNAVAILABLE", null, null, null),
                Arguments.of("SCHEMA_VIOLATION", null, null, null),
                Arguments.of("BUDGET_EXCEEDED", null, new BigDecimal("0.750"), new BigDecimal("0.750")),
                Arguments.of("BUDGET_EXCEEDED", null, new BigDecimal("6"), null));
    }

    private void prepareRecovery() {
        reserveInvestigation(state(1, "IN_PROGRESS", null));
        when(investigationRepository.findByRunIdAndIssueId(RUN_ID, ISSUE_ID))
                .thenReturn(Optional.of(state(null, "COMPLETED", "FAILED")));
    }

    private void reserveInvestigation(IssueInvestigationState state) {
        when(investigationRepository.reserve(
                eq(RUN_ID), eq(ISSUE_ID), anyString(), anyString(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(state);
    }

    private void verifySettlement(BigDecimal consumedUnits) {
        if (consumedUnits == null) {
            verify(quotaRepository).release(eq(reservation), any(LocalDateTime.class));
            verify(quotaRepository, never()).consume(any(), any(), any());
        } else {
            verify(quotaRepository).consume(eq(reservation), eq(consumedUnits), any(LocalDateTime.class));
            verify(quotaRepository, never()).release(any(), any());
        }
    }

    private void verifyFailedStep() {
        verify(investigationRepository).completeStep(
                eq(1L), eq(1), eq(2), eq(0), anyString(), eq(null));
        verify(investigationRepository).finish(eq(1L), eq("FAILED"), any(LocalDateTime.class));
    }

    private IssueInvestigationState state(Integer inFlightStep, String status, String termination) {
        return new IssueInvestigationState(
                1L, RUN_ID, ISSUE_ID, KEY, status, "상충 보도 상태", 1, inFlightStep,
                2, 2, 0, null, null, termination);
    }

    private InvestigationContext context() {
        return new InvestigationContext(
                ISSUE_ID, 7L, "HBM 투자", "투자 검토", "DISPUTED",
                new BigDecimal("90"), new BigDecimal("70"),
                List.of("기업A"), List.of("정부"), 2, 5,
                List.of(101L), List.of(101L),
                List.of(new AgentExploreRequest.AllowedSource("NAVER", "네이버", "SEARCH")),
                Map.of("NAVER", 11L), false, "상충 보도 상태");
    }
}
