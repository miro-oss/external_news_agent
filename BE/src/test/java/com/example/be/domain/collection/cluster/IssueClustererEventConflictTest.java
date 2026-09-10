package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererEventConflictTest {
    // Deliberately admit every lexical candidate to isolate the component conflict guard.
    private final IssueClusterer clusterer = permissiveClusterer();

    @Test
    void unknownIntermediateArticleCannotBridgeDifferentPrimaryEvents() {
        List<ClusterArticle> articles = List.of(
                article(1, "ETF 리밸런싱 앞두고 비중 조정", null, null),
                article(2, "시장 동향 설명", null, null),
                article(3, "코스피 상승 마감", null, null));
        for (List<ClusterArticle> input : List.of(articles, articles.reversed())) {
            ClusterPlan plan = clusterer.cluster(input, true);
            assertTrue(together(plan, 1, 2));
            assertFalse(together(plan, 1, 3));
            assertFalse(together(plan, 2, 3));
        }
    }

    @Test
    void savedIssueMembershipIsRetainedButItsConflictsCannotSpread() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1, "ETF 리밸런싱 앞두고 비중 조정", null, 70L),
                article(2, "코스피 상승 마감", null, 70L),
                article(3, "코스피 오름세로 출발", null, null)));
        assertTrue(together(plan, 1, 2));
        assertFalse(together(plan, 2, 3));
        assertEquals(70L, plan.issues().stream().filter(issue -> issue.articleIds().contains(1L))
                .findFirst().orElseThrow().existingIssueId());
    }

    @Test
    void fixedContentGroupPreservesAllEventProfiles() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1, "시장 동향 설명", 90L, null),
                article(2, "ETF 리밸런싱 앞두고 비중 조정", 90L, null),
                article(3, "코스피 상승 마감", null, null)));
        assertTrue(together(plan, 1, 2));
        assertFalse(together(plan, 1, 3));
    }

    @Test
    void nonVotingProxyConflictSurvivesAGlobalRepresentativeFromAnotherTopic() {
        String body = "A laboratory documented a controlled experiment with detailed independent measurements. "
                .repeat(8);
        ClusterArticle representative = fullText(1, 9, "시장 동향 설명", body);
        ClusterArticle proxy = fullText(2, 1, "ETF 리밸런싱 앞두고 비중 조정", body);
        ClusterPlan plan = clusterer.cluster(List.of(representative, proxy,
                article(3, "코스피 상승 마감", null, null)), true);
        assertTrue(together(plan, 1, 2));
        assertFalse(together(plan, 1, 3));
        assertFalse(plan.pairScores().stream().anyMatch(score ->
                score.leftArticleId() == 2 || score.rightArticleId() == 2));
        assertEquals(List.of(3L), clusterer.eventConflictingArticleIds(List.of(
                representative, proxy, article(3, "코스피 상승 마감", null, null))).get(2L));
    }

    @Test
    void exporterIncludesSymmetricConflictProfilesBeyondVotingPairs() {
        var conflicts = clusterer.eventConflictingArticleIds(List.of(
                article(1, "시장 동향 설명", 90L, null),
                article(2, "ETF 리밸런싱 앞두고 비중 조정", 90L, null),
                article(3, "코스피 상승 마감", null, null)));
        assertEquals(List.of(), conflicts.get(1L));
        assertEquals(List.of(3L), conflicts.get(2L));
        assertEquals(List.of(2L), conflicts.get(3L));
    }

    @Test
    void repeatedCandidatesDoNotRescanRejectedComponentsAndRootChangesRemainSafe() {
        AtomicInteger checks = new AtomicInteger();
        IssueClusterer.UnionFind union = new IssueClusterer.UnionFind(
                List.of(0L, 1L, 2L, 3L, 4L, 5L), Map.of(), (left, right) -> {
                    checks.incrementAndGet();
                    return (left == 2 && right == 4) || (left == 4 && right == 2);
                });
        union.join(1, 2);
        union.join(3, 4);
        assertFalse(union.canJoin(1, 3));
        checks.set(0);
        for (int attempt = 0; attempt < 1000; attempt++) {
            assertFalse(union.canJoin(2, 4));
            assertFalse(union.canJoin(3, 1));
        }
        assertEquals(0, checks.get());
        union.join(3, 5);
        assertFalse(union.canJoin(1, 5));
        assertEquals(0, checks.get());
        union.join(0, 1);
        assertFalse(union.canJoin(0, 3));
        assertTrue(checks.get() > 0);
        // Forced membership is still permitted and now has one root, even if once rejected.
        union.join(0, 3);
        assertTrue(union.canJoin(2, 4));
    }

    @Test
    void concreteReleaseIdentityUsesTheEntityWindowAndKeepsTheBreakingLimit() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(1);
        properties.setOrganizationTitleJaccardThreshold(1);
        properties.setEntityOverlapThreshold(100);
        IssueClusterer restricted = new IssueClusterer(properties, new BreakingNewsDetector());
        for (boolean breaking : List.of(false, true)) {
            String prefix = breaking ? "[속보] " : "";
            long limit = breaking ? 6 : 48;
            ClusterArticle first = statistics(1, prefix + "한국 명목 GDP 19년 만에 최고", 0);
            assertTrue(together(restricted.cluster(List.of(first,
                    statistics(2, "한국 국민소득 19년 만의 호황", limit))), 1, 2));
            assertFalse(together(restricted.cluster(List.of(first,
                    statistics(2, "한국 국민소득 19년 만의 호황", limit + 1))), 1, 2));
        }
    }

    @Test
    void explicitDifferentReleasePeriodsOverrideEvenIdenticalHeadlines() {
        String title = "한국 GDP 성장 통계";
        ClusterPlan plan = clusterer.cluster(List.of(
                fullText(1, 1, title, "한국은행이 2026년 3분기 잠정 통계를 발표했다."),
                fullText(2, 1, title, "한국은행이 2026년 4분기 잠정 통계를 발표했다.")));
        assertFalse(together(plan, 1, 2));
    }

    private static ClusterArticle statistics(long id, String title, long hours) {
        OffsetDateTime time = OffsetDateTime.parse("2026-10-21T10:00:00+09:00").plusHours(hours);
        String summary = "한국은행이 발표한 3분기 잠정 통계다. 수출 디플레이터는 18.7% 상승했다.";
        return new ClusterArticle(id, 1, title, summary, null, FetchStatus.METADATA_ONLY,
                id, "fixture-" + id, null, time, time, List.of(), null, null, null, true);
    }

    private static boolean together(ClusterPlan plan, long left, long right) {
        return plan.issues().stream().anyMatch(issue ->
                issue.articleIds().containsAll(List.of(left, right)));
    }

    private static IssueClusterer permissiveClusterer() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        properties.setTitleJaccardThreshold(0);
        return new IssueClusterer(properties, new BreakingNewsDetector());
    }

    private static ClusterArticle article(long id, String title, Long contentGroup, Long issue) {
        OffsetDateTime time = OffsetDateTime.parse("2026-03-21T10:00:00+09:00");
        return new ClusterArticle(id, 1, title, null, null, FetchStatus.METADATA_ONLY,
                id, "fixture-" + id, null, time, time, List.of(), contentGroup, null, issue, true);
    }

    private static ClusterArticle fullText(long id, long topicId, String title, String body) {
        OffsetDateTime time = OffsetDateTime.parse("2026-03-21T10:00:00+09:00");
        return new ClusterArticle(id, topicId, title, null, body, FetchStatus.FULLTEXT,
                id, "fixture-" + id, null, time, time, List.of(), null, null, null, true);
    }
}
