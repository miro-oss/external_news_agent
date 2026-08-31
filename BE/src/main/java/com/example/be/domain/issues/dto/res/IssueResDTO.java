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
            "independentContentCount", "topicId", "topicName", "entities", "crossSource",
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
        private final Long representativeArticleId;
        private final List<Article> articles;
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
