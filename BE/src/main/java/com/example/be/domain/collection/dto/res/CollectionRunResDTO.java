package com.example.be.domain.collection.dto.res;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

public class CollectionRunResDTO {

    private CollectionRunResDTO() {
    }

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "runId", "status", "triggerType", "idempotencyKey",
            "targetTopicIds", "targetCombinationCount", "startedAt"
    })
    @Schema(name = "CollectionRunCreateResponse", description = "수동 수집 실행 시작 결과")
    public static class Created {

        @Schema(description = "수집 실행 ID", example = "42")
        private final Long runId;

        @Schema(description = "실행 상태", example = "RUNNING")
        private final String status;

        @Schema(description = "실행 트리거", example = "MANUAL")
        private final String triggerType;

        @Schema(description = "중복 실행 방지 키", example = "2026-08-10-manual-001")
        private final String idempotencyKey;

        @Schema(description = "대상 주제 ID 목록", example = "[1, 2]")
        private final List<Long> targetTopicIds;

        @Schema(description = "대상 주제 × 소스 조합 수", example = "6")
        private final Integer targetCombinationCount;

        @Schema(description = "시작 시각", example = "2026-08-10T10:00:00+09:00")
        private final OffsetDateTime startedAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "runId", "status", "triggerType", "startedAt", "finishedAt",
            "scannedCount", "newCount", "updatedCount", "skippedCount", "warningCount", "reportId"
    })
    @Schema(name = "CollectionRunSummaryResponse", description = "수집 실행 내역 목록 항목")
    public static class Summary {

        @Schema(description = "수집 실행 ID", example = "42")
        private final Long runId;

        @Schema(description = "실행 상태", example = "SUCCESS")
        private final String status;

        @Schema(description = "실행 트리거", example = "MANUAL")
        private final String triggerType;

        @Schema(description = "시작 시각", example = "2026-08-10T10:00:00+09:00")
        private final OffsetDateTime startedAt;

        @Schema(description = "종료 시각", example = "2026-08-10T10:03:12+09:00")
        private final OffsetDateTime finishedAt;

        @Schema(description = "훑은 기사 수", example = "128")
        private final int scannedCount;

        @Schema(description = "신규 기사 수", example = "14")
        private final int newCount;

        @Schema(description = "수정 기사 수", example = "3")
        private final int updatedCount;

        @Schema(description = "건너뛴 기사 수", example = "111")
        private final int skippedCount;

        @Schema(description = "경고 수", example = "1")
        private final int warningCount;

        @Schema(description = "보고서 ID. 보고서 생성 전이면 null", example = "17")
        private final Long reportId;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "runId", "status", "triggerType", "idempotencyKey", "startedAt", "finishedAt",
            "scannedCount", "newCount", "updatedCount", "skippedCount", "reportId", "breakdown", "warnings"
    })
    @Schema(name = "CollectionRunDetailResponse", description = "수집 실행 상세")
    public static class Detail {

        @Schema(description = "수집 실행 ID", example = "42")
        private final Long runId;

        @Schema(description = "실행 상태", example = "PARTIAL")
        private final String status;

        @Schema(description = "실행 트리거", example = "MANUAL")
        private final String triggerType;

        @Schema(description = "중복 실행 방지 키", example = "2026-08-10-manual-001")
        private final String idempotencyKey;

        @Schema(description = "시작 시각", example = "2026-08-10T10:00:00+09:00")
        private final OffsetDateTime startedAt;

        @Schema(description = "종료 시각", example = "2026-08-10T10:03:12+09:00")
        private final OffsetDateTime finishedAt;

        @Schema(description = "훑은 기사 수", example = "128")
        private final int scannedCount;

        @Schema(description = "신규 기사 수", example = "14")
        private final int newCount;

        @Schema(description = "수정 기사 수", example = "3")
        private final int updatedCount;

        @Schema(description = "건너뛴 기사 수", example = "111")
        private final int skippedCount;

        @Schema(description = "보고서 ID. 보고서 생성 전이면 null", example = "17")
        private final Long reportId;

        @Schema(description = "조합별 수집 결과")
        private final List<Breakdown> breakdown;

        @Schema(description = "수집 실행 경고 목록")
        private final List<Warning> warnings;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "topicId", "topicName", "sourceId", "sourceName",
            "scannedCount", "newCount", "updatedCount", "status"
    })
    @Schema(name = "CollectionRunBreakdownResponse", description = "조합별 수집 결과")
    public static class Breakdown {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long topicId;

        @Schema(description = "수집 주제명", example = "HBM")
        private final String topicName;

        @Schema(description = "수집 소스 ID", example = "2")
        private final Long sourceId;

        @Schema(description = "수집 소스명", example = "Google News RSS")
        private final String sourceName;

        @Schema(description = "훑은 기사 수", example = "50")
        private final int scannedCount;

        @Schema(description = "신규 기사 수", example = "9")
        private final int newCount;

        @Schema(description = "수정 기사 수", example = "2")
        private final int updatedCount;

        @Schema(description = "조합 수집 상태", example = "SUCCESS")
        private final String status;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"sourceId", "sourceName", "code", "message", "articleCount", "occurredAt"})
    @Schema(name = "CollectionRunWarningResponse", description = "수집 실행 경고")
    public static class Warning {

        @Schema(description = "수집 소스 ID", example = "6")
        private final Long sourceId;

        @Schema(description = "수집 소스명", example = "Reuters Technology")
        private final String sourceName;

        @Schema(description = "경고 코드", example = "FULLTEXT_BLOCKED")
        private final String code;

        @Schema(description = "경고 메시지", example = "페이월로 전문을 가져오지 못했습니다.")
        private final String message;

        @Schema(description = "경고와 관련된 기사 수", example = "5")
        private final int articleCount;

        @Schema(description = "경고 발생 시각", example = "2026-08-10T10:02:05+09:00")
        private final OffsetDateTime occurredAt;
    }
}
