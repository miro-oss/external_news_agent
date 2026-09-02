package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/** P2-3 중요도 공식. 모든 입력은 0~100으로 맞춘 뒤 상태 penalty를 마지막에 뺀다. */
@Component
@RequiredArgsConstructor
public class IssueImportanceCalculator {

    static final int VOLUME_SATURATION_ARTICLES = 10;
    static final double RECENCY_HALF_LIFE_HOURS = 24.0d;

    private final TopicFitScorer topicFitScorer;

    public BigDecimal calculate(NewsIssue issue,
                                List<IssueArticle> memberships,
                                OffsetDateTime now) {
        double topicFit = memberships == null || memberships.isEmpty()
                ? 0.0d
                : memberships.stream()
                .map(IssueArticle::getArticle)
                .mapToDouble(article -> topicFit(issue, article))
                .average()
                .orElse(0.0d);
        return score(
                issue.getSensitivityScore(),
                issue.getArticleCount(),
                issue.getPublisherCount(),
                issue.getIndependentContentCount(),
                issue.getLastSeenAt(),
                topicFit,
                issue.getStatus(),
                now);
    }

    BigDecimal score(BigDecimal sensitivity,
                     int articleCount,
                     int publisherCount,
                     int independentContentCount,
                     OffsetDateTime lastSeenAt,
                     double topicFit,
                     IssueStatus status,
                     OffsetDateTime now) {
        double sensitivityScore = clamp(sensitivity == null ? 0.0d : sensitivity.doubleValue());
        double volume = articleCount <= 0
                ? 0.0d
                : 100.0d * Math.min(
                1.0d,
                Math.log1p(articleCount) / Math.log1p(VOLUME_SATURATION_ARTICLES));
        double diversity = articleCount <= 0
                ? 0.0d
                : 100.0d
                * Math.min(1.0d, (double) independentContentCount / articleCount)
                * Math.min(1.0d, (double) publisherCount / 3.0d);
        double ageHours = lastSeenAt == null || now == null
                ? Double.POSITIVE_INFINITY
                : Math.max(0.0d, Duration.between(lastSeenAt, now).toMinutes() / 60.0d);
        double recency = Double.isFinite(ageHours)
                ? 100.0d * Math.pow(0.5d, ageHours / RECENCY_HALF_LIFE_HOURS)
                : 0.0d;
        double weighted = 0.40d * sensitivityScore
                + 0.20d * volume
                + 0.15d * diversity
                + 0.15d * recency
                + 0.10d * clamp(topicFit * 100.0d)
                - statusPenalty(status);
        return BigDecimal.valueOf(clamp(weighted)).setScale(2, RoundingMode.HALF_UP);
    }

    private double topicFit(NewsIssue issue, Article article) {
        String sourceLanguage = article.getSource() == null ? null : article.getSource().getLanguage();
        return topicFitScorer.score(
                issue.getTopic(),
                article.getTitle(),
                article.getSummary(),
                article.getLanguage(),
                sourceLanguage);
    }

    private double statusPenalty(IssueStatus status) {
        if (status == IssueStatus.RETRACTED) {
            return 50.0d;
        }
        if (status == IssueStatus.DISPUTED) {
            return 20.0d;
        }
        return 0.0d;
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(100.0d, value));
    }
}
