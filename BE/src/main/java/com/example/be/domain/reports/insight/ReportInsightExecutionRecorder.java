package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightRequest;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.analysis.agent.service.ReportInsightAuditContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service @RequiredArgsConstructor
public class ReportInsightExecutionRecorder {
    private final AgentRunRecorder recorder;
    private final AgentQuotaService quota;
    private final ReportInsightPersistenceService persistence;

    @Transactional
    public NewsReportInsight success(ReportInsightSnapshotAssembler.Snapshot snapshot,
            AgentReportInsightRequest request, AgentReportInsightResponse response,
            ReportInsightAuditContext context, LocalDateTime startedAt, QuotaReservation reservation) {
        var saved = persistence.saveGenerated(snapshot, response).getFirst();
        if (!recorder.recordReportInsightSuccess(snapshot.runId(), request, response, context, startedAt))
            throw new IllegalStateException("리포트 인사이트 사용량 감사 기록을 저장하지 못했습니다.");
        quota.completeSuccess(reservation, response.meta().credits());
        return saved;
    }

    @Transactional
    public void failure(Long runId, AgentReportInsightRequest request, AgentClientException exception,
            ReportInsightAuditContext context, LocalDateTime startedAt, QuotaReservation reservation) {
        if (!recorder.recordReportInsightFailure(runId, request, exception, context, startedAt))
            throw new IllegalStateException("리포트 인사이트 실패 사용량 감사 기록을 저장하지 못했습니다.");
        // Over-cap release relies on the known credits in agent_runs; both writes commit together.
        quota.completeObservedFailure(reservation, exception);
    }
}
