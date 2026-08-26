package com.example.be.domain.topics.dto.res;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

public class TopicResDTO {

    private TopicResDTO() {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicCreateResponse", description = "수집 주제 등록 결과")
    public static class Created {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long id;

        @Schema(description = "주제명", example = "HBM")
        private final String name;

        @Schema(description = "검색어", example = "HBM 반도체")
        private final String queryText;

        @Schema(description = "AND 필터")
        private final List<String> requiredKeywords;

        @Schema(description = "OR 필터")
        private final List<String> optionalKeywords;

        @Schema(description = "NOT 필터")
        private final List<String> excludedKeywords;

        @Schema(description = "1회 수집 건수", example = "20")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분)", example = "60")
        private final int intervalMinutes;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;

        @Schema(description = "연결된 소스 목록")
        private final List<SourceBrief> sources;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicSummaryResponse", description = "수집 주제 목록 항목")
    public static class Summary {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long id;

        @Schema(description = "주제명", example = "HBM")
        private final String name;

        @Schema(description = "검색어", example = "HBM 반도체")
        private final String queryText;

        @Schema(description = "AND 필터")
        private final List<String> requiredKeywords;

        @Schema(description = "OR 필터")
        private final List<String> optionalKeywords;

        @Schema(description = "NOT 필터")
        private final List<String> excludedKeywords;

        @Schema(description = "1회 수집 건수", example = "20")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분)", example = "60")
        private final int intervalMinutes;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;

        @Schema(description = "연결된 소스 수", example = "4")
        private final int linkedSourceCount;

        @Schema(description = "마지막 수집 시각", example = "2026-08-10T08:00:00+09:00")
        private final OffsetDateTime lastCollectedAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicDetailResponse", description = "수집 주제 상세")
    public static class Detail {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long id;

        @Schema(description = "주제명", example = "HBM")
        private final String name;

        @Schema(description = "검색어", example = "HBM 반도체")
        private final String queryText;

        @Schema(description = "AND 필터")
        private final List<String> requiredKeywords;

        @Schema(description = "OR 필터")
        private final List<String> optionalKeywords;

        @Schema(description = "NOT 필터")
        private final List<String> excludedKeywords;

        @Schema(description = "1회 수집 건수", example = "20")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분)", example = "60")
        private final int intervalMinutes;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;

        @Schema(description = "마지막 수집 시각", example = "2026-08-10T08:00:00+09:00")
        private final OffsetDateTime lastCollectedAt;

        @Schema(description = "연결된 소스 목록")
        private final List<SourceDetail> sources;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicUpdateResponse", description = "수집 주제 수정 결과")
    public static class Updated {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long id;

        @Schema(description = "주제명", example = "HBM")
        private final String name;

        @Schema(description = "검색어", example = "HBM4 반도체")
        private final String queryText;

        @Schema(description = "AND 필터")
        private final List<String> requiredKeywords;

        @Schema(description = "OR 필터")
        private final List<String> optionalKeywords;

        @Schema(description = "NOT 필터")
        private final List<String> excludedKeywords;

        @Schema(description = "1회 수집 건수", example = "30")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분)", example = "30")
        private final int intervalMinutes;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicActivationResponse", description = "수집 주제 활성 토글 결과")
    public static class Activated {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long id;

        @Schema(description = "주제명", example = "HBM")
        private final String name;

        @Schema(description = "활성 여부", example = "false")
        private final boolean active;

        @Schema(description = "다음 예정 수집 시각. 비활성이면 null", example = "2026-08-10T09:00:00+09:00")
        private final OffsetDateTime nextScheduledAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicDeleteResponse", description = "수집 주제 삭제 결과")
    public static class Deleted {

        @Schema(description = "삭제된 수집 주제 ID", example = "1")
        private final Long id;

        @Schema(description = "삭제 시각", example = "2026-08-10T10:00:00+09:00")
        private final OffsetDateTime deletedAt;

        @Schema(description = "함께 해제된 소스 연결 수", example = "3")
        private final int unlinkedSourceCount;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicSourceLinkResponse", description = "주제-소스 연결 설정 결과")
    public static class SourcesLinked {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long topicId;

        @Schema(description = "교체 후 연결된 소스 목록")
        private final List<SourceBrief> sources;

        @Schema(description = "새로 연결된 소스 수", example = "1")
        private final int addedCount;

        @Schema(description = "연결이 해제된 소스 수", example = "0")
        private final int removedCount;

        @Schema(description = "이 주제가 만들어내는 (주제 × 소스) 조합 수", example = "4")
        private final int combinationCount;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicSourceBriefResponse", description = "주제에 연결된 소스 요약")
    public static class SourceBrief {

        @Schema(description = "소스 ID", example = "1")
        private final Long id;

        @Schema(description = "소스명", example = "ETNews 반도체")
        private final String name;

        @Schema(description = "소스 종류", example = "FEED", allowableValues = {"FEED", "SEARCH"})
        private final String sourceKind;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicSourceDetailResponse", description = "주제에 연결된 소스 상세")
    public static class SourceDetail {

        @Schema(description = "소스 ID", example = "1")
        private final Long id;

        @Schema(description = "소스명", example = "ETNews 반도체")
        private final String name;

        @Schema(description = "소스 종류", example = "FEED", allowableValues = {"FEED", "SEARCH"})
        private final String sourceKind;

        @Schema(description = "언어", example = "ko")
        private final String language;

        @Schema(description = "robots.txt 확인 상태", example = "allowed", allowableValues = {"allowed", "disallowed", "unknown"})
        private final String robotsStatus;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;
    }
}
