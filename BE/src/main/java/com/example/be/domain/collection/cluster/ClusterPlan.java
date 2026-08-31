package com.example.be.domain.collection.cluster;

import java.time.OffsetDateTime;
import java.util.List;

public record ClusterPlan(
        List<ContentGroupAssignment> contentGroups,
        List<IssueAssignment> issues,
        List<PairScore> pairScores
) {

    public ClusterPlan {
        contentGroups = List.copyOf(contentGroups);
        issues = List.copyOf(issues);
        pairScores = List.copyOf(pairScores);
    }

    public record ContentGroupAssignment(
            Long existingContentGroupId,
            long representativeArticleId,
            String simhash,
            List<Long> articleIds
    ) {

        public ContentGroupAssignment {
            articleIds = List.copyOf(articleIds);
        }
    }

    public record IssueAssignment(
            Long existingIssueId,
            long topicId,
            long representativeArticleId,
            List<Long> articleIds,
            List<String> entities,
            OffsetDateTime firstSeenAt,
            OffsetDateTime lastSeenAt,
            int publisherCount,
            int independentContentCount
    ) {

        public IssueAssignment {
            articleIds = List.copyOf(articleIds);
            entities = List.copyOf(entities);
        }
    }

    public record PairScore(
            long leftArticleId,
            long rightArticleId,
            long topicId,
            double titleJaccard,
            int entityOverlap,
            double hoursApart,
            boolean sameCluster
    ) {
    }
}
