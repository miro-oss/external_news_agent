package com.example.be.domain.reports.comparison;

import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportComparisonWorker {
    private final ReportComparisonRepository repository;
    private final ReportComparisonPersistence persistence;
    private final ReportComparisonEngine engine;
    private final ReportComparisonAnalyzer analyzer;
    @Value("${news.reports.daily.enabled:true}") private boolean enabled = true;
    @Value("${news.scheduling.enabled:true}") private boolean schedulingEnabled = true;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void completed(ReportCompleted event) {
        // Persistence runs in its own transaction. No comparison failure can undo report completion or delivery.
        enqueueSafely(event.reportId());
    }

    @Scheduled(fixedDelayString = "${news.reports.changes.poll-interval-ms:15000}", scheduler = "reportComparisonScheduler")
    public void poll() {
        if (!enabled || !schedulingEnabled) return;
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        // Unknown provider outcomes are terminal; recovery never repeats a paid call.
        repository.expireRunning(now.minusMinutes(30), now);
        repository.missingJobs().forEach(this::enqueueSafely);
        repository.pending().forEach(this::process);
    }

    private void enqueueSafely(long reportId) {
        try { persistence.enqueue(reportId); }
        catch (RuntimeException exception) { log.warn("보고서 비교 작업 등록 실패. reportId={}", reportId, exception); }
    }

    void process(long reportId) {
        try {
            var claimed = persistence.claim(reportId);
            if (claimed.isEmpty()) return;
            var job = claimed.get();
            try {
                var plan = engine.prepare(job.work());
                var result = plan.candidates().isEmpty() ? plan.result()
                        : engine.assess(plan, analyzer.analyze(reportId, job.work().baseReportId(), plan.candidates()));
                persistence.finish(reportId, result);
            } catch (RuntimeException exception) {
                log.warn("보고서 변화 비교 실패. reportId={}", reportId, exception);
                persistence.finish(reportId, job.result().withStatus(ReportChanges.Status.FAILED));
            }
        } catch (RuntimeException exception) {
            log.warn("보고서 비교 작업 처리 실패. reportId={}", reportId, exception);
        }
    }
}
