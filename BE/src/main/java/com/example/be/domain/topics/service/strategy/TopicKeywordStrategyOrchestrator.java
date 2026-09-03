package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyResponse;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaExceededException;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.topics.entity.TopicKeywordBucket;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordChangeAction;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TopicKeywordStrategyOrchestrator {

    private final AgentProperties properties;
    private final CollectionRunRepository runRepository;
    private final CollectionRunItemRepository runItemRepository;
    private final TopicRepository topicRepository;
    private final TopicKeywordProposalRepository proposalRepository;
    private final TopicKeywordStrategyInputAssembler inputAssembler;
    private final AgentClient agentClient;
    private final AgentQuotaService quotaService;
    private final AgentRunRecorder runRecorder;
    private final CollectionResultWriter resultWriter;

    public void strategize(Long runId) {
        if (!properties.isEnabled()) {
            return;
        }
        CollectionRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("키워드 제안을 만들 실행이 없습니다. runId=" + runId));
        if (run.getTriggerType() != TriggerType.SCHEDULED) {
            return;
        }
        Map<Long, TopicRunStats> statsByTopic = topicRunStats(runId);
        for (Long topicId : statsByTopic.keySet()) {
            if (proposalRepository.existsByTopic_IdAndStatus(topicId, TopicKeywordProposalStatus.PENDING)) {
                log.info("검토 대기 중인 키워드 제안이 있어 새 제안을 건너뜁니다. topicId={}", topicId);
                continue;
            }
            propose(run, topicId, statsByTopic.get(topicId));
        }
    }

    private void propose(CollectionRun run, Long topicId, TopicRunStats stats) {
        TopicKeywordStrategyInputAssembler.Snapshot snapshot = inputAssembler.assemble(run.getId(), topicId);
        AgentKeywordStrategyRequest request = new AgentKeywordStrategyRequest(
                idempotencyKey(run.getId(), topicId),
                run.getLlmPlan(),
                new AgentKeywordStrategyRequest.Target("TOPIC", topicId),
                snapshot.topic(),
                new AgentKeywordStrategyRequest.Run(
                        run.getId(),
                        run.getTriggerType().name(),
                        stats.scannedCount(),
                        stats.newCount(),
                        stats.updatedCount()),
                snapshot.currentKeywordStats(),
                snapshot.articles());
        QuotaReservation reservation;
        try {
            reservation = quotaService.reserve(run.getId(), request.idempotencyKey(),
                    AgentTask.KEYWORD_STRATEGY, run.getLlmPlan());
        } catch (QuotaExceededException exception) {
            addWarning(run.getId(), topicId,
                    "키워드 제안 예산이 부족해 이번 주기 제안을 건너뛰었습니다.");
            return;
        } catch (IllegalStateException exception) {
            addWarning(run.getId(), topicId,
                    "동일한 키워드 제안 생성이 이미 진행 중입니다.");
            return;
        }

        LocalDateTime startedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        AgentKeywordStrategyResponse response = null;
        try {
            response = agentClient.keywordStrategy(request);
            validate(response);
            saveProposal(run.getId(), topicId, request.idempotencyKey(), response);
            runRecorder.recordKeywordStrategySuccess(run.getId(), topicId, request, response, startedAt);
            quotaService.completeSuccess(reservation, response.meta().credits());
        } catch (RuntimeException exception) {
            recordFailure(run.getId(), topicId, request, startedAt, exception);
            completeFailure(reservation, exception);
            addWarning(run.getId(), topicId,
                    "수집 전략가 키워드 제안 생성에 실패해 기존 키워드를 유지했습니다.");
        }
    }

    private void saveProposal(Long runId,
                              Long topicId,
                              String idempotencyKey,
                              AgentKeywordStrategyResponse response) {
        if (response.proposals().isEmpty()) {
            return;
        }
        proposalRepository.save(TopicKeywordProposal.builder()
                .topic(topicRepository.findById(topicId).orElseThrow())
                .collectionRunId(runId)
                .idempotencyKey(idempotencyKey)
                .summary(response.summary())
                .changes(response.proposals().stream()
                        .map(this::toChange)
                        .toList())
                .status(TopicKeywordProposalStatus.PENDING)
                .createdAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
    }

    private TopicKeywordChange toChange(AgentKeywordStrategyResponse.Proposal proposal) {
        return new TopicKeywordChange(
                TopicKeywordBucket.valueOf(proposal.bucket().trim().toUpperCase(Locale.ROOT)),
                TopicKeywordChangeAction.valueOf(proposal.action().trim().toUpperCase(Locale.ROOT)),
                proposal.keyword(),
                proposal.reason());
    }

    private void validate(AgentKeywordStrategyResponse response) {
        if (response == null || response.meta() == null
                || !StringUtils.hasText(response.summary())
                || response.meta().truncated()) {
            throw new IllegalStateException("키워드 제안 응답 meta 또는 summary가 올바르지 않습니다.");
        }
        Set<String> seen = new HashSet<>();
        for (AgentKeywordStrategyResponse.Proposal proposal : response.proposals()) {
            if (proposal == null
                    || !StringUtils.hasText(proposal.bucket())
                    || !StringUtils.hasText(proposal.action())
                    || !StringUtils.hasText(proposal.keyword())
                    || !StringUtils.hasText(proposal.reason())) {
                throw new IllegalStateException("키워드 제안 항목이 비어 있습니다.");
            }
            String key = proposal.bucket().trim().toUpperCase(Locale.ROOT)
                    + "|"
                    + proposal.keyword().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                throw new IllegalStateException("같은 버킷의 키워드가 중복 제안되었습니다.");
            }
        }
    }

    private void completeFailure(QuotaReservation reservation, RuntimeException exception) {
        if (exception instanceof AgentClientException clientException) {
            quotaService.completeFailure(reservation, clientException);
            return;
        }
        quotaService.completeFailure(reservation, "SCHEMA_VIOLATION");
    }

    private void recordFailure(Long runId,
                               Long topicId,
                               AgentKeywordStrategyRequest request,
                               LocalDateTime startedAt,
                               RuntimeException exception) {
        AgentClientException clientException = exception instanceof AgentClientException value
                ? value
                : null;
        runRecorder.recordKeywordStrategyFailure(
                runId,
                topicId,
                request,
                clientException == null ? "SCHEMA_VIOLATION" : clientException.getCode(),
                exception.getMessage(),
                clientException == null ? null : clientException.getUsage(),
                timeoutPhase(clientException),
                startedAt);
    }

    private void addWarning(Long runId, Long topicId, String message) {
        resultWriter.addAgentWarning(
                runId,
                CollectionRunWarning.CODE_LLM_KEYWORD_STRATEGY_FAILED,
                message + " topicId=" + topicId);
    }

    private String idempotencyKey(Long runId, Long topicId) {
        return "run:%d:topic:%d:keyword-strategy".formatted(runId, topicId);
    }

    private Map<Long, TopicRunStats> topicRunStats(Long runId) {
        Map<Long, TopicRunStats> stats = new LinkedHashMap<>();
        for (CollectionRunItem item : runItemRepository.findExecutionItemsByRunId(runId)) {
            stats.merge(
                    item.getTopic().getId(),
                    new TopicRunStats(item.getScannedCount(), item.getNewCount(), item.getUpdatedCount()),
                    TopicRunStats::plus);
        }
        return stats;
    }

    private AgentTimeoutPhase timeoutPhase(AgentClientException exception) {
        if (exception == null) {
            return null;
        }
        return switch (exception.getTimeoutPhase()) {
            case CONNECT -> AgentTimeoutPhase.CONNECT;
            case READ -> AgentTimeoutPhase.READ;
            case NONE -> null;
        };
    }

    private record TopicRunStats(int scannedCount, int newCount, int updatedCount) {

        private TopicRunStats plus(TopicRunStats other) {
            return new TopicRunStats(
                    scannedCount + other.scannedCount,
                    newCount + other.newCount,
                    updatedCount + other.updatedCount);
        }
    }
}
