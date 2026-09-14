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
import com.example.be.domain.notifications.service.WatchAlertOutboxPersistenceService.WatchAlertSnapshot;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One bounded claim transaction. Locks and managed article bodies never survive into the next batch. */
@Service
@RequiredArgsConstructor
public class WatchAlertOutboxBatchClaimer {
    private static final int CANDIDATE_BATCH_SIZE = 100;
    private static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final WatchAlertOutboxRepository repository;
    private final IssueArticleRepository issueArticleRepository;
    private final BreakingNewsDetector breakingNewsDetector;
    private final IssueStatusCalculator issueStatusCalculator;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchClaim claimAfter(long afterId, long upToId, LocalDateTime now, int remainingSlots) {
        LocalDateTime staleBefore = now.minus(STALE_PROCESSING_TIMEOUT);
        List<Long> candidateIds = repository.findClaimableIds(staleBefore, afterId, upToId,
                PageRequest.of(0, CANDIDATE_BATCH_SIZE));
        if (candidateIds.isEmpty()) {
            return new BatchClaim(List.of(), afterId, true);
        }
        Map<Long, List<IssueArticle>> visibleMembershipsByIssue = new HashMap<>();
        List<WatchAlertSnapshot> snapshots = new ArrayList<>();
        long nextAfterId = candidateIds.getLast();
        for (WatchAlertOutbox alert : repository.findClaimableByIdsForUpdate(candidateIds, staleBefore)) {
            // The locking query rechecks eligibility; candidate IDs alone do not reserve an alert.
            if (hasVisibleEvidence(alert, visibleMembershipsByIssue)) {
                alert.startProcessing(LocalDateTime.now(ApiTimeZone.ZONE));
                snapshots.add(snapshot(alert, visibleMembershipsByIssue.get(alert.getWatch().getIssue().getId())));
                if (snapshots.size() == remainingSlots) {
                    // Leave unvisited candidates for the next invocation when its claim budget fills mid-page.
                    nextAfterId = alert.getId();
                    break;
                }
            }
        }
        repository.flush();
        // Move past hidden and concurrently claimed candidates as well as the ones returned to the sender.
        boolean exhausted = nextAfterId == candidateIds.getLast()
                && (candidateIds.size() < CANDIDATE_BATCH_SIZE || nextAfterId == upToId);
        return new BatchClaim(snapshots, nextAfterId, exhausted);
    }

    private boolean hasVisibleEvidence(WatchAlertOutbox alert, Map<Long, List<IssueArticle>> membershipsByIssue) {
        Long issueId = alert.getWatch().getIssue().getId();
        List<IssueArticle> visible = membershipsByIssue.computeIfAbsent(issueId, id ->
                issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(id).stream()
                        .filter(membership -> membership.getArticle().hasFullText()).toList());
        if (visible.size() < 2) {
            return false;
        }
        boolean hasTitleSource = visible.stream().map(membership -> alertTitle(membership.getArticle()))
                .filter(StringUtils::hasText).anyMatch(title -> title.equals(alert.getIssueTitle()));
        if (!hasTitleSource) {
            return false;
        }
        // The saved refutation message needs full-text evidence beyond a matching headline.
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

    private WatchAlertSnapshot snapshot(WatchAlertOutbox alert, List<IssueArticle> visibleMemberships) {
        int visiblePublisherCount = (int) visibleMemberships.stream().map(IssueArticle::getArticle)
                .map(article -> StringUtils.hasText(article.getSourceName()) ? article.getSourceName()
                        : article.getSource() == null ? null : article.getSource().getName())
                .filter(StringUtils::hasText).map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct().count();
        return new WatchAlertSnapshot(alert.getId(), alert.getWatch().getId(), alert.getWatch().getWatchType(),
                alert.getWatch().getIssue().getId(), alert.getNotifyGroupId(), alert.getIssueTitle(),
                alert.getFirstSeenAt(), Math.min(alert.getFollowUpCount(), visibleMemberships.size() - 1),
                Math.min(alert.getPublisherCount(), visiblePublisherCount), alert.getQueuedAt(), alert.getAttemptCount());
    }

    public record BatchClaim(List<WatchAlertSnapshot> snapshots, long afterId, boolean exhausted) {
        public BatchClaim {
            snapshots = List.copyOf(snapshots);
        }
    }
}
