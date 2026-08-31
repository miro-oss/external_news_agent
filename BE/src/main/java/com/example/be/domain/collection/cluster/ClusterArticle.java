package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 트랜잭션 밖에서 클러스터링할 수 있도록 엔티티를 값으로 복사한 입력. 한 행은 기사×주제다. */
public record ClusterArticle(
        long articleId,
        long topicId,
        String title,
        String summary,
        String body,
        FetchStatus fetchStatus,
        long sourceId,
        String publisher,
        BigDecimal reliabilityScore,
        OffsetDateTime publishedAt,
        OffsetDateTime observedAt,
        List<String> topicKeywords,
        Long contentGroupId,
        String contentGroupSimhash,
        Long existingIssueId,
        boolean observedInRun
) {

    public ClusterArticle {
        topicKeywords = topicKeywords == null ? List.of() : List.copyOf(topicKeywords);
        observedAt = observedAt == null ? publishedAt : observedAt;
    }

    public boolean hasFullText() {
        return fetchStatus == FetchStatus.FULLTEXT && body != null && !body.isBlank();
    }

    public OffsetDateTime eventTime() {
        return publishedAt == null ? observedAt : publishedAt;
    }
}
