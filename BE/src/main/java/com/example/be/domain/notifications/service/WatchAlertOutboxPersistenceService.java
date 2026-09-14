package com.example.be.domain.notifications.service;

import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** 속보 후속 알림을 짧은 트랜잭션으로 선점하고 전송 결과를 영속화한다. */
@Service
@RequiredArgsConstructor
public class WatchAlertOutboxPersistenceService {

    private final WatchAlertOutboxRepository repository;
    private final WatchAlertOutboxBatchClaimer batchClaimer;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long scanUpperBound() {
        return repository.findScanUpperBound();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WatchAlertOutboxBatchClaimer.BatchClaim claimNextBatch(long afterId, long upToId, LocalDateTime now,
                                                                int remainingSlots) {
        return batchClaimer.claimAfter(afterId, upToId, now, remainingSlots);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(Long alertId) {
        repository.findById(alertId).ifPresent(alert ->
                alert.markSent(LocalDateTime.now(ApiTimeZone.ZONE)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(Long alertId, String error) {
        repository.findById(alertId).ifPresent(alert -> alert.retry(error));
    }

    public record WatchAlertSnapshot(Long id,
                                     Long watchId,
                                     WatchType watchType,
                                     Long issueId,
                                     Long notifyGroupId,
                                     String issueTitle,
                                     OffsetDateTime firstSeenAt,
                                     int followUpCount,
                                     int publisherCount,
                                     OffsetDateTime queuedAt,
                                     int attemptCount) {

        public String message() {
            if (watchType == WatchType.DISPUTED) {
                return "⚠ '%s'에 반박 기사 등장 · 후속 %d건 · 매체 %d곳 확인됨"
                        .formatted(issueTitle, followUpCount, publisherCount);
            }
            long elapsedHours = Math.max(0, Duration.between(firstSeenAt, queuedAt).toHours());
            String elapsed = elapsedHours == 0 ? "방금" : elapsedHours + "시간 전";
            return "%s 속보 '%s'에 후속 %d건 · 매체 %d곳 확인됨"
                    .formatted(elapsed, issueTitle, followUpCount, publisherCount);
        }
    }
}
