package com.example.be.domain.reports.dto.res;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class ReportResDTO {

    private ReportResDTO() {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "runId", "title", "generatedAt", "modelName", "findingCount", "highRiskCount",
            "deliveryStatus"
    })
    @Schema(name = "ReportSummaryResponse", description = "보고서 목록 항목")
    public static class Summary {

        private final Long id;
        private final Long runId;
        private final String title;
        private final OffsetDateTime generatedAt;
        private final String modelName;
        private final long findingCount;
        private final long highRiskCount;
        private final String deliveryStatus;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "runId", "title", "markdownBody", "modelName", "generatedAt", "summaryStats", "findings"
    })
    @Schema(name = "ReportDetailResponse", description = "보고서 상세")
    public static class Detail {

        private final Long id;
        private final Long runId;
        private final String title;
        private final String markdownBody;
        private final String modelName;
        private final OffsetDateTime generatedAt;
        private final SummaryStats summaryStats;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final List<Finding> findings;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"findingCount", "newCount", "updatedCount", "byRiskLevel", "byCategory"})
    public static class SummaryStats {

        private final long findingCount;
        private final long newCount;
        private final long updatedCount;
        private final Map<String, Long> byRiskLevel;
        private final Map<String, Long> byCategory;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "articleId", "articleTitle", "canonicalUrl", "changeType", "summary", "keyPoints",
            "intent", "sentiment", "riskLevel", "relevance", "category"
    })
    public static class Finding {

        private final Long id;
        private final Long articleId;
        private final String articleTitle;
        private final String canonicalUrl;
        private final String changeType;
        private final String summary;
        private final List<KeyPoint> keyPoints;
        private final String intent;
        private final String sentiment;
        private final String riskLevel;
        private final String relevance;
        private final String category;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"text", "evidence", "groundedness"})
    @Schema(name = "ReportFindingKeyPoint", description = "보고서 핵심 주장과 기사 문장 근거")
    public static class KeyPoint {

        private final String text;

        @Schema(description = "기사 상세 sentences.index를 참조하는 0-based 문장 인덱스")
        private final List<Integer> evidence;

        @Schema(description = "근거 검증 상태", allowableValues = {"grounded", "weak"})
        private final String groundedness;
    }
}
