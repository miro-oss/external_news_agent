package com.example.be.domain.issues.service;

import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.scoring.TopicFitScorer;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueStatus;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicEditorialFocus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** P2-3 중요도 공식. 모든 입력은 0~100으로 맞춘 뒤 상태 penalty를 마지막에 뺀다. */
@Component
@RequiredArgsConstructor
public class IssueImportanceCalculator {

    static final int VOLUME_SATURATION_ARTICLES = 10;
    static final double RECENCY_HALF_LIFE_HOURS = 24.0d;
    private static final Weights INDUSTRY_WEIGHTS = new Weights(0.40d, 0.20d, 0.15d, 0.15d, 0.10d);
    private static final Weights TECHNOLOGY_WEIGHTS = new Weights(0.10d, 0.05d, 0.05d, 0.35d, 0.45d);

    private final TopicFitScorer topicFitScorer;

    public BigDecimal calculate(NewsIssue issue,
                                List<IssueArticle> memberships,
                                OffsetDateTime now) {
        return calculate(issue, memberships, now, issue.getTopic());
    }

    /** Run selection can use its captured topic without rewriting the current issue projection. */
    public BigDecimal calculate(NewsIssue issue,
                                List<IssueArticle> memberships,
                                OffsetDateTime now,
                                Topic topic) {
        double topicFit = memberships == null || memberships.isEmpty()
                ? 0.0d
                : memberships.stream()
                .map(IssueArticle::getArticle)
                .mapToDouble(article -> topicFit(topic, article))
                .average()
                .orElse(0.0d);
        // lastSeenAt can be collection time for undated articles; only readable publication dates count.
        OffsetDateTime latestPublishedAt = memberships == null ? null : memberships.stream()
                .map(IssueArticle::getArticle)
                .map(Article::getPublishedAt)
                .filter(Objects::nonNull)
                .max(OffsetDateTime::compareTo)
                .orElse(null);
        return score(
                issue.getSensitivityScore(),
                issue.getArticleCount(),
                issue.getPublisherCount(),
                issue.getIndependentContentCount(),
                latestPublishedAt,
                topicFit,
                issue.getStatus(),
                now,
                TopicEditorialFocus.isAiTechnology(topic) ? TECHNOLOGY_WEIGHTS : INDUSTRY_WEIGHTS);
    }

    BigDecimal score(BigDecimal sensitivity,
                     int articleCount,
                     int publisherCount,
                     int independentContentCount,
                     OffsetDateTime lastSeenAt,
                     double topicFit,
                     IssueStatus status,
                     OffsetDateTime now) {
        return score(sensitivity, articleCount, publisherCount, independentContentCount,
                lastSeenAt, topicFit, status, now, INDUSTRY_WEIGHTS);
    }

    private BigDecimal score(BigDecimal sensitivity, int articleCount, int publisherCount,
                             int independentContentCount, OffsetDateTime lastSeenAt, double topicFit,
                             IssueStatus status, OffsetDateTime now, Weights weights) {
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
        // Technical releases can have one original source and no customer/deal sensitivity.
        double weighted = weights.sensitivity() * sensitivityScore
                + weights.volume() * volume
                + weights.diversity() * diversity
                + weights.recency() * recency
                + weights.topicFit() * clamp(topicFit * 100.0d)
                - statusPenalty(status);
        return BigDecimal.valueOf(clamp(weighted)).setScale(2, RoundingMode.HALF_UP);
    }

    private double topicFit(Topic topic, Article article) {
        String sourceLanguage = article.getSource() == null ? null : article.getSource().getLanguage();
        return topicFitScorer.score(
                topic,
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

    private record Weights(double sensitivity, double volume, double diversity, double recency, double topicFit) { }
}
