package com.example.be.domain.issues.entity;

import com.example.be.domain.issues.converter.IssueCrossSourceConverter;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.converter.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "news_issues")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", nullable = false, length = com.example.be.domain.collection.entity.Article.MAX_TITLE_LENGTH)
    private String title;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "summary")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IssueStatus status;

    @Column(name = "importance_score", precision = 6, scale = 2)
    private BigDecimal importanceScore;

    @Column(name = "sensitivity_score", precision = 6, scale = 2)
    private BigDecimal sensitivityScore;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "article_count", nullable = false)
    private int articleCount;

    @Column(name = "publisher_count", nullable = false)
    private int publisherCount;

    @Column(name = "independent_content_count", nullable = false)
    private int independentContentCount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "entities", nullable = false)
    private List<String> entities = List.of();

    @Builder.Default
    @Convert(converter = IssueCrossSourceConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "cross_source", nullable = false)
    private IssueCrossSource crossSource = IssueCrossSource.empty();

    public void refresh(String title,
                        OffsetDateTime firstSeenAt,
                        OffsetDateTime lastSeenAt,
                        int articleCount,
                        int publisherCount,
                        int independentContentCount,
                        List<String> entities) {
        this.title = title;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.articleCount = articleCount;
        this.publisherCount = publisherCount;
        this.independentContentCount = independentContentCount;
        this.entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public void applyRepresentativeSummary(String summary) {
        this.summary = summary;
    }

    public void applyCrossSource(IssueCrossSource crossSource) {
        this.crossSource = crossSource == null ? IssueCrossSource.empty() : crossSource;
    }

    public IssueStatus markMerged() {
        IssueStatus previous = this.status;
        this.status = IssueStatus.RETRACTED;
        this.articleCount = 0;
        this.publisherCount = 0;
        this.independentContentCount = 0;
        return previous;
    }
}
