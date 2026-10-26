package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.reports.comparison.ReportCompleted;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReportInsightWorker {
    private final ReportInsightJobRepository jobs;
    private final ReportInsightJobPersistence persistence;
    private final ReportInsightService insights;
    private final AgentProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void completed(ReportCompleted event) {
        // No provider work on the report completion thread or inside its transaction.
        enqueueSafely(event.reportId());
    }

    @Scheduled(fixedDelayString = "${news.reports.insights.poll-interval-ms:5000}", scheduler = "reportInsightScheduler")
    public void poll() {
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        var abandonedAfter = properties.getReportInsightTimeout().plus(properties.getConnectTimeout()).plusMinutes(5);
        jobs.expireRunning(now.minus(abandonedAfter), now);
        jobs.missingJobs().forEach(this::enqueueSafely);
        jobs.pending().forEach(this::process);
    }

    private void enqueueSafely(long reportId) {
        try { persistence.enqueue(reportId); }
        catch (RuntimeException exception) {
            log.warn("리포트 관점 자동 분석 작업 등록 실패. reportId={}", reportId, exception);
        }
    }

    void process(ReportInsightJobRepository.Job job) {
        try {
            if (!persistence.claim(job)) return;
            boolean succeeded = false;
            try {
                // Existing cache, quota reservation, evidence validation and usage audit apply unchanged.
                insights.createAutomatic(job.reportId(), job.audience());
                succeeded = true;
            } catch (RuntimeException exception) {
                log.warn("리포트 관점 자동 분석 실패. reportId={}, audience={}", job.reportId(), job.audience(), exception);
            }
            persistence.finish(job, succeeded);
        } catch (RuntimeException exception) {
            // A persistence failure retains RUNNING; expiry will mark it terminal without another provider call.
            log.warn("리포트 관점 자동 분석 작업 처리 실패. reportId={}, audience={}", job.reportId(), job.audience(), exception);
        }
    }
}
