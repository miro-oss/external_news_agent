package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentExploreRequest;
import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueInvestigationOrchestratorTest {

    private static final Long RUN_ID = 42L;
    private static final Long ISSUE_ID = 88L;

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
    private AgentQuotaService quotaService;
    @Mock
    private AgentRunRecorder recorder;
    @Mock
    private CollectionResultWriter resultWriter;

    private AgentProperties properties;
    private IssueInvestigationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setToken("agent-secret");
        orchestrator = new IssueInvestigationOrchestrator(
                properties, runRepository, contextService, investigationRepository,
                agentRunRepository, agentRunJdbcRepository, agentClient, guard,
                actionExecutor, quotaService, recorder, resultWriter);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(
                CollectionRun.builder().id(RUN_ID).llmPlan(AgentPlan.FREE).build()));
        when(contextService.candidates(RUN_ID)).thenReturn(List.of(context()));
    }

    @Test
    void stopsImmediatelyWhenActionAddsNoEvidence() {
        IssueInvestigationState state = state(1, null, "IN_PROGRESS", null);
        prepareFirstStep(state, searchResponse());
        when(actionExecutor.execute(eq(RUN_ID), any(), any()))
                .thenReturn(new InvestigationActionResult(1, 0, "새 근거 없음"));

        orchestrator.investigate(RUN_ID);

        verify(agentClient).explore(any(AgentExploreRequest.class));
        verify(investigationRepository).finish(
                eq(state.id()), eq("NO_NEW_EVIDENCE"), any(LocalDateTime.class));
    }

    @Test
    void stopsAtThirdStepEvenWhenEveryActionAddsEvidence() {
        IssueInvestigationState first = state(1, null, "IN_PROGRESS", null);
        IssueInvestigationState second = state(2, null, "IN_PROGRESS", null);
        IssueInvestigationState third = state(3, null, "IN_PROGRESS", null);
        prepareFirstStep(first, searchResponse());
        when(actionExecutor.execute(eq(RUN_ID), any(), any()))
                .thenReturn(new InvestigationActionResult(1, 1, "근거 추가"));
        when(investigationRepository.findByRunIdAndIssueId(RUN_ID, ISSUE_ID))
                .thenReturn(Optional.of(second), Optional.of(third));

        orchestrator.investigate(RUN_ID);

        verify(agentClient, times(3)).explore(any(AgentExploreRequest.class));
        verify(investigationRepository).finish(
                eq(first.id()), eq("MAX_STEPS"), any(LocalDateTime.class));
    }

    @Test
    void concludesWhenAgentProposesConclude() {
        IssueInvestigationState state = state(1, null, "IN_PROGRESS", null);
        prepareFirstStep(state, concludeResponse());
        when(actionExecutor.execute(eq(RUN_ID), any(), any()))
                .thenReturn(InvestigationActionResult.conclude("조사 완료"));

        orchestrator.investigate(RUN_ID);

        verify(investigationRepository).finish(
                eq(state.id()), eq("CONCLUDED"), any(LocalDateTime.class));
    }

    @Test
    void recordsBudgetTerminationWithoutCallingAgent() {
        IssueInvestigationState state = state(1, null, "IN_PROGRESS", null);
        when(investigationRepository.reserve(
                eq(RUN_ID), eq(ISSUE_ID), anyString(), anyString(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(state);
        when(contextService.current(RUN_ID, ISSUE_ID)).thenReturn(context());
        when(quotaService.reserve(eq(RUN_ID), anyString(), eq(AgentTask.INVESTIGATE), eq(AgentPlan.FREE)))
                .thenThrow(new QuotaExceededException(AgentPlan.FREE, "15% 상한"));
        when(investigationRepository.markInFlight(state.id(), 1)).thenReturn(true);

        orchestrator.investigate(RUN_ID);

        verify(agentClient, never()).explore(any());
        verify(recorder).recordInvestigationSkipped(
                eq(RUN_ID), eq(ISSUE_ID), any(), eq("15% 상한"),
                eq("BUDGET_LIMIT"), eq(2), any(LocalDateTime.class));
        verify(investigationRepository).finish(
                eq(state.id()), eq("BUDGET_LIMIT"), any(LocalDateTime.class));
    }

    @Test
    void resumesAuditedInFlightStepWithoutCallingAgentAgain() {
        IssueInvestigationState inFlight = state(1, 1, "IN_PROGRESS", null);
        IssueInvestigationState finished = state(2, null, "COMPLETED", "NO_NEW_EVIDENCE");
        when(investigationRepository.reserve(
                eq(RUN_ID), eq(ISSUE_ID), anyString(), anyString(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(inFlight);
        AgentRun audit = AgentRun.builder()
                .idempotencyKey(inFlight.idempotencyKey() + ":step:1")
                .agentTask(AgentTask.INVESTIGATE)
                .targetType(AgentTargetType.ISSUE)
                .targetId(ISSUE_ID)
                .status(AgentRunStatus.SUCCESS)
                .investigationStep(1)
                .investigationAction("SEARCH_MORE")
                .actionReason("추가 검색")
                .addedArticleCount(0)
                .evidenceBefore(2)
                .evidenceAfter(2)
                .terminationReason("NO_NEW_EVIDENCE")
                .startedAt(LocalDateTime.now())
                .build();
        when(agentRunRepository.findByIdempotencyKey(inFlight.idempotencyKey() + ":step:1"))
                .thenReturn(Optional.of(audit));
        when(quotaService.findActiveReservation(anyString())).thenReturn(Optional.empty());
        when(investigationRepository.findByRunIdAndIssueId(RUN_ID, ISSUE_ID))
                .thenReturn(Optional.of(finished));

        orchestrator.investigate(RUN_ID);

        verify(agentClient, never()).explore(any());
        verify(investigationRepository).completeStep(
                inFlight.id(), 1, 2, 0, "추가 검색", null);
        verify(investigationRepository).finish(
                eq(inFlight.id()), eq("NO_NEW_EVIDENCE"), any(LocalDateTime.class));
    }

    @Test
    void yieldsWhenAnotherWorkerOwnsTheSameStep() {
        IssueInvestigationState state = state(1, null, "IN_PROGRESS", null);
        IssueInvestigationState owned = state(1, 1, "IN_PROGRESS", null);
        when(investigationRepository.reserve(
                eq(RUN_ID), eq(ISSUE_ID), anyString(), anyString(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(state);
        when(contextService.current(RUN_ID, ISSUE_ID)).thenReturn(context());
        QuotaReservation reservation = reservation(1);
        when(quotaService.reserve(eq(RUN_ID), anyString(), eq(AgentTask.INVESTIGATE), eq(AgentPlan.FREE)))
                .thenReturn(reservation);
        when(investigationRepository.markInFlight(state.id(), 1)).thenReturn(false);
        when(investigationRepository.findByRunIdAndIssueId(RUN_ID, ISSUE_ID))
                .thenReturn(Optional.of(owned));

        orchestrator.investigate(RUN_ID);

        verify(agentClient, never()).explore(any());
        verify(quotaService).completeFailure(reservation, "PROVIDER_UNAVAILABLE");
        verify(investigationRepository, times(1)).markInFlight(state.id(), 1);
    }

    @Test
    void compareHistoryCanContinueWithoutInflatingEvidence() {
        IssueInvestigationState first = state(1, null, "IN_PROGRESS", null);
        IssueInvestigationState second = state(2, null, "IN_PROGRESS", null);
        when(investigationRepository.reserve(
                eq(RUN_ID), eq(ISSUE_ID), anyString(), anyString(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(first);
        when(contextService.current(RUN_ID, ISSUE_ID)).thenReturn(context());
        when(quotaService.reserve(eq(RUN_ID), anyString(), eq(AgentTask.INVESTIGATE), eq(AgentPlan.FREE)))
                .thenReturn(reservation(1), reservation(2));
        when(investigationRepository.markInFlight(first.id(), 1)).thenReturn(true);
        when(investigationRepository.markInFlight(second.id(), 2)).thenReturn(true);
        when(agentClient.explore(any(AgentExploreRequest.class)))
                .thenReturn(compareHistoryResponse(), concludeResponse());
        when(guard.evaluate(eq(RUN_ID), any(), any()))
                .thenReturn(new IssueInvestigationGuard.Decision(true, null, null));
        when(actionExecutor.execute(eq(RUN_ID), any(), any()))
                .thenReturn(new InvestigationActionResult(0, 0, "과거 이슈 비교"),
                        InvestigationActionResult.conclude("조사 완료"));
        when(investigationRepository.findByRunIdAndIssueId(RUN_ID, ISSUE_ID))
                .thenReturn(Optional.of(second));

        orchestrator.investigate(RUN_ID);

        verify(agentClient, times(2)).explore(any(AgentExploreRequest.class));
        verify(investigationRepository).finish(
                eq(first.id()), eq("CONCLUDED"), any(LocalDateTime.class));
    }

    @Test
    void nullProposalStillCompletesFailedStep() {
        IssueInvestigationState state = state(1, null, "IN_PROGRESS", null);
        prepareAgentCall(state, response(null));

        orchestrator.investigate(RUN_ID);

        verify(investigationRepository).completeStep(
                eq(state.id()), eq(1), eq(2), eq(0), anyString(), eq(null));
        verify(investigationRepository).finish(
                eq(state.id()), eq("FAILED"), any(LocalDateTime.class));
        verify(actionExecutor, never()).execute(any(), any(), any());
    }

    private void prepareFirstStep(IssueInvestigationState state, AgentExploreResponse response) {
        prepareAgentCall(state, response);
        when(guard.evaluate(eq(RUN_ID), any(), any()))
                .thenReturn(new IssueInvestigationGuard.Decision(true, null, "query-hash"));
    }

    private void prepareAgentCall(IssueInvestigationState state, AgentExploreResponse response) {
        when(investigationRepository.reserve(
                eq(RUN_ID), eq(ISSUE_ID), anyString(), anyString(), anyInt(), any(LocalDateTime.class)))
                .thenReturn(state);
        when(contextService.current(RUN_ID, ISSUE_ID)).thenReturn(context());
        when(quotaService.reserve(eq(RUN_ID), anyString(), eq(AgentTask.INVESTIGATE), eq(AgentPlan.FREE)))
                .thenReturn(reservation(1));
        when(investigationRepository.markInFlight(eq(state.id()), anyInt())).thenReturn(true);
        when(agentClient.explore(any(AgentExploreRequest.class))).thenReturn(response);
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

    private IssueInvestigationState state(int nextStep,
                                          Integer inFlightStep,
                                          String status,
                                          String termination) {
        return new IssueInvestigationState(
                1L, RUN_ID, ISSUE_ID, "run:42:issue:88:investigation",
                status, "상충 보도 상태", nextStep, inFlightStep,
                2, 2 + Math.max(0, nextStep - 1), nextStep - 1,
                null, null, termination);
    }

    private QuotaReservation reservation(long id) {
        return new QuotaReservation(
                id, RUN_ID, "reservation:" + id,
                AgentTask.INVESTIGATE, AgentPlan.FREE, BigDecimal.ONE);
    }

    private AgentExploreResponse searchResponse() {
        return response(new AgentExploreResponse.Proposal(
                "SEARCH_MORE", "NAVER", "HBM 투자", null, List.of(), null, "추가 검색"));
    }

    private AgentExploreResponse concludeResponse() {
        return response(new AgentExploreResponse.Proposal(
                "CONCLUDE", null, null, null, List.of(), null, "조사 완료"));
    }

    private AgentExploreResponse compareHistoryResponse() {
        return response(new AgentExploreResponse.Proposal(
                "COMPARE_HISTORY", null, null, null, List.of("기업A"), 30, "과거 비교"));
    }

    private AgentExploreResponse response(AgentExploreResponse.Proposal proposal) {
        return new AgentExploreResponse(
                proposal,
                new AgentExploreResponse.Meta(
                        "mock", "mock", "explore.ko.v1", 0L, 0L,
                        BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }
}
