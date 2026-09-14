package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.cluster.BreakingNewsDetector;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.service.IssueStatusCalculator;
import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** 속보 후속 알림을 짧은 트랜잭션으로 선점하고 전송 결과를 영속화한다. */
@Service
@RequiredArgsConstructor
public class WatchAlertOutboxPersistenceService {

    private static final int CLAIM_LIMIT = 100;
    private static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final WatchAlertOutboxRepository repository;
    private final IssueArticleRepository issueArticleRepository;
    private final BreakingNewsDetector breakingNewsDetector;
    private final IssueStatusCalculator issueStatusCalculator;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<WatchAlertSnapshot> claimPending() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Map<Long, List<IssueArticle>> visibleMembershipsByIssue = new HashMap<>();
        List<WatchAlertOutbox> alerts = repository.findClaimable(
                        now.minus(STALE_PROCESSING_TIMEOUT)).stream()
                // Old outboxes have only a saved title, so require an available source for that title.
                // Filter before the limit so unavailable old alerts cannot block deliverable ones.
                .filter(alert -> hasVisibleEvidence(alert, visibleMembershipsByIssue))
                .limit(CLAIM_LIMIT)
                .toList();
        alerts.forEach(alert -> alert.startProcessing(now));
        repository.flush();
        return alerts.stream().map(this::snapshot).toList();
    }

    private boolean hasVisibleEvidence(WatchAlertOutbox alert,
                                       Map<Long, List<IssueArticle>> membershipsByIssue) {
        Long issueId = alert.getWatch().getIssue().getId();
        List<IssueArticle> visible = membershipsByIssue.computeIfAbsent(issueId, id ->
                issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(id).stream()
                        .filter(membership -> membership.getArticle().hasFullText())
                        .toList());
        boolean hasTitleSource = visible.stream().map(membership -> alertTitle(membership.getArticle()))
                .filter(StringUtils::hasText).anyMatch(title -> title.equals(alert.getIssueTitle()));
        if (!hasTitleSource) {
            return false;
        }
        // A visible headline alone cannot establish the saved "refutation appeared" message.
        // This calculation is pure: preserve the stored issue projection and outbox snapshot.
        return alert.getWatch().getWatchType() != WatchType.DISPUTED
                || issueStatusCalculator.calculateFromFullText(alert.getWatch().getIssue(), visible).status()
                == IssueStatus.DISPUTED;
    }

    private String alertTitle(Article article) {
        if (!breakingNewsDetector.hasExplicitMarker(article.getTitle())) {
            return article.getTitle();
        }
        String coreTitle = breakingNewsDetector.coreTitle(article.getTitle());
        return StringUtils.hasText(coreTitle) ? coreTitle : article.getTitle();
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
