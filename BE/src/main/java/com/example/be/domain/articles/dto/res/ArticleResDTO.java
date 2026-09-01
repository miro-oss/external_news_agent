package com.example.be.domain.articles.dto.res;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

public class ArticleResDTO {

    private ArticleResDTO() {
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "title", "publisher", "canonicalUrl", "urlHash", "language", "publishedAt", "fetchedAt",
            "fetchStatus", "topicId", "topicName", "sourceId", "sourceName", "changeType", "summary",
            "category", "relevance", "riskLevel", "sentiment", "perspectiveTags"
    })
    @Schema(name = "ArticleSummaryResponse", description = "수집 기사 목록 항목")
    public static class Summary {

        private final Long id;
        private final String title;
        private final String publisher;
        private final String canonicalUrl;
        private final String urlHash;
        private final String language;
        private final OffsetDateTime publishedAt;
        private final OffsetDateTime fetchedAt;
        private final String fetchStatus;
        private final Long topicId;
        private final String topicName;
        private final Long sourceId;
        private final String sourceName;
        private final String changeType;
        private final String summary;
        private final String category;
        private final String relevance;
        private final String riskLevel;
        private final String sentiment;
        private final List<PerspectiveTag> perspectiveTags;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "id", "title", "publisher", "canonicalUrl", "language", "publishedAt", "fetchedAt", "fetchStatus",
            "topicId", "topicName", "sourceId", "sourceName", "bodyText", "sentences", "analysis",
            "analysisArticleId", "issueId", "relatedArticles"
    })
    @Schema(name = "ArticleDetailResponse", description = "수집 기사 상세")
    public static class Detail {

        private final Long id;
        private final String title;
        private final String publisher;
        private final String canonicalUrl;
        private final String language;
        private final OffsetDateTime publishedAt;
        private final OffsetDateTime fetchedAt;
        private final String fetchStatus;
        private final Long topicId;
        private final String topicName;
        private final Long sourceId;
        private final String sourceName;
        private final String bodyText;
        private final List<Sentence> sentences;
        private final Analysis analysis;
        private final Long analysisArticleId;
        private final Long issueId;
        private final List<RelatedArticle> relatedArticles;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"index", "text"})
    public static class Sentence {

        private final int index;
        private final String text;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "changeType", "summary", "keyPoints", "intent", "sentiment", "riskLevel", "relevance",
            "category", "perspectiveTags", "analyzedAt", "runId"
    })
    public static class Analysis {

        private final String changeType;
        private final String summary;
        private final List<KeyPoint> keyPoints;
        private final String intent;
        private final String sentiment;
        private final String riskLevel;
        private final String relevance;
        private final String category;
        private final List<PerspectiveTag> perspectiveTags;
        private final OffsetDateTime analyzedAt;
        private final Long runId;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({
            "text", "evidence", "groundedness", "groundingReason", "claimType", "attributedTo"
    })
    public static class KeyPoint {

        private final String text;
        private final List<Integer> evidence;
        private final String groundedness;

        @Schema(description = "근거 검증 또는 강등 이유", nullable = true)
        private final String groundingReason;

        @Schema(description = "주장 유형", allowableValues = {"FACT", "FORECAST", "OPINION"})
        private final String claimType;

        @Schema(description = "OPINION 발화 주체. 다른 주장 유형은 null", nullable = true)
        private final String attributedTo;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"audience", "relevance", "hook", "evidenceSentenceIds"})
    public static class PerspectiveTag {

        private final String audience;
        private final String relevance;
        private final String hook;
        private final List<Integer> evidenceSentenceIds;
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"id", "title", "publisher"})
    public static class RelatedArticle {

        private final Long id;
        private final String title;
        private final String publisher;
    }
}
