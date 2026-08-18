package com.example.be.domain.sources.dto.res;

import com.example.be.domain.sources.entity.CrawlPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public class SourceResDTO {

    private SourceResDTO() {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "SourceCreateResponse", description = "수집 소스 등록 결과")
    public static class Created {

        @Schema(description = "수집 소스 ID", example = "7")
        private final Long id;

        @Schema(description = "소스 종류", example = "SEARCH", allowableValues = {"FEED", "SEARCH"})
        private final String sourceKind;

        @Schema(description = "소스명", example = "Naver 뉴스 검색")
        private final String name;

        @Schema(description = "URL 템플릿 또는 provider 키", example = "NAVER")
        private final String urlTemplate;

        @Schema(description = "국가 코드", example = "KR")
        private final String country;

        @Schema(description = "언어 코드", example = "ko")
        private final String language;

        @Schema(description = "수집 정책")
        private final CrawlPolicy crawlPolicy;

        @Schema(description = "robots.txt 확인 상태. 등록 직후에는 항상 unknown", example = "unknown",
                allowableValues = {"allowed", "disallowed", "unknown"})
        private final String robotsStatus;

        @Schema(description = "robots.txt 확인 시각", example = "2026-08-10T09:00:00+09:00")
        private final OffsetDateTime robotsCheckedAt;

        @Schema(description = "소스 신뢰도", example = "0.9")
        private final BigDecimal reliabilityScore;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "SourceSummaryResponse", description = "수집 소스 목록 항목")
    public static class Summary {

        @Schema(description = "수집 소스 ID", example = "1")
        private final Long id;

        @Schema(description = "소스 종류", example = "FEED", allowableValues = {"FEED", "SEARCH"})
        private final String sourceKind;

        @Schema(description = "소스명", example = "ETNews 반도체")
        private final String name;

        @Schema(description = "URL 템플릿 또는 provider 키", example = "https://rss.etnews.com/Section902.xml")
        private final String urlTemplate;

        @Schema(description = "국가 코드", example = "KR")
        private final String country;

        @Schema(description = "언어 코드", example = "ko")
        private final String language;

        @Schema(description = "수집 정책")
        private final CrawlPolicy crawlPolicy;

        @Schema(description = "robots.txt 확인 상태", example = "allowed",
                allowableValues = {"allowed", "disallowed", "unknown"})
        private final String robotsStatus;

        @Schema(description = "robots.txt 확인 시각", example = "2026-08-10T09:00:00+09:00")
        private final OffsetDateTime robotsCheckedAt;

        @Schema(description = "소스 신뢰도", example = "0.85")
        private final BigDecimal reliabilityScore;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;

        @Schema(description = "연결된 주제 수", example = "3")
        private final int linkedTopicCount;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "SourceDetailResponse", description = "수집 소스 상세")
    public static class Detail {

        @Schema(description = "수집 소스 ID", example = "1")
        private final Long id;

        @Schema(description = "소스 종류", example = "FEED", allowableValues = {"FEED", "SEARCH"})
        private final String sourceKind;

        @Schema(description = "소스명", example = "ETNews 반도체")
        private final String name;

        @Schema(description = "URL 템플릿 또는 provider 키", example = "https://rss.etnews.com/Section902.xml")
        private final String urlTemplate;

        @Schema(description = "국가 코드", example = "KR")
        private final String country;

        @Schema(description = "언어 코드", example = "ko")
        private final String language;

        @Schema(description = "수집 정책")
        private final CrawlPolicy crawlPolicy;

        @Schema(description = "robots.txt 확인 상태", example = "allowed",
                allowableValues = {"allowed", "disallowed", "unknown"})
        private final String robotsStatus;

        @Schema(description = "robots.txt 확인 시각", example = "2026-08-10T09:00:00+09:00")
        private final OffsetDateTime robotsCheckedAt;

        @Schema(description = "소스 신뢰도", example = "0.85")
        private final BigDecimal reliabilityScore;

        @Schema(description = "활성 여부", example = "true")
        private final boolean active;

        @Schema(description = "이 소스가 연결된 주제 목록")
        private final List<TopicBrief> linkedTopics;

        @Schema(description = "마지막 수집 시작 시각. 실행 이력이 없으면 null",
                example = "2026-08-10T08:00:00+09:00")
        private final OffsetDateTime lastCollectedAt;

        @Schema(description = "마지막 수집 실행 상태. 실행 이력이 없으면 null", example = "SUCCESS",
                allowableValues = {"PENDING", "RUNNING", "SUCCESS", "PARTIAL", "FAILED"})
        private final String lastRunStatus;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "SourceUpdateResponse", description = "수집 소스 수정 결과")
    public static class Updated {

        @Schema(description = "수집 소스 ID", example = "1")
        private final Long id;

        @Schema(description = "소스 종류", example = "FEED", allowableValues = {"FEED", "SEARCH"})
        private final String sourceKind;

        @Schema(description = "소스명", example = "ETNews 반도체 (개편)")
        private final String name;

        @Schema(description = "URL 템플릿 또는 provider 키", example = "https://rss.etnews.com/Section902.xml")
        private final String urlTemplate;

        @Schema(description = "국가 코드", example = "KR")
        private final String country;

        @Schema(description = "언어 코드", example = "ko")
        private final String language;

        @Schema(description = "수집 정책")
        private final CrawlPolicy crawlPolicy;

        @Schema(description = "robots.txt 확인 상태", example = "allowed",
                allowableValues = {"allowed", "disallowed", "unknown"})
        private final String robotsStatus;

        @Schema(description = "robots.txt 확인 시각", example = "2026-08-10T09:00:00+09:00")
        private final OffsetDateTime robotsCheckedAt;

        @Schema(description = "소스 신뢰도", example = "0.85")
        private final BigDecimal reliabilityScore;

        @Schema(description = "활성 여부", example = "false")
        private final boolean active;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "SourceDeleteResponse",
            description = "수집 소스 삭제 결과. 기사 이력이 소스를 참조하므로 레코드는 남기고 비활성화한다")
    public static class Deleted {

        @Schema(description = "삭제된 수집 소스 ID", example = "1")
        private final Long id;

        @Schema(description = "활성 여부. 항상 false", example = "false")
        private final boolean active;

        @Schema(description = """
                삭제를 처리한 응답 시각이다. 소스가 언제 비활성이 됐는지를 나타내는 값이 아니다.
                news_sources에는 비활성 시각 컬럼이 없어서(plan-final §3-1) 이미 비활성인 소스에 다시 요청하면 매번 새 시각이 나온다.
                비활성 시점을 추적해야 하면 컬럼을 추가해야 한다.
                """, example = "2026-08-10T10:00:00+09:00")
        private final OffsetDateTime deletedAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "SourceTopicBriefResponse", description = "소스에 연결된 주제 요약")
    public static class TopicBrief {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long id;

        @Schema(description = "주제명", example = "HBM")
        private final String name;
    }

    @Getter
    @Builder
    @Schema(name = "SourceRobotsChecked", description = "robots.txt 재확인 결과")
    public static class RobotsChecked {

        @Schema(description = "수집 소스 ID", example = "1")
        private Long sourceId;

        @Schema(description = "수집 허용 여부", example = "allowed",
                allowableValues = {"allowed", "disallowed", "unknown"})
        private String robotsStatus;

        @Schema(description = "확인 시각", example = "2026-08-10T10:05:00+09:00")
        private LocalDateTime robotsCheckedAt;

        @Schema(description = "robots.txt가 요구한 요청 간격(초). 없으면 null", example = "5")
        private Long crawlDelaySeconds;

        @Schema(description = "조회한 robots.txt 주소", example = "https://www.etnews.com/robots.txt")
        private String robotsTxtUrl;
    }

    @Getter
    @Builder
    @Schema(name = "SourceRobotsCheckFailed", description = "robots.txt 확인 실패")
    public static class RobotsCheckFailed {

        @Schema(description = "수집 소스 ID", example = "1")
        private Long sourceId;

        @Schema(description = "저장된 상태. 확인하지 못하면 unknown으로 둡니다", example = "unknown")
        private String robotsStatus;

        @Schema(description = "실패 사유", example = "CONNECT_TIMEOUT")
        private String reason;
    }
}
