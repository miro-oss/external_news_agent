package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.issues.entity.ContentGroup;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.entity.IssueRelation;
import com.example.be.domain.issues.entity.IssueRelationType;
import com.example.be.domain.issues.entity.IssueStance;
import com.example.be.domain.issues.entity.IssueStanceSource;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.IssueStatusHistory;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.ContentGroupRepository;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueRelationRepository;
import com.example.be.domain.issues.repository.IssueStatusHistoryRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.domain.issues.service.IssueProjectionService;
import com.example.be.domain.issues.service.IssueRefutationLinker;
import com.example.be.domain.issues.service.IssueStanceClassifier;
import com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus;
import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import com.example.be.domain.notifications.repository.WatchAlertOutboxRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 계산이 끝난 결과만 짧은 트랜잭션으로 반영한다. */
@Service
@RequiredArgsConstructor
public class IssueClusterWriter {

    private static final Duration WATCH_TTL = Duration.ofHours(48);
    private static final Duration WATCH_COOLDOWN = Duration.ofMinutes(30);

    private final ArticleRepository articleRepository;
    private final TopicRepository topicRepository;
    private final ContentGroupRepository contentGroupRepository;
    private final NewsIssueRepository issueRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final IssueRelationRepository issueRelationRepository;
    private final IssueStatusHistoryRepository statusHistoryRepository;
    private final NewsWatchRepository watchRepository;
    private final WatchAlertOutboxRepository watchAlertOutboxRepository;
    private final BreakingNewsDetector breakingNewsDetector;
    private final IssueStanceClassifier stanceClassifier;
    private final IssueProjectionService projectionService;
    private final IssueRefutationLinker refutationLinker;

    @Transactional
    public void write(ClusterPlan plan) {
        Set<Long> articleIds = new LinkedHashSet<>();
        plan.contentGroups().forEach(group -> articleIds.addAll(group.articleIds()));
        plan.issues().forEach(issue -> articleIds.addAll(issue.articleIds()));
        Map<Long, Article> articles = new LinkedHashMap<>();
        articleRepository.findAllById(articleIds).forEach(article -> articles.put(article.getId(), article));
        if (articles.size() != articleIds.size()) {
            throw new IllegalStateException("클러스터링 중 기사가 사라졌습니다.");
        }

        applyContentGroups(plan.contentGroups(), articles);
        for (ClusterPlan.IssueAssignment assignment : plan.issues()) {
            applyIssue(assignment, articles);
        }
    }

    private void applyContentGroups(List<ClusterPlan.ContentGroupAssignment> assignments,
                                    Map<Long, Article> articles) {
        for (ClusterPlan.ContentGroupAssignment assignment : assignments) {
            Article representative = requiredArticle(articles, assignment.representativeArticleId());
            ContentGroup group;
            if (assignment.existingContentGroupId() == null) {
                group = contentGroupRepository.save(ContentGroup.builder()
                        .representativeArticle(representative)
                        .simhash(assignment.simhash())
                        .createdAt(LocalDateTime.now(ApiTimeZone.ZONE))
                        .build());
            } else {
                group = contentGroupRepository.findById(assignment.existingContentGroupId())
                        .orElseThrow(() -> new IllegalStateException(
                                "본문 중복군이 없습니다. id=" + assignment.existingContentGroupId()));
            }
            assignment.articleIds().forEach(articleId -> requiredArticle(articles, articleId)
                    .assignContentGroup(group));
            mergeContentGroups(group, assignment, representative);
        }
    }

    private void mergeContentGroups(ContentGroup winner,
                                    ClusterPlan.ContentGroupAssignment assignment,
                                    Article representative) {
        List<Long> losingIds = assignment.mergedContentGroupIds();
        if (!losingIds.isEmpty()) {
            List<ContentGroup> losingGroups = contentGroupRepository.findAllById(losingIds);
            if (losingGroups.size() != losingIds.size()) {
                throw new IllegalStateException("병합할 본문 중복군 일부가 없습니다. ids=" + losingIds);
            }
            articleRepository.findByContentGroupIdIn(losingIds)
                    .forEach(article -> article.assignContentGroup(winner));
            articleRepository.flush();
            contentGroupRepository.deleteAll(losingGroups);
            contentGroupRepository.flush();
        }
        winner.refreshRepresentative(representative, assignment.simhash());
    }

    private void applyIssue(ClusterPlan.IssueAssignment assignment,
                            Map<Long, Article> articles) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Topic topic = topicRepository.findById(assignment.topicId()).orElseThrow();
        Article representative = requiredArticle(articles, assignment.representativeArticleId());
        boolean newIssue = assignment.existingIssueId() == null;
        NewsIssue issue = newIssue
                ? createIssue(topic, representative, assignment)
                : issueRepository.findById(assignment.existingIssueId())
                .orElseThrow(() -> new IllegalStateException(
                        "이슈가 없습니다. id=" + assignment.existingIssueId()));
        if (!issue.getTopic().getId().equals(topic.getId())) {
            throw new IllegalStateException("서로 다른 주제의 이슈를 병합할 수 없습니다.");
        }

        Map<Long, IssueArticle> membershipByArticle = new LinkedHashMap<>();
        issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(issue.getId())
                .forEach(membership -> membershipByArticle.put(
                        membership.getArticle().getId(), membership));
        mergeIssues(issue, topic, assignment.mergedIssueIds(), membershipByArticle);
        Article stanceReference = stanceReference(
                representative, assignment.articleIds(), membershipByArticle, articles);
        Set<Long> existingArticleIds = Set.copyOf(membershipByArticle.keySet());
        for (Long articleId : assignment.articleIds()) {
            membershipByArticle.computeIfAbsent(articleId, ignored -> {
                Article article = requiredArticle(articles, articleId);
                IssueStanceClassifier.Result stance = stanceClassifier.classify(stanceReference, article);
                return issueArticleRepository.save(IssueArticle.builder()
                        .issue(issue)
                        .article(article)
                        .role(IssueArticleRole.MEMBER)
                        .stance(stance.stance())
                        .stanceSource(IssueStanceSource.RULE)
                        .stanceConfidence(stance.confidence())
                        .joinedAt(now)
                        .build());
            });
        }
        membershipByArticle.values().forEach(membership -> membership.changeRole(
                roleOf(membership.getArticle(), representative)));

        List<Article> members = membershipByArticle.values().stream()
                .map(IssueArticle::getArticle)
                .toList();
        issue.refresh(
                issueTitle(representative),
                firstSeen(members, assignment.firstSeenAt()),
                lastSeen(members, assignment.lastSeenAt()),
                members.size(),
                publisherCount(members),
                independentContentCount(members),
                assignment.entities());
        List<IssueArticle> memberships = List.copyOf(membershipByArticle.values());
        projectionService.recalculate(
                issue,
                memberships,
                now.atZone(ApiTimeZone.ZONE).toOffsetDateTime());
        if (newIssue) {
            refutationLinker.linkNewIssue(issue, representative)
                    .ifPresent(projectionService::recalculate);
        }

        List<Article> newArticles = membershipByArticle.entrySet().stream()
                .filter(entry -> !existingArticleIds.contains(entry.getKey()))
                .map(entry -> entry.getValue().getArticle())
                .toList();
        List<NewsWatch> eligibleWatches = newIssue
                ? List.of()
                : watchRepository.findEligibleForNotification(issue.getId(), now);
        if (!newArticles.isEmpty()) {
            enqueueAlerts(eligibleWatches, issue, members, now);
        }
        registerBreakingWatch(issue, newArticles, now);
    }

    private Article stanceReference(Article representative,
                                    List<Long> assignmentArticleIds,
                                    Map<Long, IssueArticle> existingMemberships,
                                    Map<Long, Article> articles) {
        return existingMemberships.values().stream()
                .map(IssueArticle::getArticle)
                .filter(article -> !stanceClassifier.hasExplicitCorrection(article))
                .findFirst()
                .or(() -> assignmentArticleIds.stream()
                        .map(articles::get)
                        .filter(article -> article != null)
                        .filter(article -> !stanceClassifier.hasExplicitCorrection(article))
                        .min(Comparator.comparing(
                                        this::eventTime,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(Article::getId)))
                .orElse(representative);
    }

    private void mergeIssues(NewsIssue winner,
                             Topic topic,
                             List<Long> losingIssueIds,
                             Map<Long, IssueArticle> membershipByArticle) {
        Map<WatchType, NewsWatch> winnerWatches = new LinkedHashMap<>();
        if (!losingIssueIds.isEmpty()) {
            watchRepository.findByIssueIdOrderByIdAsc(winner.getId())
                    .forEach(watch -> winnerWatches.put(watch.getWatchType(), watch));
        }
        for (Long losingIssueId : losingIssueIds) {
            NewsIssue loser = issueRepository.findById(losingIssueId)
                    .orElseThrow(() -> new IllegalStateException("병합할 이슈가 없습니다. id=" + losingIssueId));
            if (!loser.getTopic().getId().equals(topic.getId())) {
                throw new IllegalStateException("서로 다른 주제의 이슈를 병합할 수 없습니다.");
            }
            for (IssueArticle losingMembership :
                    issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(losingIssueId)) {
                IssueArticle duplicate = membershipByArticle.get(losingMembership.getArticle().getId());
                if (duplicate == null) {
                    losingMembership.moveToIssue(winner);
                    membershipByArticle.put(losingMembership.getArticle().getId(), losingMembership);
                } else {
                    issueArticleRepository.delete(losingMembership);
                }
            }
            mergeWatches(winner, loser, winnerWatches);
            recordIssueMerge(loser, winner);
        }
    }

    private void mergeWatches(NewsIssue winner,
                              NewsIssue loser,
                              Map<WatchType, NewsWatch> winnerByType) {
        for (NewsWatch losingWatch : watchRepository.findByIssueIdOrderByIdAsc(loser.getId())) {
            if (winnerByType.containsKey(losingWatch.getWatchType())) {
                losingWatch.deactivate();
            } else {
                losingWatch.moveToIssue(winner);
                winnerByType.put(losingWatch.getWatchType(), losingWatch);
            }
        }
    }

    private IssueArticleRole roleOf(Article article, Article representative) {
        if (article.getId().equals(representative.getId())) {
            return IssueArticleRole.REPRESENTATIVE;
        }
        if (isBreaking(article)) {
            return IssueArticleRole.BREAKING;
        }
        return IssueArticleRole.MEMBER;
    }

    private boolean isBreaking(Article article) {
        return breakingNewsDetector.hasExplicitMarker(article.getTitle());
    }

    private String issueTitle(Article representative) {
        if (!breakingNewsDetector.hasExplicitMarker(representative.getTitle())) {
            return representative.getTitle();
        }
        String coreTitle = breakingNewsDetector.coreTitle(representative.getTitle());
        return StringUtils.hasText(coreTitle) ? coreTitle : representative.getTitle();
    }

    private void registerBreakingWatch(NewsIssue issue,
                                       List<Article> members,
                                       LocalDateTime now) {
        if (members.stream().noneMatch(article -> breakingNewsDetector.hasExplicitMarker(article.getTitle()))) {
            return;
        }
        LocalDateTime expiresAt = now.plus(WATCH_TTL);
        watchRepository.findByIssueIdAndWatchType(issue.getId(), WatchType.BREAKING)
                .ifPresentOrElse(
                        watch -> {
                            if (!watch.isActive() || !watch.getExpiresAt().isAfter(now)) {
                                watch.renewUntil(expiresAt);
                            }
                        },
                        () -> watchRepository.save(NewsWatch.builder()
                                .watchType(WatchType.BREAKING)
                                .issue(issue)
                                .sensitivityAtWatch(issue.getSensitivityScore())
                                .expiresAt(expiresAt)
                                .active(true)
                                .build()));
    }

    private void enqueueAlerts(List<NewsWatch> watches,
                               NewsIssue issue,
                               List<Article> members,
                               LocalDateTime now) {
        if (watches.isEmpty()) {
            return;
        }
        OffsetDateTime claimedAt = now.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
        int followUpCount = Math.max(1, issue.getArticleCount() - 1);
        Article breakingArticle = members.stream()
                .filter(article -> breakingNewsDetector.hasExplicitMarker(article.getTitle()))
                .min(Comparator.comparing(
                        this::eventTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        String breakingTitle = breakingArticle == null ? issue.getTitle() : issueTitle(breakingArticle);
        OffsetDateTime breakingAt = breakingArticle == null || eventTime(breakingArticle) == null
                ? issue.getFirstSeenAt()
                : eventTime(breakingArticle);
        for (NewsWatch watch : watches) {
            if (!watch.isActive() || !watch.getExpiresAt().isAfter(now)) {
                continue;
            }
            watch.claimUntil(now.plus(WATCH_COOLDOWN));
            watchAlertOutboxRepository.save(WatchAlertOutbox.builder()
                    .watch(watch)
                    .notifyGroupId(watch.getNotifyGroup() == null
                            ? null
                            : watch.getNotifyGroup().getId())
                    .issueTitle(breakingTitle)
                    .firstSeenAt(breakingAt)
                    .followUpCount(followUpCount)
                    .publisherCount(issue.getPublisherCount())
                    .queuedAt(claimedAt)
                    .status(WatchAlertDeliveryStatus.PENDING)
                    .attemptCount(0)
                    .build());
        }
    }

    private void recordIssueMerge(NewsIssue loser, NewsIssue winner) {
        IssueStatus previous = loser.markMerged();
        if (previous != IssueStatus.RETRACTED) {
            statusHistoryRepository.save(IssueStatusHistory.builder()
                    .issue(loser)
                    .fromStatus(previous)
                    .toStatus(IssueStatus.RETRACTED)
                    .reason("이슈 병합: #" + winner.getId())
                    .changedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                    .build());
        }
        if (!issueRelationRepository.existsByFromIssueIdAndToIssueIdAndRelationType(
                loser.getId(), winner.getId(), IssueRelationType.UPDATES)) {
            issueRelationRepository.save(IssueRelation.builder()
                    .fromIssue(loser)
                    .toIssue(winner)
                    .relationType(IssueRelationType.UPDATES)
                    .createdAt(LocalDateTime.now(ApiTimeZone.ZONE))
                    .build());
        }
    }

    private NewsIssue createIssue(Topic topic,
                                  Article representative,
                                  ClusterPlan.IssueAssignment assignment) {
        NewsIssue issue = issueRepository.save(NewsIssue.builder()
                .title(issueTitle(representative))
                .status(IssueStatus.EMERGING)
                .firstSeenAt(assignment.firstSeenAt())
                .lastSeenAt(assignment.lastSeenAt())
                .articleCount(0)
                .publisherCount(0)
                .independentContentCount(0)
                .topic(topic)
                .entities(assignment.entities())
                .crossSource(IssueCrossSource.empty())
                .build());
        statusHistoryRepository.save(IssueStatusHistory.builder()
                .issue(issue)
                .toStatus(IssueStatus.EMERGING)
                .reason("최초 기사 관측")
                .changedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
        return issue;
    }

    private OffsetDateTime firstSeen(List<Article> articles, OffsetDateTime fallback) {
        return articles.stream().map(this::eventTime).filter(value -> value != null)
                .min(OffsetDateTime::compareTo).orElse(fallback);
    }

    private OffsetDateTime lastSeen(List<Article> articles, OffsetDateTime fallback) {
        return articles.stream().map(this::eventTime).filter(value -> value != null)
                .max(OffsetDateTime::compareTo).orElse(fallback);
    }

    private OffsetDateTime eventTime(Article article) {
        if (article.getPublishedAt() != null) {
            return article.getPublishedAt();
        }
        return article.getCollectedAt() == null
                ? null
                : article.getCollectedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private int publisherCount(List<Article> articles) {
        return (int) articles.stream().map(this::publisher).filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).distinct().count();
    }

    private int independentContentCount(List<Article> articles) {
        return (int) articles.stream()
                .map(article -> article.getContentGroup() == null
                        ? "article:" + article.getId()
                        : "group:" + article.getContentGroup().getId())
                .distinct()
                .count();
    }

    private String publisher(Article article) {
        return StringUtils.hasText(article.getSourceName())
                ? article.getSourceName()
                : article.getSource().getName();
    }

    private Article requiredArticle(Map<Long, Article> articles, long articleId) {
        Article article = articles.get(articleId);
        if (article == null) {
            throw new IllegalStateException("기사가 없습니다. id=" + articleId);
        }
        return article;
    }
}
