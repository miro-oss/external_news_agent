package com.example.be.domain.reports.entity;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.global.converter.LongListJsonConverter;
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
import jakarta.persistence.OneToOne;
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
@Table(name = "news_reports")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsReport {

    public static final int MAX_TITLE_LENGTH = 500;
    public static final int MAX_MODEL_NAME_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private CollectionRun run;

    @Column(name = "title", nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "markdown_body", nullable = false)
    private String markdownBody;

    @Column(name = "model_name", nullable = false, length = MAX_MODEL_NAME_LENGTH)
    private String modelName;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "llm_provider", length = 30)
    private String llmProvider;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "credits", precision = 10, scale = 3)
    private BigDecimal credits;

    @Builder.Default
    @Convert(converter = LongListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "report_reflected_finding_ids", nullable = false)
    private List<Long> reflectedFindingIds = List.of();

    @Builder.Default
    @Convert(converter = LongListJsonConverter.class)
    @JdbcTypeCode(SqlTypes.CLOB)
    @Column(name = "report_excluded_finding_ids", nullable = false)
    private List<Long> excludedFindingIds = List.of();

    /** false인 기존 행은 저장된 참조 스냅샷이 없으므로 조회 시 레거시 계산을 사용한다. */
    @Convert(converter = YnBooleanConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "coverage_recorded_yn", nullable = false, length = 1)
    private boolean coverageRecorded;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "report_status", nullable = false, length = 30)
    private ReportStatus reportStatus = ReportStatus.FALLBACK;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    public void complete(String title,
                         String markdownBody,
                         String modelName,
                         String promptVersion,
                         String llmProvider,
                         Long inputTokens,
                         Long outputTokens,
                         BigDecimal costUsd,
                         BigDecimal credits,
                         List<Long> reflectedFindingIds,
                         List<Long> excludedFindingIds,
                         ReportStatus reportStatus,
                         LocalDateTime generatedAt) {
        this.title = title;
        this.markdownBody = markdownBody;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.llmProvider = llmProvider;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.credits = credits;
        this.reflectedFindingIds = reflectedFindingIds == null ? List.of() : List.copyOf(reflectedFindingIds);
        this.excludedFindingIds = excludedFindingIds == null ? List.of() : List.copyOf(excludedFindingIds);
        this.coverageRecorded = true;
        this.reportStatus = reportStatus;
        this.generatedAt = generatedAt;
    }
}
