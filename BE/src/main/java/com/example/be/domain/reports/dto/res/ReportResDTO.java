package com.example.be.domain.reports.dto.res;

import com.example.be.domain.analysis.dto.res.SensitivityResDTO;
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
            "id", "runId", "title", "generatedAt", "modelName", "findingCount", "highSensitivityCount",
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
        private final long highSensitivityCount;
        private final String deliveryStatus;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "runId", "title", "markdownBody", "modelName", "promptVersion", "llmProvider",
            "generatedAt", "summaryStats", "findings"
    })
    @Schema(name = "ReportDetailResponse", description = "보고서 상세")
    public static class Detail {

        private final Long id;
        private final Long runId;
        private final String title;
        private final String markdownBody;
        private final String modelName;
        private final String promptVersion;
        private final String llmProvider;
        private final OffsetDateTime generatedAt;
        private final SummaryStats summaryStats;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final List<Finding> findings;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"findingCount", "newCount", "updatedCount", "bySensitivityLevel", "byCategory"})
    public static class SummaryStats {

        private final long findingCount;
        private final long newCount;
        private final long updatedCount;
        private final Map<String, Long> bySensitivityLevel;
        private final Map<String, Long> byCategory;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "articleId", "issueId", "issue", "articleTitle", "canonicalUrl", "changeType", "summary",
            "keyPoints", "intent", "sentiment", "sensitivity", "relevance", "category", "perspectiveTags",
            "investigation"
    })
    public static class Finding {

        private final Long id;
        private final Long articleId;
        private final Long issueId;
        private final IssueSummary issue;
        private final String articleTitle;
        private final String canonicalUrl;
        private final String changeType;
        private final String summary;
        private final List<KeyPoint> keyPoints;
        private final String intent;
        private final String sentiment;
        private final SensitivityResDTO sensitivity;
        private final String relevance;
        private final String category;
        private final List<PerspectiveTag> perspectiveTags;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private final Investigation investigation;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "status", "stepCount", "addedArticleCount", "addedEvidenceCount", "reason", "rejectionReason"
    })
    @Schema(name = "ReportFindingInvestigation", description = "해당 이슈의 추가 조사 실행 trace")
    public static class Investigation {

        @Schema(allowableValues = {
                "CONCLUDED", "NO_NEW_EVIDENCE", "MAX_STEPS", "BUDGET_LIMIT", "REJECTED", "FAILED"
        })
        private final String status;
        private final int stepCount;
        private final int addedArticleCount;
        private final int addedEvidenceCount;
        private final String reason;
        private final String rejectionReason;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "title", "summary", "lastSeenAt", "articleCount", "publisherCount",
            "independentContentCount", "topicName", "entities"
    })
    @Schema(name = "ReportFindingIssueSummary", description = "접힌 이슈 카드에 필요한 이슈 요약")
    public static class IssueSummary {

        private final Long id;
        private final String title;
        private final String summary;
        private final OffsetDateTime lastSeenAt;
        private final int articleCount;
        private final int publisherCount;
        private final int independentContentCount;
        private final String topicName;
        private final List<String> entities;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"audience", "relevance", "hook", "evidenceSentenceIds"})
    @Schema(name = "ReportFindingPerspectiveTag", description = "추가 생성 없이 화면 정렬과 강조에 쓰는 독자 관점")
    public static class PerspectiveTag {

        private final String audience;
        private final String relevance;
        private final String hook;
        private final List<Integer> evidenceSentenceIds;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "text", "evidence", "groundedness", "groundingReason", "claimType", "attributedTo"
    })
    @Schema(name = "ReportFindingKeyPoint", description = "보고서 핵심 주장과 기사 문장 근거")
    public static class KeyPoint {

        private final String text;

        @Schema(description = "기사 상세 sentences.index를 참조하는 0-based 문장 인덱스")
        private final List<Integer> evidence;

        @Schema(description = "근거 검증 상태", allowableValues = {"grounded", "weak"})
        private final String groundedness;

        @Schema(description = "근거 검증 또는 강등 이유", nullable = true)
        private final String groundingReason;

        @Schema(description = "주장 유형", allowableValues = {"FACT", "FORECAST", "OPINION"})
        private final String claimType;

        @Schema(description = "OPINION 발화 주체. 다른 주장 유형은 null", nullable = true)
        private final String attributedTo;
    }
}
