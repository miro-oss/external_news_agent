package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererTest {

    private IssueClusterer clusterer;

    @BeforeEach
    void setUp() {
        IssueClusteringProperties properties = new IssueClusteringProperties();
        clusterer = new IssueClusterer(properties);
    }

    @Test
    void groupsSyndicatedFullTextWithoutDroppingPublisherRows() {
        String original = longBody("삼성전자가 HBM4 양산 계획을 발표했다");
        String syndicated = original.replaceFirst("계획", "일정");
        ClusterArticle first = article(
                1L, "삼성전자 HBM4 양산 계획 발표", original,
                FetchStatus.FULLTEXT, "전자신문", "0.85", hour(0));
        ClusterArticle second = article(
                2L, "[속보] 삼성전자 HBM4 양산 계획 발표 - 연합뉴스", syndicated,
                FetchStatus.FULLTEXT, "연합뉴스", "0.95", hour(1));
        ClusterArticle unrelated = article(
                3L, "인텔 오하이오 공장 보조금 협상 재개", longBody("인텔이 공장 보조금 협상을 재개했다"),
                FetchStatus.FULLTEXT, "로이터", "0.90", hour(2));

        ClusterPlan plan = clusterer.cluster(List.of(first, second, unrelated));

        assertEquals(1, plan.contentGroups().size());
        assertEquals(List.of(1L, 2L), plan.contentGroups().getFirst().articleIds());
        assertEquals(2, plan.issues().size());
        ClusterPlan.IssueAssignment grouped = plan.issues().stream()
                .filter(issue -> issue.articleIds().contains(1L))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(1L, 2L), grouped.articleIds());
        assertEquals(2, grouped.publisherCount());
        assertEquals(1, grouped.independentContentCount());
        assertEquals(2L, grouped.representativeArticleId());
    }

    @Test
    void neverCreatesContentGroupFromMetadataOnlySimilarity() {
        ClusterArticle first = article(
                1L, "삼성전자 HBM4 공급 일정 발표", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "삼성전자 HBM4 공급 전망 공개", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(1));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertTrue(plan.contentGroups().isEmpty());
    }

    @Test
    void joinsDifferentTitlesWhenTwoDeterministicEntitiesOverlapWithinWindow() {
        ClusterArticle first = article(
                1L, "삼성전자 차세대 메모리 투자 확대", longBody("삼성전자 HBM4 투자"),
                FetchStatus.FULLTEXT, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "HBM4 생산라인에 추가 장비 투입", longBody("삼성전자 HBM4 생산라인"),
                FetchStatus.FULLTEXT, "매일경제", "0.8", hour(47));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertEquals(1, plan.issues().size());
        assertTrue(plan.pairScores().getFirst().entityOverlap() >= 2);
        assertTrue(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void doesNotUseEntityMatchOutsideFortyEightHourWindow() {
        ClusterArticle first = article(
                1L, "삼성전자 차세대 메모리 투자 확대", longBody("삼성전자 HBM4 투자"),
                FetchStatus.FULLTEXT, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "HBM4 생산라인에 추가 장비 투입", longBody("삼성전자 HBM4 생산라인"),
                FetchStatus.FULLTEXT, "매일경제", "0.8", hour(49));

        ClusterPlan plan = clusterer.cluster(List.of(first, second), true);

        assertEquals(2, plan.issues().size());
        assertFalse(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void reusesGlobalContentRepresentativeAcrossTopics() {
        String body = longBody("공동 배포된 동일 보도자료");
        ClusterArticle first = article(
                1L, "반도체 설비 투자 공동 발표", body,
                FetchStatus.FULLTEXT, "전자신문", "0.9", hour(0));
        ClusterArticle second = new ClusterArticle(
                2L,
                8L,
                "제조 자동화 설비 투자 공동 발표",
                "같은 원고",
                body,
                FetchStatus.FULLTEXT,
                2L,
                "산업일보",
                new BigDecimal("0.8"),
                hour(0),
                hour(0),
                List.of("제조"),
                null,
                null,
                null,
                true);

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertEquals(1, plan.contentGroups().size());
        assertEquals(2, plan.issues().size());
        assertTrue(plan.issues().stream().allMatch(issue -> issue.representativeArticleId() == 1L));
        assertTrue(plan.issues().stream()
                .filter(issue -> issue.topicId() == 8L)
                .findFirst()
                .orElseThrow()
                .articleIds()
                .containsAll(List.of(1L, 2L)));
    }

    @Test
    void recordsLosingExistingGroupsAndIssuesForTransactionalMerge() {
        String body = longBody("동일 배포 원고");
        ClusterArticle first = existingArticle(1L, "삼성전자 HBM4 투자 확정", body, 10L, 100L);
        ClusterArticle second = existingArticle(2L, "삼성전자 HBM4 투자 공식 발표", body, 20L, 200L);

        ClusterPlan plan = clusterer.cluster(List.of(first, second));

        assertEquals(1, plan.contentGroups().size());
        assertEquals(10L, plan.contentGroups().getFirst().existingContentGroupId());
        assertEquals(List.of(20L), plan.contentGroups().getFirst().mergedContentGroupIds());
        assertEquals(1, plan.issues().size());
        assertEquals(100L, plan.issues().getFirst().existingIssueId());
        assertEquals(List.of(200L), plan.issues().getFirst().mergedIssueIds());
    }

    @Test
    void usesStableEpochWhenEveryEventTimeIsMissing() {
        ClusterArticle article = new ClusterArticle(
                1L, 7L, "시간 정보 없는 기사", null, null, FetchStatus.METADATA_ONLY,
                1L, "전자신문", new BigDecimal("0.8"), null, null,
                List.of("반도체"), null, null, null, true);

        ClusterPlan plan = clusterer.cluster(List.of(article));

        assertEquals(OffsetDateTime.parse("1970-01-01T00:00:00Z"),
                plan.issues().getFirst().firstSeenAt());
        assertTrue(plan.pairScores().isEmpty());
    }

    @Test
    void keepsPairDiagnosticsOptIn() {
        ClusterArticle first = article(
                1L, "삼성전자 HBM4 투자", null,
                FetchStatus.METADATA_ONLY, "전자신문", "0.8", hour(0));
        ClusterArticle second = article(
                2L, "삼성전자 HBM4 공식 투자", null,
                FetchStatus.METADATA_ONLY, "매일경제", "0.8", hour(1));

        assertTrue(clusterer.cluster(List.of(first, second)).pairScores().isEmpty());
        assertEquals(1, clusterer.cluster(List.of(first, second), true).pairScores().size());
    }

    private ClusterArticle existingArticle(long id,
                                           String title,
                                           String body,
                                           long contentGroupId,
                                           long issueId) {
        return new ClusterArticle(
                id, 7L, title, title, body, FetchStatus.FULLTEXT, id,
                "매체" + id, new BigDecimal("0.9"), hour(0), hour(0),
                List.of("반도체"), contentGroupId, SimHash.toHex(SimHash.of(body)), issueId, true);
    }

    private ClusterArticle article(long id,
                                   String title,
                                   String body,
                                   FetchStatus fetchStatus,
                                   String publisher,
                                   String reliability,
                                   OffsetDateTime publishedAt) {
        return new ClusterArticle(
                id,
                7L,
                title,
                title + " 관련 상세 보도",
                body,
                fetchStatus,
                id,
                publisher,
                new BigDecimal(reliability),
                publishedAt,
                publishedAt,
                List.of("반도체"),
                null,
                null,
                null,
                true);
    }

    private String longBody(String sentence) {
        return (sentence + ". 관련 업계와 공급망의 구체적인 일정과 생산 계획을 설명했다. ")
                .repeat(20);
    }

    private OffsetDateTime hour(int hours) {
        return OffsetDateTime.parse("2026-08-10T00:00:00+09:00").plusHours(hours);
    }
}
