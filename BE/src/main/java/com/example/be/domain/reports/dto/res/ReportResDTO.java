package com.example.be.domain.reports.dto.res;

import com.example.be.domain.analysis.dto.res.SensitivityResDTO;
import com.example.be.domain.reports.entity.ReportScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class ReportResDTO {

    private ReportResDTO() {
    }

    @Schema(name = "ReportDeletedResponse", description = "보고서 삭제 결과. 원문과 발송 이력은 보존")
    public record Deleted(Long id, boolean deleted) {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "runId", "reportScope", "reportDate", "sourceRunIds", "sourceReportCount", "title", "generatedAt", "collectionStartedAt", "collectionContexts", "modelName", "findingCount", "highSensitivityCount",
            "deliveryStatus"
    })
    @Schema(name = "ReportSummaryResponse", description = "보고서 목록 항목")
    public static class Summary {

        private final Long id;
        @Schema(description = "RUN 수집 실행 ID. DAILY는 null", nullable = true)
        private final Long runId;
        private final ReportScope reportScope;
        @Schema(description = "DAILY 집계일 (Asia/Seoul). RUN은 null", nullable = true)
        private final LocalDate reportDate;
        private final List<Long> sourceRunIds;
        @Schema(description = "DAILY 생성 시점에 sourceRunIds에 대해 생성 완료된 RUN 보고서 수. 이후 삭제해도 유지. RUN은 null", nullable = true, minimum = "0")
        private final Long sourceReportCount;
        @Schema(description = "수집 시점 주제명과 생성 시각(RUN) 또는 집계일(DAILY)을 포함한 제목. 스냅샷이 없는 이전 보고서는 저장된 기존 제목을 유지", example = "HBM 시장 · 2026-09-08 10:00 리포트")
        private final String title;
        private final OffsetDateTime generatedAt;
        @Schema(description = "RUN 원본 수집 실행의 시작 시각 (Asia/Seoul). DAILY 또는 시작 시각 기록이 없으면 null이며 생성 시각으로 대체하지 않음", nullable = true, example = "2026-09-08T10:00:00+09:00")
        private final OffsetDateTime collectionStartedAt;
        @Schema(description = "보고서 상세와 동일한 저장된 실행별 수집 조건. 현재 주제 설정에서 복원하지 않으며 기록이 없는 이전 보고서는 []")
        private final List<ReportCollectionContext> collectionContexts;
        private final String modelName;
        private final long findingCount;
        private final long highSensitivityCount;
        private final String deliveryStatus;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "runId", "reportScope", "reportDate", "sourceRunIds", "sourceReportCount", "title", "markdownBody", "modelName", "promptVersion", "llmProvider",
            "generatedAt", "structuredContent", "collectionContexts", "articleStats", "summaryStats", "findings"
    })
    @Schema(name = "ReportDetailResponse", description = "보고서 상세")
    public static class Detail {

        private final Long id;
        @Schema(description = "RUN 수집 실행 ID. DAILY는 null", nullable = true)
        private final Long runId;
        private final ReportScope reportScope;
        @Schema(description = "DAILY 집계일 (Asia/Seoul). RUN은 null", nullable = true)
        private final LocalDate reportDate;
        private final List<Long> sourceRunIds;
        @Schema(description = "DAILY 생성 시점에 sourceRunIds에 대해 생성 완료된 RUN 보고서 수. 이후 삭제해도 유지. RUN은 null", nullable = true, minimum = "0")
        private final Long sourceReportCount;
        @Schema(description = "수집 시점 주제명과 생성 시각(RUN) 또는 집계일(DAILY)을 포함한 제목. 스냅샷이 없는 이전 보고서는 저장된 기존 제목을 유지", example = "HBM 시장 · 2026-09-08 10:00 리포트")
        private final String title;
        @Schema(description = "원본 Markdown 보고서. structuredContent가 null인 이전 보고서의 본문 렌더링에도 사용")
        private final String markdownBody;
        private final String modelName;
        private final String promptVersion;
        private final String llmProvider;
        private final OffsetDateTime generatedAt;
        private final SummaryStats summaryStats;
        @Schema(description = "화면과 알림이 함께 사용하는 저장된 핵심 요약·중요 이벤트·관찰 항목·수집 참고. 구조화 저장 이전 보고서는 null이며 markdownBody로 표시. includeFindings와 관계없이 반환", nullable = true)
        private final ReportContent structuredContent;
        @Schema(description = "보고서에 포함된 실행별 접수 시점 수집 조건. 이후 주제를 편집해도 변경되지 않음. 이전 보고서 자체에 저장된 문맥이 없으면 [], 새 보고서가 스냅샷 없는 이전 실행을 참조하면 해당 topics가 []. includeFindings와 관계없이 반환", requiredMode = Schema.RequiredMode.REQUIRED)
        private final List<ReportCollectionContext> collectionContexts;
        @Schema(description = "RUN은 runId, DAILY는 sourceRunIds 전체에서 관측한 고유 articleId 집계. 동일 기사의 소스·실행 중복을 제거하며 분석 결과 수인 summaryStats와 다름. includeFindings=false에도 반환. 관측 데이터가 없으면 모든 수가 0", requiredMode = Schema.RequiredMode.REQUIRED)
        private final ArticleStats articleStats;

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
    @Schema(name = "ReportArticleStatistics", description = "보고서 범위의 중복 제거 기사 수. totalCount = newCount + existingCount")
    public static class ArticleStats {
        @Schema(description = "보고서 범위에서 관측한 고유 articleId 수. 분석 포함 여부와 무관", minimum = "0", example = "12")
        private final long totalCount;
        @Schema(description = "범위 내 관측 중 하나라도 NEW인 고유 기사 수. 같은 기사에 NEW와 UPDATED/UNCHANGED가 함께 있으면 신규로 한 번만 집계", minimum = "0", example = "4")
        private final long newCount;
        @Schema(description = "NEW 관측 없이 UPDATED 또는 UNCHANGED로 관측된 고유 기사 수. totalCount에서 newCount를 뺀 값", minimum = "0", example = "8")
        private final long existingCount;
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
        @Schema(description = "근거 분석의 수집 실행 ID. 기사 상세 조회 시 사용")
        private final Long runId;
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
