package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.financial.DartFinancialService;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.dart.exception.DartUpstreamException;
import com.example.be.domain.reports.comparison.ReportCompleted;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReportDartWorker {
    private final ReportDartRepository repository;
    private final ReportDartPersistence persistence;
    private final DartFinancialService financials;
    private final DartProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void completed(ReportCompleted event) { enqueueSafely(event.reportId()); }

    @Scheduled(fixedDelayString = "${news.dart.poll-interval-ms:5000}", scheduler = "dartScheduler")
    public void poll() {
        persistence.recover();
        repository.missing().forEach(this::enqueueSafely);
        if (!properties.isEnabled() || repository.paused(now())) return;
        for (var job : repository.pending(now())) {
            if (repository.paused(now())) break;
            process(job);
        }
    }

    private void enqueueSafely(long reportId) {
        try { persistence.enqueue(reportId); }
        catch (RuntimeException exception) {
            // Never print provider exceptions or URLs: DART authenticates through a URL query parameter.
            log.warn("공시 작업 등록 실패. reportId={}", reportId);
        }
    }

    void process(ReportDartRepository.Job job) {
        String token = UUID.randomUUID().toString();
        try {
            if (!persistence.claim(job, token)) return;
            var cache = persistence.cache(job.company().corpCode(), token);
            if (cache.data() != null) {
                persistence.complete(job, token, cache.data(), false);
            } else if (!cache.owner()) {
                persistence.defer(job, token);
            } else {
                // Both short claim transactions have committed before the external request begins.
                var data = financials.fetch(job.company());
                persistence.complete(job, token, data, true);
            }
        } catch (RuntimeException exception) {
            String upstreamStatus = exception instanceof DartUpstreamException upstream ? upstream.getStatus() : "INTERNAL";
            try { persistence.fail(job, token, upstreamStatus); }
            catch (RuntimeException persistenceFailure) {
                // The persisted lease will be recovered after restart or its deadline.
                log.warn("공시 작업 상태 저장 실패. reportId={}, corpCode={}", job.reportId(), job.company().corpCode());
            }
            log.warn("공시 자료 준비 실패. reportId={}, corpCode={}", job.reportId(), job.company().corpCode());
        }
    }

    private static LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
}
