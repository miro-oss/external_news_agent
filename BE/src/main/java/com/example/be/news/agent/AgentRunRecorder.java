package com.example.be.news.agent;

import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AgentRunRecorder {

    private static final int MAX_FAILURE_MESSAGE_LENGTH = 1000;

    private final AgentRunRepository repository;

    @Transactional
    public void recordSuccess(Long runId,
                              Long articleId,
                              AgentAnalyzeRequest request,
                              AgentAnalyzeResponse response,
                              LocalDateTime startedAt) {
        if (repository.existsByIdempotencyKey(request.idempotencyKey())) {
            return;
        }
        AgentAnalyzeResponse.Meta meta = response.meta();
        repository.save(AgentRun.builder()
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
                              LocalDateTime startedAt) {
        if (repository.existsByIdempotencyKey(request.idempotencyKey())) {
            return;
        }
        repository.save(AgentRun.builder()
                .collectionRunId(runId)
                .idempotencyKey(request.idempotencyKey())
                .agentTask(AgentTask.ANALYZE)
                .targetType(AgentTargetType.ARTICLE)
                .targetId(articleId)
                .status(AgentRunStatus.FAILED)
                .failureCode(failureCode)
                .failureMessage(truncate(failureMessage))
                .llmPlan(request.plan())
                .requestHash(hash(request))
                .startedAt(startedAt)
                .finishedAt(now())
                .build());
    }

    private String hash(AgentAnalyzeRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(request.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private String truncate(String message) {
        if (message == null || message.length() <= MAX_FAILURE_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ApiTimeZone.ZONE);
    }
}
