package com.example.be.domain.topics.dto.res;

import io.swagger.v3.oas.annotations.media.Schema;
import com.example.be.domain.dart.model.DartModels;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
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

        @Schema(description = "선택한 공시 회사. 미연결이면 null", nullable = true)
        private final DartModels.Company dartCompany;

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

        @Schema(description = "1회 수집 건수", example = "100")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분). 0이면 정기 수집 안 함", example = "60")
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

        @Schema(description = "선택한 공시 회사. 미연결이면 null", nullable = true)
        private final DartModels.Company dartCompany;

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

        @Schema(description = "1회 수집 건수", example = "100")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분). 0이면 정기 수집 안 함", example = "60")
        private final int intervalMinutes;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;

        @Schema(description = "연결된 소스 수", example = "4")
        private final int linkedSourceCount;

        @Schema(description = "마지막 수집 시각", example = "2026-08-10T08:00:00+09:00")
        private final OffsetDateTime lastCollectedAt;

        @Schema(description = "최근 7일 기준 급상승 키워드")
        private final List<KeywordTrend> surgeKeywords;

        @Schema(description = "최근 7일 기준 연관 키워드")
        private final List<RelatedKeyword> relatedKeywords;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicDetailResponse", description = "수집 주제 상세")
    public static class Detail {

        @Schema(description = "선택한 공시 회사. 미연결이면 null", nullable = true)
        private final DartModels.Company dartCompany;

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

        @Schema(description = "1회 수집 건수", example = "100")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분). 0이면 정기 수집 안 함", example = "60")
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

        @Schema(description = "선택한 공시 회사. 미연결이면 null", nullable = true)
        private final DartModels.Company dartCompany;

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

        @Schema(description = "1회 수집 건수", example = "300")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분). 0이면 정기 수집 안 함", example = "720")
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

        @Schema(description = "다음 예정 수집 시각. 비활성이거나 정기 수집을 끄면 null", example = "2026-08-10T09:00:00+09:00")
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

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicKeywordTrendResponse", description = "주제별 직전 7일 대비 기사 수 증가 키워드")
    public static class KeywordTrend {

        @Schema(description = "키워드", example = "HBM4")
        private final String keyword;

        @Schema(description = "최근 168시간 수집 기사 중 이 키워드가 확인된 고유 기사 수", example = "4")
        private final int articleCount;

        @Schema(description = "직전 168시간 수집 기사 중 이 키워드가 확인된 고유 기사 수", example = "1")
        private final int previousArticleCount;

        @Schema(description = "최근 168시간 기사 수와 직전 168시간 기사 수의 차이", example = "3")
        private final int deltaArticleCount;

        @Schema(description = "직전 7개 24시간 구간의 기사 수 분포 대비 최근 7개 구간 peak의 z-score. 분산이 0이면 null", example = "2.87")
        private final BigDecimal zScore;

        @Schema(description = "z-score 2.5 이상인 급상승 여부", example = "true")
        private final boolean burst;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicRelatedKeywordResponse", description = "주제별 최근 7일 연관 키워드")
    public static class RelatedKeyword {

        @Schema(description = "키워드", example = "마이크론")
        private final String keyword;

        @Schema(description = "최근 168시간 수집 기사 중 이 키워드가 확인된 고유 기사 수", example = "3")
        private final int articleCount;

        @Schema(description = "최근 168시간 해당 주제의 본문 확보 수집 기사 중 이 키워드가 확인된 비율(%)", example = "60.00")
        private final BigDecimal sharePercent;
    }
}
