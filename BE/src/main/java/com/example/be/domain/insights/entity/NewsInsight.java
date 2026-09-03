package com.example.be.domain.insights.entity;

import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.converter.InsightFactListConverter;
import com.example.be.domain.insights.converter.InsightImplicationListConverter;
import com.example.be.global.converter.LongListJsonConverter;
import com.example.be.global.converter.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Entity
@Table(name = "news_insights")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private AgentTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 30)
    private Audience audience;

    @Column(name = "headline", nullable = false, length = 500)
    private String headline;

    @Convert(converter = InsightFactListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "facts_json", nullable = false)
    private List<InsightFact> facts;

    @Convert(converter = InsightImplicationListConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "implications_json", nullable = false)
    private List<InsightImplication> implications;

    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "watch_next_json", nullable = false)
    private List<String> watchNext;

    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "watch_entities_json", nullable = false)
    private List<String> watchEntities = List.of();

    @Builder.Default
    @Convert(converter = LongListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "input_article_ids_json", nullable = false)
    private List<Long> inputArticleIds = List.of();

    @Builder.Default
    @Convert(converter = LongListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "related_article_ids_json", nullable = false)
    private List<Long> relatedArticleIds = List.of();

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "prompt_version", nullable = false, length = 50)
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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void addRelatedArticleId(Long articleId) {
        if (articleId == null || inputArticleIds.contains(articleId)
                || relatedArticleIds.contains(articleId)) {
            return;
        }
        List<Long> updated = new ArrayList<>(relatedArticleIds);
        updated.add(articleId);
        this.relatedArticleIds = List.copyOf(updated);
    }

    public void mergeRelatedArticleIds(Collection<Long> articleIds) {
        articleIds.forEach(this::addRelatedArticleId);
    }

    public void mergeInputArticleIds(Collection<Long> articleIds) {
        Set<Long> merged = new HashSet<>(inputArticleIds);
        articleIds.stream().filter(Objects::nonNull).forEach(merged::add);
        this.inputArticleIds = merged.stream().sorted().toList();
        // 병합으로 원본 기사에 포함된 항목은 관련 새 기사에서도 제외한다.
        this.relatedArticleIds = relatedArticleIds.stream()
                .filter(articleId -> !merged.contains(articleId))
                .toList();
    }

    public void moveToTarget(Long targetId) {
        this.targetId = targetId;
    }
}
