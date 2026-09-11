package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentReportChangesRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportChangesResponse;
import com.example.be.domain.analysis.agent.entity.AgentRun;
import com.example.be.domain.analysis.agent.entity.AgentRunStatus;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.entity.AgentTimeoutPhase;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
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
public class ReportComparisonRunRecorder {
    private final AgentRunJdbcRepository repository;
    private final ObjectMapper mapper;
    private final AgentQuotaService quota;

    @Transactional
    public void success(AgentReportChangesRequest request, AgentReportChangesResponse response, LocalDateTime startedAt) {
        var meta = response.meta();
        repository.insertIfAbsent(base(request, startedAt)
                .status(meta.mock() ? AgentRunStatus.MOCK : AgentRunStatus.SUCCESS)
                .promptVersion(meta.promptVersion()).llmProvider(meta.provider()).llmModel(meta.model())
                .inputTokens(meta.inputTokens()).outputTokens(meta.outputTokens())
                .costUsd(meta.costUsd()).credits(meta.credits()).build());
    }

    @Transactional
    public void failure(AgentReportChangesRequest request, AgentClientException exception,
                        LocalDateTime startedAt, QuotaReservation reservation) {
        var usage = exception.getUsage();
        var execution = exception.getExecutionMetadata();
        boolean inserted = repository.insertIfAbsent(base(request, startedAt)
                .status(AgentRunStatus.FAILED).failureCode(bounded(exception.getCode(), 100))
                .failureMessage("보고서 변화 비교 Agent 호출 또는 검증에 실패했습니다.")
                .promptVersion(execution == null || bounded(execution.promptVersion(), 50) == null
                        ? AgentReportComparisonAnalyzer.PROMPT_VERSION : execution.promptVersion())
                .llmProvider(execution == null ? null : bounded(execution.provider(), 30))
                .llmModel(execution == null ? null : bounded(execution.model(), 100))
                .timeoutPhase(exception.getTimeoutPhase() == AgentClientException.TimeoutPhase.NONE ? null
                        : AgentTimeoutPhase.valueOf(exception.getTimeoutPhase().name()))
                .inputTokens(usage == null ? null : usage.inputTokens())
                .outputTokens(usage == null ? null : usage.outputTokens())
                .costUsd(usage == null ? null : usage.costUsd()).credits(usage == null ? null : usage.credits())
                .build());
        if (!inserted) throw new IllegalStateException("비교 실패 사용량이 새 감사 기록으로 저장되지 않았습니다.");
        // Over-cap settlement relies on this audit row after releasing the reservation.
        // The insert and settlement must commit together, or both must roll back.
        quota.completeFailure(reservation, exception);
    }

    private AgentRun.AgentRunBuilder base(AgentReportChangesRequest request, LocalDateTime startedAt) {
        return AgentRun.builder().idempotencyKey(request.idempotencyKey()).agentTask(AgentTask.REPORT_CHANGES)
                .targetType(AgentTargetType.REPORT).targetId(request.reportId()).llmPlan(request.plan())
                .requestHash(hash(request)).startedAt(startedAt).finishedAt(LocalDateTime.now(ApiTimeZone.ZONE));
    }

    private String hash(Object request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private static String bounded(String value, int maxBytes) {
        return value == null || value.isBlank()
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes ? null : value;
    }
}
