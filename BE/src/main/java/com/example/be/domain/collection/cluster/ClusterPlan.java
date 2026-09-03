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
            List<Long> mergedContentGroupIds,
            long representativeArticleId,
            String simhash,
            List<Long> articleIds
    ) {

        public ContentGroupAssignment {
            mergedContentGroupIds = List.copyOf(mergedContentGroupIds);
            articleIds = List.copyOf(articleIds);
        }

        public ContentGroupAssignment(Long existingContentGroupId,
                                      long representativeArticleId,
                                      String simhash,
                                      List<Long> articleIds) {
            this(existingContentGroupId, List.of(), representativeArticleId, simhash, articleIds);
        }
    }

    public record IssueAssignment(
            Long existingIssueId,
            List<Long> mergedIssueIds,
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
            mergedIssueIds = List.copyOf(mergedIssueIds);
            articleIds = List.copyOf(articleIds);
            entities = List.copyOf(entities);
        }

        public IssueAssignment(Long existingIssueId,
                               long topicId,
                               long representativeArticleId,
                               List<Long> articleIds,
                               List<String> entities,
                               OffsetDateTime firstSeenAt,
                               OffsetDateTime lastSeenAt,
                               int publisherCount,
                               int independentContentCount) {
            this(existingIssueId, List.of(), topicId, representativeArticleId, articleIds, entities,
                    firstSeenAt, lastSeenAt, publisherCount, independentContentCount);
        }
    }

    public record PairScore(
            long leftArticleId,
            long rightArticleId,
            long topicId,
            double titleJaccard,
            int entityOverlap,
            int organizationOverlap,
            boolean breakingPair,
            double hoursApart,
            boolean sameCluster
    ) {
    }
}
