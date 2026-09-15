package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererFullTextEvidenceTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-09-15T09:00:00+09:00");
    private static final String TITLE = "한화세미텍 첨단 패키징 장비 공개";
    private static final String BODY = "한화세미텍은 첨단 패키징 장비를 공개했다고 밝혔다.";
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void absentOrUnverifiedBodiesRemainCandidatesWithoutIssueAssignmentsOrEvidence() {
        List<ClusterArticle> articles = List.of(
                article(1, TITLE, null, FetchStatus.METADATA_ONLY, null, null),
                article(2, TITLE, BODY, FetchStatus.METADATA_ONLY, null, null),
                article(3, TITLE, BODY, FetchStatus.FETCH_FAILED, null, null),
                article(4, TITLE, null, FetchStatus.FULLTEXT_BLOCKED, null, null),
                article(5, TITLE, null, FetchStatus.FULLTEXT, null, null),
                article(6, TITLE, " \n\t ", FetchStatus.FULLTEXT, null, null));
        ClusterPlan plan = clusterer.cluster(articles, true);
        assertTrue(plan.issues().isEmpty());
        assertTrue(plan.contentGroups().isEmpty());
        assertTrue(plan.pairScores().isEmpty());
        assertTrue(clusterer.eventConflictingArticleIds(articles).values().stream().allMatch(List::isEmpty));
        assertTrue(articles.stream().allMatch(value -> clusterer.titleOrganizations(value).isEmpty()));
    }

    @Test
    void existingMetadataOnlyIssueIsNotReassignedOrMerged() {
        List<ClusterArticle> articles = List.of(
                article(1, TITLE, null, FetchStatus.METADATA_ONLY, 10L, 70L),
                article(2, "[속보] " + TITLE, null, FetchStatus.FETCH_FAILED, 10L, 70L));
        for (List<ClusterArticle> input : List.of(articles, articles.reversed())) {
            assertTrue(clusterer.cluster(input).issues().isEmpty());
        }
    }

    @Test
    void shortActualBodiesParticipateWithoutContentFingerprints() {
        ClusterArticle breaking = article(1, "[속보] " + TITLE, BODY, FetchStatus.FULLTEXT, null, null);
        ClusterArticle followup = article(2, TITLE, BODY, FetchStatus.FULLTEXT, null, null);
        assertTrue(SimHash.tryOfArticleBody(BODY, 200).isEmpty());
        ClusterPlan plan = clusterer.cluster(List.of(breaking, followup), true);
        assertEquals(Set.of(Set.of(1L, 2L)), memberships(plan));
        assertEquals(2L, plan.issues().getFirst().representativeArticleId());
        assertTrue(plan.contentGroups().isEmpty());
        assertEquals(1, plan.pairScores().size());
        assertTrue(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void metadataCannotBridgeTwoOtherwiseSeparateEventsInAnyInputOrder() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(0.6);
        properties.setEntityOverlapThreshold(100);
        properties.setOrganizationTimeWindow(Duration.ZERO);
        properties.setEntityTimeWindow(Duration.ZERO);
        IssueClusterer lexical = new IssueClusterer(properties, new BreakingNewsDetector());
        ClusterArticle left = article(1, "orchid cedar maple birch", "첫 번째 기사 본문이다.",
                FetchStatus.FULLTEXT, null, null);
        ClusterArticle bridge = article(2, "orchid cedar maple birch aspen willow", null,
                FetchStatus.METADATA_ONLY, null, null);
        ClusterArticle right = article(3, "maple birch aspen willow", "세 번째 기사 본문이다.",
                FetchStatus.FULLTEXT, null, null);
        assertEquals(Set.of(Set.of(1L), Set.of(3L)), memberships(lexical.cluster(List.of(left, right))));
        for (List<ClusterArticle> input : permutations(List.of(left, bridge, right))) {
            ClusterPlan plan = lexical.cluster(input, true);
            assertEquals(Set.of(Set.of(1L), Set.of(3L)), memberships(plan));
            assertEquals(1, plan.pairScores().size());
            assertFalse(plan.pairScores().getFirst().sameCluster());
        }
        ClusterArticle verifiedBridge = article(2, bridge.title(), "두 번째 기사 본문이다.",
                FetchStatus.FULLTEXT, null, null);
        assertEquals(Set.of(Set.of(1L, 2L, 3L)),
                memberships(lexical.cluster(List.of(left, verifiedBridge, right))));
    }

    @Test
    void savedMetadataOrganizationCannotVetoAValidFollowupOrAddOutputEntities() {
        ClusterArticle original = article(1, TITLE, BODY, FetchStatus.FULLTEXT, null, 70L);
        ClusterArticle metadata = article(2, "디엠에스 QZX987 장비 공개", null,
                FetchStatus.METADATA_ONLY, null, 70L);
        ClusterArticle followup = article(3, TITLE, BODY, FetchStatus.FULLTEXT, null, null);
        List<String> expectedEntities = clusterer.cluster(List.of(original, followup)).issues().getFirst().entities();
        for (List<ClusterArticle> input : permutations(List.of(original, metadata, followup))) {
            ClusterPlan plan = clusterer.cluster(input, true);
            assertEquals(Set.of(Set.of(1L, 2L, 3L)), memberships(plan));
            assertEquals(70L, plan.issues().getFirst().existingIssueId());
            assertEquals(1L, plan.issues().getFirst().representativeArticleId());
            assertEquals(expectedEntities, plan.issues().getFirst().entities());
            assertEquals(1, plan.pairScores().size());
        }
    }

    @Test
    void savedMetadataEventConflictCannotVetoFullTextEvidence() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(0);
        IssueClusterer permissive = new IssueClusterer(properties, new BreakingNewsDetector());
        ClusterArticle original = article(1, "코스피 상승 마감", "코스피가 상승 마감했다.",
                FetchStatus.FULLTEXT, null, 70L);
        ClusterArticle metadata = article(2, "ETF 리밸런싱 앞두고 비중 조정", null,
                FetchStatus.METADATA_ONLY, null, 70L);
        ClusterArticle followup = article(3, "코스피 오름세로 출발", "코스피가 오름세로 출발했다.",
                FetchStatus.FULLTEXT, null, null);
        for (List<ClusterArticle> input : permutations(List.of(original, metadata, followup))) {
            assertEquals(Set.of(Set.of(1L, 2L, 3L)), memberships(permissive.cluster(input)));
            assertEquals(List.of(), permissive.eventConflictingArticleIds(input).get(2L));
        }
    }

    @Test
    void savedMetadataContentGroupCannotMergeTwoExistingIssues() {
        String longBody = "A laboratory documented a controlled experiment with detailed independent measurements. ".repeat(8);
        ClusterArticle original = article(1, TITLE, longBody, FetchStatus.FULLTEXT, 10L, 70L);
        ClusterArticle metadata = article(2, TITLE, null, FetchStatus.METADATA_ONLY, 10L, 80L);
        ClusterArticle other = article(3, "디엠에스 첨단 패키징 장비 공개", "디엠에스가 패키징 장비를 공개했다.",
                FetchStatus.FULLTEXT, null, 80L);
        for (List<ClusterArticle> input : permutations(List.of(original, metadata, other))) {
            ClusterPlan plan = clusterer.cluster(input);
            assertEquals(Set.of(Set.of(1L), Set.of(2L, 3L)), memberships(plan));
            assertEquals(Set.of(70L, 80L), plan.issues().stream()
                    .map(ClusterPlan.IssueAssignment::existingIssueId).collect(Collectors.toSet()));
            assertTrue(plan.issues().stream().allMatch(issue -> issue.mergedIssueIds().isEmpty()));
            assertEquals(List.of(1L), plan.contentGroups().getFirst().articleIds());
        }
    }

    @Test
    void metadataContentProxyDoesNotCreateAnIssueInAnotherTopic() {
        String body = "A laboratory documented a controlled experiment with detailed independent measurements. ".repeat(8);
        ClusterArticle representative = article(1, TITLE, body, FetchStatus.FULLTEXT, 10L, null);
        ClusterArticle metadata = new ClusterArticle(2, 2, TITLE, TITLE, null,
                FetchStatus.METADATA_ONLY, 2, "다른 매체", new BigDecimal("0.8"), TIME, TIME,
                List.of(), 10L, null, null, true);
        ClusterPlan plan = clusterer.cluster(List.of(representative, metadata));
        assertEquals(1, plan.issues().size());
        assertEquals(1L, plan.issues().getFirst().topicId());
        assertEquals(List.of(1L), plan.issues().getFirst().articleIds());
        assertEquals(List.of(1L), plan.contentGroups().getFirst().articleIds());
    }

    @Test
    void acquiringActualBodyRejoinsTheExistingIssueUsingTheSameArticleId() {
        ClusterArticle previous = article(1, TITLE, null, FetchStatus.METADATA_ONLY, null, 70L);
        previous = new ClusterArticle(previous.articleId(), previous.topicId(), previous.title(), previous.summary(),
                previous.body(), previous.fetchStatus(), previous.sourceId(), previous.publisher(),
                previous.reliabilityScore(), previous.publishedAt(), previous.observedAt(), previous.topicKeywords(),
                previous.contentGroupId(), previous.contentGroupSimhash(), previous.existingIssueId(), false);
        ClusterArticle followup = article(2, TITLE, BODY, FetchStatus.FULLTEXT, null, null);
        assertEquals(Set.of(Set.of(2L)), memberships(clusterer.cluster(List.of(previous, followup))));
        ClusterArticle recovered = article(1, TITLE, BODY, FetchStatus.FULLTEXT, null, 70L);
        for (List<ClusterArticle> input : permutations(List.of(previous, recovered, followup))) {
            ClusterPlan plan = clusterer.cluster(input);
            assertEquals(Set.of(Set.of(1L, 2L)), memberships(plan));
            assertEquals(70L, plan.issues().getFirst().existingIssueId());
        }
    }

    private static Set<Set<Long>> memberships(ClusterPlan plan) {
        return plan.issues().stream().map(issue -> Set.copyOf(issue.articleIds())).collect(Collectors.toSet());
    }

    private static List<List<ClusterArticle>> permutations(List<ClusterArticle> articles) {
        return List.of(List.of(0, 1, 2), List.of(0, 2, 1), List.of(1, 0, 2),
                        List.of(1, 2, 0), List.of(2, 0, 1), List.of(2, 1, 0)).stream()
                .map(order -> order.stream().map(articles::get).toList()).toList();
    }

    private static ClusterArticle article(long id, String title, String body, FetchStatus status,
                                           Long contentGroupId, Long existingIssueId) {
        OffsetDateTime time = TIME.plusHours(id);
        return new ClusterArticle(id, 1, title, title + " 관련 보도", body, status,
                id, "매체" + id, new BigDecimal("0.8"), time, time, List.of(),
                contentGroupId, null, existingIssueId, true);
    }
}
