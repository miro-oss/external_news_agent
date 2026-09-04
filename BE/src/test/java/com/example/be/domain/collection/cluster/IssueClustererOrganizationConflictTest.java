package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** User-confirmed event boundary and synthetic counterexamples for #163. */
class IssueClustererOrganizationConflictTest {

    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void separatesUserConfirmedDifferentCompanyAnnouncementsAtTheSameExhibition() {
        ClusterArticle hanwha = article(3797L,
                "한화세미텍, '세미콘 타이완 2026'서 첨단 패키징 장비 4종 공개");
        ClusterArticle dms = article(3771L,
                "디엠에스, ‘세미콘 타이완’ 참가…유리기판·첨단 패키징 장비 공개");

        ClusterPlan plan = clusterer.cluster(List.of(hanwha, dms), true);

        assertEquals(0.5, plan.pairScores().getFirst().titleJaccard(), 0.000001);
        assertFalse(plan.pairScores().getFirst().sameCluster());
        assertEquals(Set.of(Set.of(3797L), Set.of(3771L)), memberships(plan));
    }

    @Test
    void separatesThreeVendorsWhileRetainingCoverageOfTheSameVendorAnnouncement() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(3797L, "한화세미텍, '세미콘 타이완 2026'서 첨단 패키징 장비 4종 공개"),
                article(3771L, "디엠에스, ‘세미콘 타이완’ 참가…유리기판·첨단 패키징 장비 공개"),
                article(3738L, "한미반도체, '세미콘 타이완 2026'서 2.5D 패키징 장비 대거 공개"),
                article(3739L, "한화세미텍, 세미콘 타이완 참가…첨단 패키징 장비 라인업 공개")));

        assertEquals(Set.of(Set.of(3797L, 3739L), Set.of(3771L), Set.of(3738L)), memberships(plan));
    }

    @Test
    void retainsStrongTitleMatchWhenTheSameVendorUsesAnEnglishAlias() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1L, "한화세미텍 세미콘 타이완 첨단 패키징 장비 공개"),
                article(2L, "Hanwha Semitech 세미콘 타이완 첨단 패키징 장비 공개")));

        assertEquals(Set.of(Set.of(1L, 2L)), memberships(plan));
    }

    @Test
    void doesNotTreatMissingOrganizationAsEvidenceOfAConflict() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1L, "한화세미텍 세미콘 타이완 첨단 패키징 장비 공개"),
                article(2L, "세미콘 타이완 첨단 패키징 장비 공개")));

        assertEquals(Set.of(Set.of(1L, 2L)), memberships(plan));
    }

    @Test
    void preventsAnUnknownOrganizationArticleFromBridgingDifferentVendors() {
        List<ClusterArticle> articles = List.of(
                article(1L, "한화세미텍 세미콘 타이완 첨단 패키징 장비 공개"),
                article(2L, "세미콘 타이완 첨단 패키징 장비 공개"),
                article(3L, "디엠에스 세미콘 타이완 첨단 패키징 장비 공개"));

        assertStableMembershipsAcrossPermutations(articles, Set.of(Set.of(1L, 2L), Set.of(3L)));
    }

    @Test
    void preventsAnOverlappingOrganizationListFromBridgingSingleVendorAnnouncements() {
        List<ClusterArticle> articles = List.of(
                article(1L, "한화세미텍 세미콘 타이완 첨단 패키징 장비 공개"),
                article(2L, "한화세미텍 디엠에스 세미콘 타이완 첨단 패키징 장비 공개"),
                article(3L, "디엠에스 세미콘 타이완 첨단 패키징 장비 공개"));

        assertStableMembershipsAcrossPermutations(articles, Set.of(Set.of(1L, 2L), Set.of(3L)));
    }

    @Test
    void retainsDirectJointAnnouncementCoverageWithOverlappingNamedParties() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1L, "한화세미텍 디엠에스 첨단 패키징 장비 공동 개발 발표"),
                article(2L, "디엠에스 한화세미텍 첨단 패키징 장비 공동 개발 공식 발표")));

        assertEquals(Set.of(Set.of(1L, 2L)), memberships(plan));
    }

    @Test
    void summaryBackgroundMentionsCannotEraseConflictingTitleOrganizations() {
        ClusterArticle first = article(1L, "삼성전자 첨단 패키징 장비 공개",
                "삼성전자와 인텔의 경쟁 상황은 배경 정보다.", null, null);
        ClusterArticle second = article(2L, "인텔 첨단 패키징 장비 공개",
                "삼성전자와 인텔의 경쟁 상황은 배경 정보다.", null, null);

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertTrue(plan.pairScores().getFirst().entityOverlap() >= 2);
        assertTrue(plan.pairScores().getFirst().organizationOverlap() >= 1);
        assertFalse(plan.pairScores().getFirst().sameCluster());
        assertEquals(Set.of(Set.of(1L), Set.of(2L)), memberships(plan));
    }

    @Test
    void preservesExistingIssueMembershipInsteadOfImplicitlySplittingStoredIssues() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1L, "한화세미텍 첨단 패키징 장비 공개", null, 100L, null),
                article(2L, "디엠에스 첨단 패키징 장비 공개", null, 100L, null)));

        assertEquals(Set.of(Set.of(1L, 2L)), memberships(plan));
        assertEquals(100L, plan.issues().getFirst().existingIssueId());
    }

    @Test
    void preservesExistingContentGroupMembershipWhenCurrentBodyIsUnavailable() {
        ClusterPlan plan = clusterer.cluster(List.of(
                article(1L, "한화세미텍 첨단 패키징 장비 공개", null, null, 10L),
                article(2L, "디엠에스 첨단 패키징 장비 공개", null, null, 10L)));

        assertEquals(Set.of(Set.of(1L, 2L)), memberships(plan));
        assertEquals(1, plan.issues().getFirst().independentContentCount());
    }

    @Test
    void preservesMixedStoredIssueButKeepsNewSingleVendorFollowUpSeparate() {
        List<ClusterArticle> articles = List.of(
                article(1L, "한화세미텍 첨단 패키징 장비 공개", null, 100L, null),
                article(2L, "디엠에스 첨단 패키징 장비 공개", null, 100L, null),
                article(3L, "한화세미텍 첨단 패키징 장비 공개"));

        assertStableMembershipsAcrossPermutations(articles, Set.of(Set.of(1L, 2L), Set.of(3L)));
        ClusterPlan plan = clusterer.cluster(articles, true);
        ClusterPlan.PairScore followUp = plan.pairScores().stream()
                .filter(score -> score.leftArticleId() == 1L && score.rightArticleId() == 3L)
                .findFirst().orElseThrow();
        assertEquals(1.0, followUp.titleJaccard());
        assertFalse(followUp.sameCluster(), "A strong pair must not expand an already mixed issue.");
        assertEquals(100L, plan.issues().stream().filter(issue -> issue.articleIds().contains(1L))
                .findFirst().orElseThrow().existingIssueId());
    }

    @Test
    void preservesMixedContentGroupButKeepsNewSingleVendorFollowUpSeparate() {
        List<ClusterArticle> articles = List.of(
                article(1L, "한화세미텍 첨단 패키징 장비 공개", null, null, 10L),
                article(2L, "디엠에스 첨단 패키징 장비 공개", null, null, 10L),
                article(3L, "한화세미텍 첨단 패키징 장비 공개"));

        assertStableMembershipsAcrossPermutations(articles, Set.of(Set.of(1L, 2L), Set.of(3L)));
    }

    @Test
    void mixedForcedGroupsStillAcceptUnknownOrAllOverlappingProfilesWhenTitlesMatch() {
        for (boolean existingIssue : List.of(true, false)) {
            Long issueId = existingIssue ? 100L : null;
            Long contentId = existingIssue ? null : 10L;
            for (String title : List.of("첨단 패키징 장비 공개", "한화세미텍 디엠에스 첨단 패키징 장비 공개")) {
                List<ClusterArticle> articles = List.of(
                        article(1L, "한화세미텍 첨단 패키징 장비 공개", null, issueId, contentId),
                        article(2L, "디엠에스 첨단 패키징 장비 공개", null, issueId, contentId),
                        article(3L, title));

                assertStableMembershipsAcrossPermutations(articles, Set.of(Set.of(1L, 2L, 3L)));
            }
        }
    }

    private void assertStableMembershipsAcrossPermutations(List<ClusterArticle> articles,
                                                           Set<Set<Long>> expected) {
        for (List<Integer> order : List.of(
                List.of(0, 1, 2), List.of(0, 2, 1), List.of(1, 0, 2),
                List.of(1, 2, 0), List.of(2, 0, 1), List.of(2, 1, 0))) {
            List<ClusterArticle> permuted = order.stream().map(articles::get).toList();
            assertEquals(expected, memberships(clusterer.cluster(permuted)),
                    "Membership changed for input order " + order);
        }
    }

    private Set<Set<Long>> memberships(ClusterPlan plan) {
        return plan.issues().stream().map(issue -> Set.copyOf(issue.articleIds()))
                .collect(Collectors.toSet());
    }

    private ClusterArticle article(long id, String title) {
        return article(id, title, null, null, null);
    }

    private ClusterArticle article(long id, String title, String summary,
                                   Long existingIssueId, Long contentGroupId) {
        OffsetDateTime time = OffsetDateTime.parse("2026-09-04T09:00:00+09:00");
        return new ClusterArticle(id, 7L, title, summary, null, FetchStatus.METADATA_ONLY,
                id, "매체" + id, new BigDecimal("0.8"), time, time,
                List.of("반도체"), contentGroupId, null, existingIssueId, true);
    }
}
