package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_report_insights")
@Getter @Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsReportInsight {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "report_id", nullable = false) private Long reportId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Audience audience;
    @Column(name = "input_hash", nullable = false, length = 64) private String inputHash;
    @Column(name = "prompt_version", nullable = false, length = 50) private String promptVersion;
    @Column(name = "rubric_version", nullable = false, length = 50) private String rubricVersion;
    @JdbcTypeCode(SqlTypes.CLOB) @Column(name = "payload_json", nullable = false) private String payloadJson;
    @Column(name = "input_finding_count", nullable = false) private int inputFindingCount;
    @Column(name = "llm_provider", length = 30) private String llmProvider;
    @Column(name = "llm_model", length = 100) private String llmModel;
    @Column(name = "input_tokens") private Long inputTokens;
    @Column(name = "output_tokens") private Long outputTokens;
    @Column(name = "cost_usd", precision = 12, scale = 6) private BigDecimal costUsd;
    @Column(precision = 10, scale = 3) private BigDecimal credits;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
