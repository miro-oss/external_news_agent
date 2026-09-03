package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentExploreRequest;
import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.analysis.agent.repository.AgentRunRepository;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** P-A-O 조사 루프. Agent는 제안만 하고 승인·실행·종료 상태는 Spring이 소유한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueInvestigationOrchestrator {

    static final int MAX_STEPS = 3;

    private final AgentProperties properties;
    private final CollectionRunRepository runRepository;
    private final IssueInvestigationContextService contextService;
    private final IssueInvestigationJdbcRepository investigationRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunJdbcRepository agentRunJdbcRepository;
    private final AgentClient agentClient;
    private final IssueInvestigationGuard guard;
    private final IssueInvestigationActionExecutor actionExecutor;
    private final AgentQuotaService quotaService;
    private final AgentRunRecorder recorder;
    private final CollectionResultWriter resultWriter;

    public void investigate(Long runId) {
        if (!properties.isEnabled()) {
            return;
        }
        AgentPlan plan = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("조사할 실행이 없습니다. runId=" + runId))
                .getLlmPlan();
        LocalDate today = LocalDate.now(ApiTimeZone.ZONE);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        for (InvestigationContext candidate : contextService.candidates(runId)) {
            if (investigationRepository.investigatedToday(
                    candidate.issueId(), dayStart, dayEnd, runId)) {
                continue;
            }
            try {
                investigateIssue(runId, plan, candidate);
            } catch (RuntimeException exception) {
                addFailureWarning(runId, candidate.issueId(), exception.getMessage());
                log.warn("이슈 추가 조사에 실패해 기존 분석으로 보고서를 만든다. runId={} issueId={} error={}",
                        runId, candidate.issueId(), exception.getMessage(), exception);
            }
        }
    }

    private void investigateIssue(Long runId,
                                  AgentPlan plan,
                                  InvestigationContext candidate) {
        IssueInvestigationState state = investigationRepository.reserve(
                runId,
                candidate.issueId(),
                "run:%d:issue:%d:investigation".formatted(runId, candidate.issueId()),
                candidate.triggerReason(),
                candidate.evidenceSentenceCount(),
                now());
        if (state.finished()) {
            return;
        }
        if (state.inFlightStep() != null) {
            state = recoverInFlight(state);
            if (state.finished()) {
                return;
            }
        }

        while (state.nextStep() <= MAX_STEPS) {
            InvestigationContext current = contextService.current(runId, state.issueId());
            AgentExploreRequest request = request(state, current, plan);
            QuotaReservation reservation;
            boolean recoveredSharedReservation = false;
            try {
                reservation = quotaService.reserve(
                        runId, request.idempotencyKey(), AgentTask.INVESTIGATE, plan);
            } catch (QuotaExceededException exception) {
                finishBudgetLimited(state, request, exception.getMessage());
                return;
            } catch (IllegalStateException exception) {
                // quota 예약 뒤 in-flight 표시 전에 프로세스가 멈춘 경우에는 provider를 아직 호출하지 않았다.
                // 남은 RESERVED 행을 그대로 이어 써야 같은 idempotency key를 새로 만들지 않는다.
                reservation = quotaService.findActiveReservation(request.idempotencyKey())
                        .orElseThrow(() -> exception);
                recoveredSharedReservation = true;
            }
            int attemptedStep = state.nextStep();
            if (!investigationRepository.markInFlight(state.id(), attemptedStep)) {
                if (!recoveredSharedReservation) {
                    completeFailure(reservation, "PROVIDER_UNAVAILABLE");
                }
                state = investigationRepository.findByRunIdAndIssueId(runId, state.issueId())
                        .orElseThrow();
                if (state.finished()) {
                    return;
                }
                if (state.inFlightStep() != null || state.nextStep() == attemptedStep) {
                    log.debug("다른 실행자가 조사 step을 처리 중이어서 현재 루프를 종료한다. id={} step={}",
                            state.id(), attemptedStep);
                    return;
                }
                continue;
            }

            LocalDateTime startedAt = now();
            AgentExploreResponse response = null;
            try {
                response = agentClient.explore(request);
                validate(response);
                IssueInvestigationGuard.Decision decision = guard.evaluate(
                        runId, current, response.proposal());
                if (!decision.accepted()) {
                    finishRejected(state, request, response, reservation, decision, startedAt);
                    return;
                }

                InvestigationActionResult result = actionExecutor.execute(
                        runId, current, response.proposal());
                int evidenceAfter = state.evidenceCountCurrent() + result.addedEvidenceCount();
                String termination = termination(response.proposal(), state.nextStep(), result);
                recorder.recordInvestigationSuccess(
                        runId, state.issueId(), request, response, startedAt,
                        decision.queryHash(), null, result.addedArticleCount(),
                        state.evidenceCountCurrent(), evidenceAfter, termination);
                completeSuccess(reservation, response.meta().credits());
                investigationRepository.completeStep(
                        state.id(), state.nextStep(), evidenceAfter,
                        result.addedArticleCount(), response.proposal().reason(), null);
                if (termination != null) {
                    investigationRepository.finish(state.id(), termination, now());
                    return;
                }
                state = investigationRepository.findByRunIdAndIssueId(runId, state.issueId())
                        .orElseThrow();
            } catch (RuntimeException exception) {
                failStep(state, request, response, reservation, startedAt, exception);
                return;
            }
        }
    }

    private IssueInvestigationState recoverInFlight(IssueInvestigationState state) {
        int step = state.inFlightStep();
        String stepKey = stepKey(state.idempotencyKey(), step);
        AgentRun audit = agentRunRepository.findByIdempotencyKey(stepKey).orElse(null);
        if (audit == null) {
            quotaService.findActiveReservation(stepKey)
                    .ifPresent(reservation -> completeFailure(reservation, "READ_TIMEOUT"));
            completeFailedStepSafely(
                    state, step, "중단된 조사 step의 결과를 확인할 수 없습니다.");
            investigationRepository.finish(state.id(), "FAILED", now());
            addFailureWarning(state.runId(), state.issueId(),
                    "중단된 조사 step의 결과를 확인할 수 없어 재호출하지 않았습니다.");
            return investigationRepository.findByRunIdAndIssueId(state.runId(), state.issueId())
                    .orElseThrow();
        }

        quotaService.findActiveReservation(stepKey).ifPresent(reservation -> {
            if (audit.getStatus() == AgentRunStatus.FAILED) {
                completeFailure(reservation, audit.getFailureCode());
            } else {
                completeSuccess(reservation, audit.getCredits());
            }
        });
        if (audit.getStatus() == AgentRunStatus.FAILED) {
            completeFailedStepSafely(
                    state,
                    step,
                    StringUtils.hasText(audit.getActionReason())
                            ? audit.getActionReason() : audit.getFailureMessage());
            investigationRepository.finish(state.id(), "FAILED", now());
        } else {
            int evidenceAfter = audit.getEvidenceAfter() == null
                    ? state.evidenceCountCurrent() : audit.getEvidenceAfter();
            investigationRepository.completeStep(
                    state.id(), step, evidenceAfter, audit.getAddedArticleCount(),
                    audit.getActionReason(), audit.getRejectionReason());
            if (audit.getTerminationReason() != null) {
                investigationRepository.finish(state.id(), audit.getTerminationReason(), now());
            }
        }
        return investigationRepository.findByRunIdAndIssueId(state.runId(), state.issueId())
                .orElseThrow();
    }

    private AgentExploreRequest request(IssueInvestigationState state,
                                        InvestigationContext context,
                                        AgentPlan plan) {
        List<AgentExploreRequest.PreviousStep> previous = agentRunJdbcRepository
                .findInvestigationSteps(state.runId(), state.issueId()).stream()
                .map(value -> new AgentExploreRequest.PreviousStep(
                        value.step(), value.action(), value.accepted(),
                        value.summary(), value.evidenceCount()))
                .toList();
        return new AgentExploreRequest(
                stepKey(state.idempotencyKey(), state.nextStep()),
                plan,
                new AgentExploreRequest.Target("ISSUE", state.issueId()),
                state.nextStep(),
                new AgentExploreRequest.Issue(
                        context.title(), context.summary(), context.status(),
                        context.importanceScore(), context.sensitivityScore(),
                        context.entities(), context.missingStakeholders(),
                        state.evidenceCountCurrent(), context.metadataOnlyArticleIds()),
                context.allowedSources(),
                previous);
    }

    private void finishBudgetLimited(IssueInvestigationState state,
                                     AgentExploreRequest request,
                                     String reason) {
        if (!investigationRepository.markInFlight(state.id(), state.nextStep())) {
            return;
        }
        recorder.recordInvestigationSkipped(
                state.runId(), state.issueId(), request, reason,
                "BUDGET_LIMIT", state.evidenceCountCurrent(), now());
        investigationRepository.completeStep(
                state.id(), state.nextStep(), state.evidenceCountCurrent(),
                0, reason, reason);
        investigationRepository.finish(state.id(), "BUDGET_LIMIT", now());
    }

    private void finishRejected(IssueInvestigationState state,
                                AgentExploreRequest request,
                                AgentExploreResponse response,
                                QuotaReservation reservation,
                                IssueInvestigationGuard.Decision decision,
                                LocalDateTime startedAt) {
        recorder.recordInvestigationSuccess(
                state.runId(), state.issueId(), request, response, startedAt,
                decision.queryHash(), decision.rejectionReason(), 0,
                state.evidenceCountCurrent(), state.evidenceCountCurrent(), "REJECTED");
        completeSuccess(reservation, response.meta().credits());
        investigationRepository.completeStep(
                state.id(), state.nextStep(), state.evidenceCountCurrent(), 0,
                response.proposal().reason(), decision.rejectionReason());
        investigationRepository.finish(state.id(), "REJECTED", now());
    }

    private void failStep(IssueInvestigationState state,
                          AgentExploreRequest request,
                          AgentExploreResponse response,
                          QuotaReservation reservation,
                          LocalDateTime startedAt,
                          RuntimeException exception) {
        AgentClientException clientException = exception instanceof AgentClientException value
                ? value : null;
        String code = clientException != null ? clientException.getCode()
                : response == null ? "SCHEMA_VIOLATION" : "ACTION_FAILED";
        AgentClientException.Usage usage = clientException == null
                ? usage(response) : clientException.getUsage();
        try {
            recorder.recordInvestigationFailure(
                    state.runId(), state.issueId(), request, code,
                    exception.getMessage(), usage, timeoutPhase(clientException), startedAt);
        } catch (RuntimeException recordException) {
            log.error("조사 실패 감사 기록을 저장하지 못했다. key={}",
                    request.idempotencyKey(), recordException);
        }
        if (clientException != null) {
            try {
                quotaService.completeFailure(reservation, clientException);
            } catch (RuntimeException settleException) {
                log.error("조사 실패 quota 정산에 실패했다. key={}",
                        request.idempotencyKey(), settleException);
            }
        } else {
            completeFailure(reservation, code);
        }
        String failureReason = response != null && response.proposal() != null
                && StringUtils.hasText(response.proposal().reason())
                ? response.proposal().reason() : exception.getMessage();
        completeFailedStepSafely(state, state.nextStep(), failureReason);
        investigationRepository.finish(state.id(), "FAILED", now());
        addFailureWarning(state.runId(), state.issueId(), exception.getMessage());
    }

    private String termination(AgentExploreResponse.Proposal proposal,
                               int step,
                               InvestigationActionResult result) {
        if ("CONCLUDE".equals(proposal.action())) {
            return "CONCLUDED";
        }
        if (result.addedEvidenceCount() == 0
                && ("SEARCH_MORE".equals(proposal.action())
                || "READ_FULLTEXT".equals(proposal.action()))) {
            return "NO_NEW_EVIDENCE";
        }
        return step >= MAX_STEPS ? "MAX_STEPS" : null;
    }

    private void validate(AgentExploreResponse response) {
        if (response == null || response.proposal() == null || response.meta() == null
                || !StringUtils.hasText(response.proposal().action())
                || !StringUtils.hasText(response.proposal().reason())
                || !StringUtils.hasText(response.meta().provider())
                || !StringUtils.hasText(response.meta().model())
                || !StringUtils.hasText(response.meta().promptVersion())
                || tooLong(response.proposal().action(), 30)
                || tooLong(response.proposal().reason(), 1000)
                || tooLong(response.proposal().sourceKey(), 100)
                || tooLong(response.proposal().query(), 500)
                || response.proposal().entities().size() > 10
                || response.proposal().entities().stream()
                .anyMatch(value -> !StringUtils.hasText(value) || tooLong(value, 200))
                || tooLong(response.meta().provider(), 30)
                || tooLong(response.meta().model(), 100)
                || tooLong(response.meta().promptVersion(), 50)
                || negative(response.meta().inputTokens())
                || negative(response.meta().outputTokens())
                || negative(response.meta().costUsd())
                || negative(response.meta().credits())) {
            throw new AgentClientException(
                    "SCHEMA_VIOLATION", "Agent 조사 응답의 필수 필드가 올바르지 않습니다.");
        }
    }

    private boolean negative(Long value) {
        return value != null && value < 0;
    }

    private boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private boolean tooLong(String value, int maxLength) {
        return value != null && value.codePointCount(0, value.length()) > maxLength;
    }

    private AgentClientException.Usage usage(AgentExploreResponse response) {
        if (response == null || response.meta() == null) {
            return null;
        }
        return new AgentClientException.Usage(
                response.meta().inputTokens(), response.meta().outputTokens(),
                response.meta().costUsd(), response.meta().credits());
    }

    private AgentTimeoutPhase timeoutPhase(AgentClientException exception) {
        if (exception == null || exception.getTimeoutPhase() == AgentClientException.TimeoutPhase.NONE) {
            return null;
        }
        return exception.isConnectTimeout() ? AgentTimeoutPhase.CONNECT : AgentTimeoutPhase.READ;
    }

    private void completeSuccess(QuotaReservation reservation, BigDecimal credits) {
        try {
            quotaService.completeSuccess(reservation, credits);
        } catch (RuntimeException exception) {
            log.error("조사 성공 quota 정산에 실패했다. key={}", reservation.idempotencyKey(), exception);
        }
    }

    private void completeFailure(QuotaReservation reservation, String failureCode) {
        try {
            quotaService.completeFailure(reservation, failureCode);
        } catch (RuntimeException exception) {
            log.error("조사 실패 quota 정산에 실패했다. key={}", reservation.idempotencyKey(), exception);
        }
    }

    private void completeFailedStepSafely(IssueInvestigationState state,
                                          int step,
                                          String reason) {
        try {
            investigationRepository.completeStep(
                    state.id(), step, state.evidenceCountCurrent(), 0, reason, null);
        } catch (RuntimeException exception) {
            log.debug("이미 종료되었거나 반영된 조사 step이다. id={} step={}",
                    state.id(), step);
        }
    }

    private void addFailureWarning(Long runId, Long issueId, String message) {
        resultWriter.addAgentWarning(
                runId,
                CollectionRunWarning.CODE_LLM_INVESTIGATION_FAILED,
                "추가 조사 실패로 기존 분석을 유지했습니다. issueId=" + issueId
                        + " reason=" + (message == null ? "unknown" : message));
    }

    private String stepKey(String baseKey, int step) {
        return baseKey + ":step:" + step;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ApiTimeZone.ZONE);
    }
}
