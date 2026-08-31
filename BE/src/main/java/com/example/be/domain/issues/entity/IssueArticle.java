package com.example.be.domain.issues.entity;

import com.example.be.domain.collection.entity.Article;
import jakarta.persistence.Column;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_issue_articles", uniqueConstraints =
        @UniqueConstraint(name = "uq_issue_article", columnNames = {"issue_id", "article_id"}))
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false)
    private NewsIssue issue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private IssueArticleRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "stance", nullable = false, length = 20)
    private IssueStance stance;

    @Enumerated(EnumType.STRING)
    @Column(name = "stance_source", nullable = false, length = 10)
    private IssueStanceSource stanceSource;

    @Column(name = "stance_confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal stanceConfidence;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    public void changeRole(IssueArticleRole role) {
        this.role = role;
    }

    public void moveToIssue(NewsIssue issue) {
        this.issue = issue;
    }

    public void applyStance(IssueStance stance,
                            IssueStanceSource stanceSource,
                            BigDecimal stanceConfidence) {
        if (stance == null || stanceSource == null || stanceConfidence == null
                || stanceConfidence.signum() < 0
                || stanceConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("이슈 기사 stance 값이 올바르지 않습니다.");
        }
        this.stance = stance;
        this.stanceSource = stanceSource;
        this.stanceConfidence = stanceConfidence;
    }
}
