package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IssueClustererIncrementalTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-10-07T07:00:00+09:00");
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void comparesChangedEvidenceWithHistoryAndWritesOnlyItsConnectedIssue() {
        List<ClusterArticle> input = List.of(
                article(1, "orchid cedar maple discovery", 10L),
                article(2, "orchid cedar maple discovery", 10L),
                article(3, "orchid cedar maple discovery", null),
                article(4, "comet galaxy telescope observation", 20L));

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(3L), true);
        ClusterPlan full = clusterer.cluster(input, true);

        assertEquals(full.issues().stream().filter(issue -> issue.articleIds().contains(3L)).toList(),
                incremental.issues());
        assertEquals(List.of(1L, 2L, 3L), incremental.issues().getFirst().articleIds());
        assertEquals(List.of("1:3", "2:3", "3:4"), incremental.pairScores().stream()
                .map(pair -> pair.leftArticleId() + ":" + pair.rightArticleId()).toList());
        assertTrue(incremental.issues().stream().noneMatch(issue -> issue.articleIds().contains(4L)));
    }

    @Test
    void changingAnExistingMemberIncludesEverySavedMemberInComparisonAndMergeGuards() {
        List<ClusterArticle> input = List.of(
                article(1, "orchid cedar maple discovery", 10L),
                article(2, "comet galaxy telescope observation", 10L),
                article(3, "comet galaxy telescope observation", 20L));

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(1L), true);

        assertEquals(clusterer.cluster(input).issues(), incremental.issues());
        assertTrue(incremental.pairScores().stream()
                .anyMatch(pair -> pair.leftArticleId() == 2 && pair.rightArticleId() == 3));
        assertEquals(List.of(20L), incremental.issues().getFirst().mergedIssueIds());
    }

    @Test
    void updatedRepresentativeWhoseBodyFetchFailedStillRefreshesItsSavedIssue() {
        ClusterArticle failed = copy(article(1, "orchid cedar maple discovery", 10L),
                1, null, 10L, FetchStatus.FETCH_FAILED, "old body", "0.99");
        List<ClusterArticle> input = List.of(failed,
                article(2, "orchid cedar maple discovery", 10L),
                article(3, "comet galaxy telescope observation", 20L));

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(1L));

        assertEquals(1, incremental.issues().size());
        assertEquals(2L, incremental.issues().getFirst().representativeArticleId());
        assertEquals(List.of(1L, 2L), incremental.issues().getFirst().articleIds());
    }

    @Test
    void aChangedNonRepresentativeActivatesTheHistoricalContentRepresentative() {
        String body = "A laboratory documented a controlled experiment with detailed independent measurements. ".repeat(8);
        ClusterArticle old = copy(article(1, "orchid cedar maple discovery", 10L),
                1, 80L, 10L, FetchStatus.FULLTEXT, body, "0.9");
        ClusterArticle dirty = copy(article(2, "orchid cedar maple discovery", null),
                1, null, null, FetchStatus.FULLTEXT, body, "0.8");
        ClusterArticle historicalFollowup = article(3, "orchid cedar maple discovery", 20L);
        List<ClusterArticle> input = List.of(old, dirty, historicalFollowup);

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(2L), true);

        assertEquals(clusterer.cluster(input).issues(), incremental.issues());
        assertEquals(List.of(1L, 2L, 3L), incremental.issues().getFirst().articleIds());
        assertEquals(1L, incremental.contentGroups().getFirst().representativeArticleId());
        assertTrue(incremental.pairScores().stream()
                .anyMatch(pair -> pair.leftArticleId() == 1 && pair.rightArticleId() == 3));
    }

    @Test
    void changingAGlobalContentRepresentativeUpdatesOtherTopicProxies() {
        String body = "A laboratory documented a controlled experiment with detailed independent measurements. ".repeat(8);
        ClusterArticle firstTopic = copy(article(1, "orchid cedar maple discovery", 10L),
                1, 80L, 10L, FetchStatus.FULLTEXT, body, "0.8");
        ClusterArticle otherTopic = copy(article(2, "orchid cedar maple discovery", 20L),
                2, 80L, 20L, FetchStatus.FULLTEXT, body, "0.8");
        ClusterArticle changed = copy(article(3, "orchid cedar maple discovery", null),
                1, null, null, FetchStatus.FULLTEXT, body, "0.99");
        List<ClusterArticle> input = List.of(firstTopic, otherTopic, changed);

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(3L));

        assertEquals(clusterer.cluster(input), incremental);
        assertEquals(3L, incremental.contentGroups().getFirst().representativeArticleId());
        ClusterPlan.IssueAssignment other = incremental.issues().stream()
                .filter(issue -> issue.topicId() == 2).findFirst().orElseThrow();
        assertEquals(3L, other.representativeArticleId());
        assertEquals(List.of(2L, 3L), other.articleIds());
    }

    @Test
    void savedDuplicateGroupsRemainIntactWithoutWritingUnrelatedHistory() {
        String body = "A laboratory documented a controlled experiment with detailed independent measurements. ".repeat(8);
        List<ClusterArticle> input = List.of(
                copy(article(1, "orchid cedar maple discovery", 10L), 1, 80L, 10L, FetchStatus.FULLTEXT, body, "0.8"),
                copy(article(2, "orchid cedar maple discovery", 10L), 1, 80L, 10L, FetchStatus.FULLTEXT, body, "0.8"),
                article(3, "comet galaxy telescope observation", null));

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(3L), true);

        assertTrue(incremental.contentGroups().isEmpty());
        assertEquals(List.of(3L), incremental.issues().getFirst().articleIds());
        assertEquals(1, incremental.pairScores().size(), "the saved duplicate group contributes one voter");
    }

    @Test
    void losingTheSavedContentRepresentativesBodyUpdatesSurvivingGroupsAndOtherTopics() {
        String body = "A laboratory documented a controlled experiment with detailed independent measurements. ".repeat(8);
        ClusterArticle failed = copy(article(1, "orchid cedar maple discovery", 10L),
                1, 80L, 10L, FetchStatus.FETCH_FAILED, body, "0.99");
        ClusterArticle survivor = copy(article(2, "orchid cedar maple discovery", 10L),
                1, 80L, 10L, FetchStatus.FULLTEXT, body, "0.9");
        ClusterArticle otherTopic = copy(article(3, "orchid cedar maple discovery", 20L),
                2, 80L, 20L, FetchStatus.FULLTEXT, body, "0.8");
        List<ClusterArticle> input = List.of(failed, survivor, otherTopic);

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(1L));

        assertEquals(clusterer.cluster(input), incremental);
        assertEquals(2L, incremental.contentGroups().getFirst().representativeArticleId());
        assertEquals(Set.of(1L, 2L), incremental.issues().stream()
                .map(ClusterPlan.IssueAssignment::topicId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(incremental.issues().stream().allMatch(issue -> issue.representativeArticleId() == 2L));
    }

    @Test
    void pairCountGrowsWithChangedEvidenceRatherThanTheSquareOfHistory() {
        List<ClusterArticle> input = new ArrayList<>();
        for (int index = 1; index <= 200; index++) {
            input.add(article(index, "article " + index, (long) index));
        }

        ClusterPlan incremental = clusterer.clusterChanges(input, Set.of(200L), true);

        assertEquals(199, incremental.pairScores().size());
        assertTrue(clusterer.clusterChanges(input, Set.of()).issues().isEmpty());
    }

    private static ClusterArticle article(long id, String title, Long issueId) {
        return new ClusterArticle(id, 1, title, title, "검증된 기사 본문 " + id,
                FetchStatus.FULLTEXT, id, "publisher " + id, new BigDecimal("0.8"),
                TIME, TIME, List.of(), null, null, issueId, issueId == null);
    }

    private static ClusterArticle copy(ClusterArticle article, long topicId, Long groupId, Long issueId,
                                       FetchStatus status, String body, String reliability) {
        return new ClusterArticle(article.articleId(), topicId, article.title(), article.summary(), body,
                status, article.sourceId(), article.publisher(), new BigDecimal(reliability), TIME, TIME,
                List.of(), groupId, null, issueId, article.observedInRun());
    }
}
