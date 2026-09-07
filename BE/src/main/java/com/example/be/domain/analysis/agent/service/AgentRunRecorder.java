package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceRequest;
import com.example.be.domain.analysis.agent.dto.AgentEvidenceResponse;
import com.example.be.domain.analysis.agent.dto.AgentExploreRequest;
import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyResponse;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.dto.AgentSelfCritiqueResponse;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentRunRecorder {

    private static final int MAX_FAILURE_MESSAGE_LENGTH = 1000;

    private final AgentRunJdbcRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordSuccess(Long runId,
                              Long articleId,
                              AgentAnalyzeRequest request,
                              AgentAnalyzeResponse response,
                              LocalDateTime startedAt) {
        AgentAnalyzeResponse.Meta meta = response.meta();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.ANALYZE)
                .targetType(AgentTargetType.ARTICLE)
                .targetId(articleId)
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion())
                .llmProvider(meta.provider())
                .llmModel(meta.model())
                .llmPlan(request.plan())
                .inputTokens(meta.inputTokens())
                .outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd())
                .credits(meta.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordFailure(Long runId,
                              Long articleId,
                              AgentAnalyzeRequest request,
                              String failureCode,
                              String failureMessage,
                              AgentClientException.Usage usage,
                              AgentTimeoutPhase timeoutPhase,
                              LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.ANALYZE)
                .targetType(AgentTargetType.ARTICLE)
                .targetId(articleId)
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .timeoutPhase(timeoutPhase)
                .llmPlan(request.plan())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd())
                .credits(usage == null ? null : usage.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordEvidenceSuccess(Long runId,
                                      Long articleId,
                                      AgentEvidenceRequest request,
                                      AgentEvidenceResponse response,
                                      LocalDateTime startedAt) {
        AgentEvidenceResponse.Meta meta = response.meta();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.VERIFY_EVIDENCE)
                .targetType(AgentTargetType.ARTICLE)
                .targetId(articleId)
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion())
                .llmProvider(meta.provider())
                .llmModel(meta.model())
                .llmPlan(request.plan())
                .inputTokens(meta.inputTokens())
                .outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd())
                .credits(meta.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordEvidenceFailure(Long runId,
                                      Long articleId,
                                      AgentEvidenceRequest request,
                                      String failureCode,
                                      String failureMessage,
                                      AgentClientException.Usage usage,
                                      AgentTimeoutPhase timeoutPhase,
                                      LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.VERIFY_EVIDENCE)
                .targetType(AgentTargetType.ARTICLE)
                .targetId(articleId)
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .timeoutPhase(timeoutPhase)
                .llmPlan(request.plan())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd())
                .credits(usage == null ? null : usage.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordSelfCritiqueSuccess(Long runId,
                                          Long issueId,
                                          AgentAnalyzeRequest request,
                                          AgentSelfCritiqueResponse response,
                                          LocalDateTime startedAt) {
        AgentAnalyzeResponse.Meta meta = response.meta();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.SELF_CRITIQUE)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion())
                .llmProvider(meta.provider())
                .llmModel(meta.model())
                .llmPlan(request.plan())
                .inputTokens(meta.inputTokens())
                .outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd())
                .credits(meta.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordSelfCritiqueFailure(Long runId,
                                          Long issueId,
                                          AgentAnalyzeRequest request,
                                          String failureCode,
                                          String failureMessage,
                                          AgentClientException.Usage usage,
                                          AgentTimeoutPhase timeoutPhase,
                                          LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.SELF_CRITIQUE)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .timeoutPhase(timeoutPhase)
                .llmPlan(request.plan())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd())
                .credits(usage == null ? null : usage.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordInsightSuccess(Long runId,
                                     Long issueId,
                                     AgentInsightRequest request,
                                     AgentInsightResponse response,
                                     InsightAuditContext context,
                                     LocalDateTime startedAt) {
        AgentInsightResponse.Meta meta = response.meta();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.INSIGHT)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion())
                .llmProvider(meta.provider())
                .llmModel(meta.model())
                .llmPlan(request.plan())
                .inputTokens(meta.inputTokens())
                .outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd())
                .credits(meta.credits())
                .requestHash(hash(request))
                .actionPayload(objectMapper.writeValueAsString(context.withMetadataSource("RESPONSE")
                        .withUsageCompleteness(usageCompleteness(
                                meta.inputTokens(), meta.outputTokens(), meta.costUsd(), meta.credits()))))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordInsightFailure(Long runId,
                                     Long issueId,
                                     AgentInsightRequest request,
                                     String failureCode,
                                     String failureMessage,
                                     AgentClientException.Usage usage,
                                     AgentTimeoutPhase timeoutPhase,
                                     InsightAuditContext context,
                                     AgentClientException.ExecutionMetadata metadata,
                                     LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.INSIGHT)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .timeoutPhase(timeoutPhase)
                .promptVersion(metadata == null ? null : validFailureMetadata(metadata.promptVersion(), 50))
                .llmProvider(metadata == null ? null : validFailureMetadata(metadata.provider(), 30))
                .llmModel(metadata == null ? null : validFailureMetadata(metadata.model(), 100))
                .llmPlan(request.plan())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd())
                .credits(usage == null ? null : usage.credits())
                .requestHash(hash(request))
                .actionPayload(objectMapper.writeValueAsString(context.withMetadataSource(
                        metadata == null ? "UNAVAILABLE" : metadata.source())
                        .withUsageCompleteness(failureUsageCompleteness(usage, metadata))))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordInsightCacheHit(Long runId,
                                      Long issueId,
                                      InsightAuditContext context,
                                      LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                // 같은 캐시를 다시 조회한 생성 요청도 각각 한 건으로 센다.
                .idempotencyKey("insight-cache:" + UUID.randomUUID())
                .agentTask(AgentTask.INSIGHT)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(AgentRunStatus.REUSED)
                .promptVersion(context.expectedPromptVersion())
                // FREE를 넣으면 legacy quota가 무호출 캐시도 1회로 계산한다.
                .llmPlan(null)
                .inputTokens(0L)
                .outputTokens(0L)
                .costUsd(BigDecimal.ZERO)
                .credits(BigDecimal.ZERO)
                .requestHash(context.inputHash())
                .actionPayload(objectMapper.writeValueAsString(context.withMetadataSource("CACHE")
                        .withUsageCompleteness("COMPLETE")))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordKeywordStrategySuccess(Long runId,
                                             Long topicId,
                                             AgentKeywordStrategyRequest request,
                                             AgentKeywordStrategyResponse response,
                                             LocalDateTime startedAt) {
        AgentKeywordStrategyResponse.Meta meta = response.meta();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.KEYWORD_STRATEGY)
                .targetType(AgentTargetType.TOPIC)
                .targetId(topicId)
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion())
                .llmProvider(meta.provider())
                .llmModel(meta.model())
                .llmPlan(request.plan())
                .inputTokens(meta.inputTokens())
                .outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd())
                .credits(meta.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordKeywordStrategyFailure(Long runId,
                                             Long topicId,
                                             AgentKeywordStrategyRequest request,
                                             String failureCode,
                                             String failureMessage,
                                             AgentClientException.Usage usage,
                                             AgentTimeoutPhase timeoutPhase,
                                             LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.KEYWORD_STRATEGY)
                .targetType(AgentTargetType.TOPIC)
                .targetId(topicId)
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .timeoutPhase(timeoutPhase)
                .llmPlan(request.plan())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd())
                .credits(usage == null ? null : usage.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordReportSuccess(Long runId,
                                    AgentReportRequest request,
                                    AgentReportResponse response,
                                    LocalDateTime startedAt) {
        AgentReportResponse.Meta meta = response.meta();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.REPORT)
                .targetType(request.run().reportId() == null ? AgentTargetType.RUN : AgentTargetType.REPORT)
                .targetId(request.run().reportId() == null ? runId : request.run().reportId())
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion())
                .llmProvider(meta.provider())
                .llmModel(meta.model())
                .llmPlan(request.plan())
                .inputTokens(meta.inputTokens())
                .outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd())
                .credits(meta.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordReportFailure(Long runId,
                                    AgentReportRequest request,
                                    String failureCode,
                                    String failureMessage,
                                    AgentClientException.Usage usage,
                                    AgentTimeoutPhase timeoutPhase,
                                    LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.REPORT)
                .targetType(request.run().reportId() == null ? AgentTargetType.RUN : AgentTargetType.REPORT)
                .targetId(request.run().reportId() == null ? runId : request.run().reportId())
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .timeoutPhase(timeoutPhase)
                .llmPlan(request.plan())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd())
                .credits(usage == null ? null : usage.credits())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordInvestigationSuccess(Long runId,
                                           Long issueId,
                                           AgentExploreRequest request,
                                           AgentExploreResponse response,
                                           LocalDateTime startedAt,
                                           String queryHash,
                                           String rejectionReason,
                                           int addedArticleCount,
                                           int evidenceBefore,
                                           int evidenceAfter,
                                           String terminationReason) {
        AgentExploreResponse.Meta meta = response.meta();
        AgentExploreResponse.Proposal proposal = response.proposal();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.INVESTIGATE)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(rejectionReason != null
                        ? AgentRunStatus.SKIPPED
                        : meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion())
                .llmProvider(meta.provider())
                .llmModel(meta.model())
                .llmPlan(request.plan())
                .inputTokens(meta.inputTokens())
                .outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd())
                .credits(meta.credits())
                .requestHash(hash(request))
                .investigationStep(request.step())
                .investigationAction(proposal.action())
                .actionReason(truncate(proposal.reason()))
                .sourceKey(proposal.sourceKey())
                .queryHash(queryHash)
                .actionPayload(objectMapper.writeValueAsString(proposal))
                .rejectionReason(truncate(rejectionReason))
                .addedArticleCount(addedArticleCount)
                .evidenceBefore(evidenceBefore)
                .evidenceAfter(evidenceAfter)
                .terminationReason(terminationReason)
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordInvestigationFailure(Long runId,
                                           Long issueId,
                                           AgentExploreRequest request,
                                           String failureCode,
                                           String failureMessage,
                                           AgentClientException.Usage usage,
                                           AgentTimeoutPhase timeoutPhase,
                                           LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.INVESTIGATE)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .timeoutPhase(timeoutPhase)
                .llmPlan(request.plan())
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd())
                .credits(usage == null ? null : usage.credits())
                .requestHash(hash(request))
                .investigationStep(request.step())
                .terminationReason("FAILED")
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    @Transactional
    public void recordInvestigationSkipped(Long runId,
                                           Long issueId,
                                           AgentExploreRequest request,
                                           String rejectionReason,
                                           String terminationReason,
                                           int evidenceCount,
                                           LocalDateTime startedAt) {
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.INVESTIGATE)
                .targetType(AgentTargetType.ISSUE)
                .targetId(issueId)
                .status(AgentRunStatus.SKIPPED)
                .failureCode(terminationReason)
                .failureMessage(truncate(rejectionReason))
                .llmPlan(request.plan())
                .requestHash(hash(request))
                .investigationStep(request.step())
                .rejectionReason(truncate(rejectionReason))
                .evidenceBefore(evidenceCount)
                .evidenceAfter(evidenceCount)
                .terminationReason(terminationReason)
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    private String hash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private String failureUsageCompleteness(AgentClientException.Usage usage,
                                            AgentClientException.ExecutionMetadata metadata) {
        if (metadata == null) {
            // 과거 오류의 usage 숫자만으로 repair 등 전체 시도 관측 여부를 추정하지 않는다.
            return "UNKNOWN";
        }
        if ("AGENT_ERROR".equals(metadata.source())) {
            return metadata.usageCompleteness();
        }
        if (!"RESPONSE".equals(metadata.source()) || usage == null) {
            return "UNKNOWN";
        }
        return usageCompleteness(usage.inputTokens(), usage.outputTokens(), usage.costUsd(), usage.credits());
    }

    private String usageCompleteness(Long inputTokens,
                                     Long outputTokens,
                                     BigDecimal costUsd,
                                     BigDecimal credits) {
        int known = (inputTokens == null ? 0 : 1) + (outputTokens == null ? 0 : 1)
                + (costUsd == null ? 0 : 1) + (credits == null ? 0 : 1);
        return known == 4 ? "COMPLETE" : known == 0 ? "UNKNOWN" : "PARTIAL";
    }

    private String validFailureMetadata(String value, int maxBytes) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                        || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE))) {
            // 잘못된 메타 한 필드 때문에 유효한 오류 사용량 전체가 저장 실패하지 않게 한다.
            return null;
        }
        return value;
    }

    private String truncate(String message) {
        if (message == null
                || message.codePointCount(0, message.length()) <= MAX_FAILURE_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, message.offsetByCodePoints(0, MAX_FAILURE_MESSAGE_LENGTH));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ApiTimeZone.ZONE);
    }
}
