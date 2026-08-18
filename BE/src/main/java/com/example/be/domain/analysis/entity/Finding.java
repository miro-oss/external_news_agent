package com.example.be.domain.analysis.entity;

import com.example.be.domain.analysis.converter.FindingKeyPointListConverter;
import com.example.be.domain.analysis.converter.FindingSectionListConverter;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
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

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "news_findings")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private CollectionRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "summary", nullable = false)
    private String summary;

    @Convert(converter = FindingKeyPointListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "key_points", nullable = false)
    private List<FindingKeyPoint> keyPoints;

    @Column(name = "intent", length = 200)
    private String intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment", nullable = false, length = 20)
    private Sentiment sentiment;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "relevance", nullable = false, length = 20)
    private Relevance relevance;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Convert(converter = FindingSectionListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "sections", nullable = false)
    private List<FindingSection> sections;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;
}
