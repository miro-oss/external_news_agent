package com.example.be.domain.issues.dto.res;

import com.example.be.domain.issues.entity.IssueCrossSource;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public class IssueResDTO {

    private IssueResDTO() {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "title", "summary", "status", "importanceScore", "sensitivityScore",
            "firstSeenAt", "lastSeenAt", "articleCount", "publisherCount",
            "independentContentCount", "topicId", "topicName", "entities", "crossSource", "toneDistribution",
            "representativeArticleId", "articles"
    })
    @Schema(name = "IssueDetailResponse", description = "같은 사건을 다룬 기사 묶음 상세")
    public static class Detail {

        private final Long id;
        private final String title;
        private final String summary;
        private final String status;
        private final BigDecimal importanceScore;
        private final BigDecimal sensitivityScore;
        private final OffsetDateTime firstSeenAt;
        private final OffsetDateTime lastSeenAt;
        private final int articleCount;
        private final int publisherCount;
        private final int independentContentCount;
        private final Long topicId;
        private final String topicName;
        private final List<String> entities;
        private final IssueCrossSource crossSource;
        private final ToneDistribution toneDistribution;
        private final Long representativeArticleId;
        private final List<Article> articles;
    }

    @Schema(name = "IssueToneDistribution", description = "견해 포함 기사의 전체 sentiment 분포. "
            + "기사별 최신 분석을 원문 중복군별 최신 1건으로 줄인 뒤, LLM/REUSED 분석의 근거·발화 주체가 "
            + "확인된 OPINION 포함 기사만 집계합니다. 개별 견해나 전체 매체의 비율은 아닙니다.")
    public record ToneDistribution(
            @Schema(description = "자체 최신 분석이 LLM/REUSED인 기사 수. 전재 중복 제거 전", example = "4")
            int analyzedArticleCount,
            @Schema(description = "집계 대상 독립 원문 수. 세 논조 건수의 합이며 비율의 분모", example = "3")
            int sampleCount,
            @Schema(description = "positive 기사 수", example = "2") int optimisticCount,
            @Schema(description = "neutral 기사 수", example = "0") int neutralCount,
            @Schema(description = "negative 기사 수", example = "1") int pessimisticCount,
            @Schema(description = "낙관 비율(0~100), 소수 둘째 자리 반올림. 표본 0건이면 null", example = "66.67", nullable = true)
            BigDecimal optimisticPercent,
            @Schema(description = "중립 비율(0~100), 소수 둘째 자리 반올림. 표본 0건이면 null", example = "0.00", nullable = true)
            BigDecimal neutralPercent,
            @Schema(description = "비관 비율(0~100), 소수 둘째 자리 반올림. 표본 0건이면 null", example = "33.33", nullable = true)
            BigDecimal pessimisticPercent
    ) {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "title", "publisher", "canonicalUrl", "publishedAt", "contentGroupId",
            "role", "stance", "stanceSource", "stanceConfidence", "joinedAt"
    })
    public static class Article {

        private final Long id;
        private final String title;
        private final String publisher;
        private final String canonicalUrl;
        private final OffsetDateTime publishedAt;
        private final Long contentGroupId;
        private final String role;
        private final String stance;
        private final String stanceSource;
        private final BigDecimal stanceConfidence;
        private final OffsetDateTime joinedAt;
    }
}
