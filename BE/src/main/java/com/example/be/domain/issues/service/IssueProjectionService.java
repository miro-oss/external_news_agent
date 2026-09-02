package com.example.be.domain.issues.service;

import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueRelationType;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.IssueStatusHistory;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueRelationRepository;
import com.example.be.domain.issues.repository.IssueStatusHistoryRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** 기사 추가·재분석·정정마다 상태와 중요도를 현재 근거에서 다시 만드는 projection 경계. */
@Service
@RequiredArgsConstructor
public class IssueProjectionService {

    private static final Duration DISPUTED_WATCH_TTL = Duration.ofHours(48);

    private final NewsIssueRepository issueRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final IssueRelationRepository issueRelationRepository;
    private final IssueStatusHistoryRepository statusHistoryRepository;
    private final NewsWatchRepository watchRepository;
    private final IssueStatusCalculator statusCalculator;
    private final IssueImportanceCalculator importanceCalculator;

    @Transactional
    public void recalculate(Long issueId) {
        NewsIssue issue = issueRepository.findByIdForUpdate(issueId)
                .orElseThrow(() -> new IllegalStateException("재계산할 이슈가 없습니다. id=" + issueId));
        List<IssueArticle> memberships = issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(issueId);
        recalculate(issue, memberships, OffsetDateTime.now(ApiTimeZone.ZONE));
    }

    public void recalculate(NewsIssue issue,
                            List<IssueArticle> memberships,
                            OffsetDateTime now) {
        IssueStatusCalculator.Projection projection = issueRelationRepository
                .existsByToIssueIdAndRelationType(issue.getId(), IssueRelationType.REFUTES)
                ? new IssueStatusCalculator.Projection(
                IssueStatus.RETRACTED,
                "연결된 검증 실패·정정 이슈가 원 주장을 반박")
                : statusCalculator.calculate(issue, memberships);
        IssueStatus previous = issue.applyStatus(projection.status());
        if (previous != projection.status()) {
            statusHistoryRepository.save(IssueStatusHistory.builder()
                    .issue(issue)
                    .fromStatus(previous)
                    .toStatus(projection.status())
                    .reason(projection.reason())
                    .changedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                    .build());
        }
        issue.applyImportanceScore(importanceCalculator.calculate(issue, memberships, now));
        synchronizeDisputedWatch(issue, projection.status(), now);
    }

    private void synchronizeDisputedWatch(NewsIssue issue,
                                          IssueStatus status,
                                          OffsetDateTime now) {
        LocalDateTime localNow = now.atZoneSameInstant(ApiTimeZone.ZONE).toLocalDateTime();
        var current = watchRepository.findByIssueIdAndWatchType(issue.getId(), WatchType.DISPUTED);
        if (status != IssueStatus.DISPUTED) {
            current.ifPresent(NewsWatch::deactivate);
            return;
        }
        LocalDateTime expiresAt = localNow.plus(DISPUTED_WATCH_TTL);
        current.ifPresentOrElse(
                watch -> {
                    if (!watch.isActive() || !watch.getExpiresAt().isAfter(localNow)) {
                        watch.renewUntil(expiresAt);
                    }
                },
                () -> watchRepository.save(NewsWatch.builder()
                        .watchType(WatchType.DISPUTED)
                        .issue(issue)
                        .expiresAt(expiresAt)
                        .active(true)
                        .build()));
    }
}
