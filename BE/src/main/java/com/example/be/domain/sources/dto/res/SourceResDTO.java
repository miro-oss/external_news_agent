package com.example.be.domain.sources.dto.res;

import com.example.be.domain.sources.entity.CrawlPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
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

        @Schema(description = "마지막 수집 시각. 수집 실행 이력이 생기는 M3까지는 null",
                example = "2026-08-10T08:00:00+09:00")
        private final OffsetDateTime lastCollectedAt;

        @Schema(description = "마지막 수집 결과. 수집 실행 이력이 생기는 M3까지는 null", example = "SUCCESS")
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

        @Schema(description = "삭제 처리 시각", example = "2026-08-10T10:00:00+09:00")
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
}
