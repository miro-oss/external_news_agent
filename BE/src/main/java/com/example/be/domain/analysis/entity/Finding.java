package com.example.be.domain.analysis.entity;

import com.example.be.domain.analysis.converter.FindingAnalysisSectionListConverter;
import com.example.be.domain.analysis.converter.FindingEntitiesConverter;
import com.example.be.domain.analysis.converter.FindingKeyPointListConverter;
import com.example.be.domain.analysis.converter.FindingPerspectiveTagListConverter;
import com.example.be.domain.analysis.converter.FindingSectionListConverter;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.global.converter.YnBooleanConverter;
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

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_source", nullable = false, length = 20)
    private AnalysisSource analysisSource = AnalysisSource.STUB;

    @Convert(converter = FindingSectionListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "sections", nullable = false)
    private List<FindingSection> sections;

    @Builder.Default
    @Convert(converter = FindingAnalysisSectionListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "analysis_sections", nullable = false)
    private List<FindingAnalysisSection> analysisSections = List.of();

    @Builder.Default
    @Convert(converter = FindingEntitiesConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "entities", nullable = false)
    private FindingEntities entities = FindingEntities.empty();

    @Builder.Default
    @Convert(converter = FindingPerspectiveTagListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "perspective_tags")
    private List<FindingPerspectiveTag> perspectiveTags = List.of();

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "llm_provider", length = 30)
    private String llmProvider;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "credits", precision = 10, scale = 3)
    private BigDecimal credits;

    /** 제목·요약·본문을 함께 해시한 분석 입력 버전. 동일 값의 LLM finding만 재사용한다. */
    @Column(name = "analysis_input_hash", length = Article.URL_HASH_LENGTH)
    private String analysisInputHash;

    @Builder.Default
    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_truncated_yn", nullable = false, length = 1)
    private boolean inputTruncated = false;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;

    /** A1 구조화 section이 있으면 여기서 기존 공개 key point 형태로 평탄화한다. */
    public List<FindingKeyPoint> getEffectiveKeyPoints() {
        if (analysisSections == null || analysisSections.isEmpty()) {
            return keyPoints == null ? List.of() : keyPoints;
        }
        return analysisSections.stream()
                .flatMap(section -> section.bullets().stream())
                .map(bullet -> new FindingKeyPoint(
                        bullet.text(), bullet.evidence(), bullet.groundedness()))
                .toList();
    }
}
