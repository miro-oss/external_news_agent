package com.example.be.domain.analysis.relevance;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceRequest;
import com.example.be.domain.analysis.agent.dto.AgentTopicRelevanceResponse;
import com.example.be.domain.analysis.agent.entity.*;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Judgment, audit and budget are committed together; provider I/O stays outside this transaction. */
@Service
@RequiredArgsConstructor
public class TopicRelevanceFinalizer {
    private final TopicRelevanceStore store;
    private final AgentRunJdbcRepository runs;
    private final AgentQuotaService quota;

    @Transactional
    public void success(Long runId, AgentTopicRelevanceRequest request, AgentTopicRelevanceResponse response,
                        List<TopicRelevanceStore.Assessment> assessments, String requestHash,
                        QuotaReservation reservation, LocalDateTime startedAt) {
        var meta = response.meta();
        store.saveAll(assessments);
        runs.insertIfAbsent(base(runId, request, requestHash, startedAt)
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion()).llmProvider(meta.provider()).llmModel(meta.model())
                .inputTokens(meta.inputTokens()).outputTokens(meta.outputTokens())
                .costUsd(rounded(meta.costUsd(), 6)).credits(rounded(meta.credits(), 3)).build());
        quota.completeSuccess(reservation, meta.credits());
    }

    @Transactional
    public void failure(Long runId, AgentTopicRelevanceRequest request,
                        List<TopicRelevanceStore.Assessment> assessments, String requestHash,
                        QuotaReservation reservation, LocalDateTime startedAt, AgentClientException failure) {
        store.saveAll(assessments);
        var usage = failure.getUsage();
        runs.insertIfAbsent(base(runId, request, requestHash, startedAt)
                .status(AgentRunStatus.FAILED).failureCode(failure.getCode() == null ? "INTERNAL_ERROR"
                        : failure.getCode().substring(0, Math.min(100, failure.getCode().length())))
                .failureMessage("주제 적합성 판정을 완료하지 못해 분석을 보류했습니다.")
                .timeoutPhase(failure.isReadTimeout() ? AgentTimeoutPhase.READ
                        : failure.isConnectTimeout() ? AgentTimeoutPhase.CONNECT : null)
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : rounded(usage.costUsd(), 6))
                .credits(usage == null ? null : rounded(usage.credits(), 3)).build());
        quota.completeFailure(reservation, failure);
    }

    private BigDecimal rounded(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.DOWN);
    }

    private AgentRun.AgentRunBuilder base(Long runId, AgentTopicRelevanceRequest request,
                                          String requestHash, LocalDateTime startedAt) {
        return AgentRun.builder().collectionRunId(runId).idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.TOPIC_RELEVANCE).targetType(AgentTargetType.TOPIC)
                .targetId(request.topic().id()).llmPlan(request.plan()).requestHash(requestHash)
                .startedAt(startedAt).finishedAt(LocalDateTime.now(ApiTimeZone.ZONE));
    }
}
