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
import com.example.be.domain.issues.repository.ContentGroupRepository;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.IssueRelationRepository;
import com.example.be.domain.issues.repository.IssueStatusHistoryRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

    private final ArticleRepository articleRepository;
    private final TopicRepository topicRepository;
    private final ContentGroupRepository contentGroupRepository;
    private final NewsIssueRepository issueRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final IssueRelationRepository issueRelationRepository;
    private final IssueStatusHistoryRepository statusHistoryRepository;

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

    private void applyIssue(ClusterPlan.IssueAssignment assignment, Map<Long, Article> articles) {
        Topic topic = topicRepository.findById(assignment.topicId()).orElseThrow();
        Article representative = requiredArticle(articles, assignment.representativeArticleId());
        NewsIssue issue = assignment.existingIssueId() == null
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
        for (Long articleId : assignment.articleIds()) {
            membershipByArticle.computeIfAbsent(articleId, ignored -> issueArticleRepository.save(
                    IssueArticle.builder()
                            .issue(issue)
                            .article(requiredArticle(articles, articleId))
                            .role(IssueArticleRole.MEMBER)
                            .stance(IssueStance.SUPPORTS)
                            .stanceSource(IssueStanceSource.RULE)
                            .stanceConfidence(BigDecimal.ONE)
                            .joinedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                            .build()));
        }
        membershipByArticle.values().forEach(membership -> membership.changeRole(
                membership.getArticle().getId().equals(representative.getId())
                        ? IssueArticleRole.REPRESENTATIVE
                        : IssueArticleRole.MEMBER));

        List<Article> members = membershipByArticle.values().stream()
                .map(IssueArticle::getArticle)
                .toList();
        issue.refresh(
                representative.getTitle(),
                firstSeen(members, assignment.firstSeenAt()),
                lastSeen(members, assignment.lastSeenAt()),
                members.size(),
                publisherCount(members),
                independentContentCount(members),
                assignment.entities());
    }

    private void mergeIssues(NewsIssue winner,
                             Topic topic,
                             List<Long> losingIssueIds,
                             Map<Long, IssueArticle> membershipByArticle) {
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
            recordIssueMerge(loser, winner);
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
                .title(representative.getTitle())
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
