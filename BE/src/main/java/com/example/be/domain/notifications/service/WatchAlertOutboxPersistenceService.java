package com.example.be.domain.notifications.service;

import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** 속보 후속 알림을 짧은 트랜잭션으로 선점하고 전송 결과를 영속화한다. */
@Service
@RequiredArgsConstructor
public class WatchAlertOutboxPersistenceService {

    private static final int CLAIM_LIMIT = 100;
    private static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final WatchAlertOutboxRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<WatchAlertSnapshot> claimPending() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        List<WatchAlertOutbox> alerts = repository.findClaimable(
                        now.minus(STALE_PROCESSING_TIMEOUT)).stream()
                .limit(CLAIM_LIMIT)
                .toList();
        alerts.forEach(alert -> alert.startProcessing(now));
        repository.flush();
        return alerts.stream().map(this::snapshot).toList();
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

    private WatchAlertSnapshot snapshot(WatchAlertOutbox alert) {
        return new WatchAlertSnapshot(
                alert.getId(),
                alert.getWatch().getId(),
                alert.getWatch().getWatchType(),
                alert.getWatch().getIssue().getId(),
                alert.getNotifyGroupId(),
                alert.getIssueTitle(),
                alert.getFirstSeenAt(),
                alert.getFollowUpCount(),
                alert.getPublisherCount(),
                alert.getQueuedAt(),
                alert.getAttemptCount());
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
