package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.dto.AgentAnalyzeRequest;
import com.example.be.domain.analysis.agent.dto.AgentAnalyzeResponse;
import com.example.be.domain.analysis.agent.dto.AgentReportRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportResponse;
import com.example.be.domain.analysis.agent.client.AgentClientException;
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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

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
    public void recordReportSuccess(Long runId,
                                    AgentReportRequest request,
                                    AgentReportResponse response,
                                    LocalDateTime startedAt) {
        AgentReportResponse.Meta meta = response.meta();
        repository.insertIfAbsent(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.REPORT)
                .targetType(AgentTargetType.RUN)
                .targetId(runId)
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
                .targetType(AgentTargetType.RUN)
                .targetId(runId)
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

    private String hash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
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
