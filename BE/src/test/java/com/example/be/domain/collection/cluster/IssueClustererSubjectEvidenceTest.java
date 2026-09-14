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

class IssueClustererSubjectEvidenceTest {
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void differentUniversitiesCannotBridgeThroughTheSameAdmissionsTemplate() {
        var first = article(1, "새빛대 2028학년도 수시 경쟁률 10대 1 발표",
                "새빛대학교는 2028학년도 수시모집 경쟁률이 10대 1을 기록했다고 밝혔다.", null);
        var second = article(2, "가온대 2028학년도 수시 경쟁률 8대 1 발표",
                "가온대학교는 2028학년도 수시모집 경쟁률이 8대 1을 기록했다고 밝혔다.", null);
        var followup = article(3, "가온대학교 2028학년도 수시 경쟁률 8대 1 발표",
                "가온대는 올해 2028학년도 수시모집 경쟁률 8대 1을 발표했다.", null);
        assertEquals(Set.of(Set.of(1L), Set.of(2L, 3L)), memberships(clusterer.cluster(List.of(first, second, followup))));
        assertEquals(Set.of(Set.of(1L), Set.of(2L, 3L)), memberships(clusterer.cluster(List.of(followup, first, second))));
    }

    @Test
    void anAliasConnectsTheSameIdentifiedAdmissionsResultAcrossHeadlineWording() {
        var first = article(1, "에너지공대 2028학년도 경쟁률 31대1 역대 최대", null,
                "한국에너지공과대학교는 2028학년도 수시모집 경쟁률이 31대 1을 기록했다고 밝혔다.");
        var second = article(2, "켄텍 수시 전형, 100명 모집에 3100명 몰려", null,
                "켄텍은 2028학년도 수시모집에 100명 모집, 3100명 지원으로 경쟁률 31대 1을 기록했다.");
        assertEquals(Set.of(Set.of(1L, 2L)), memberships(clusterer.cluster(List.of(first, second))));
    }

    @Test
    void aProductMakerKeepsItsIdentityWhenHeadlinesEmphasizeDifferentBackgroundVendors() {
        String source = "새빛컴퓨팅은 2028년부터 3나노 공정의 AI CPU '하늘'을 미국에 수출한다.";
        var first = article(1, "새빛컴퓨팅, '하늘' 내년 미국 수출…TSMC가 생산", source, null);
        var second = article(2, "인텔·AMD 독점 깬다…새빛컴퓨팅 AI CPU '하늘' 2028년 미국 수출", null,
                source + " 제품은 데이터센터용으로 설계됐다. 제조 파트너 TSMC가 생산을 맡는다.");
        assertEquals(Set.of("새빛컴퓨팅"), clusterer.titleOrganizations(first));
        assertEquals(clusterer.titleOrganizations(first), clusterer.titleOrganizations(second));
        assertEquals(Set.of(Set.of(1L, 2L)), memberships(clusterer.cluster(List.of(first, second))));
    }

    @Test
    void aBackgroundProductCannotReplaceAHeadlineCompanysOwnAnnouncement() {
        var first = article(1, "인텔, 차세대 AI 플랫폼 신제품 공개", null,
                "새빛컴퓨팅은 2028년부터 3나노 공정의 AI CPU '하늘'을 미국에 수출한다.");
        assertEquals(Set.of("인텔"), clusterer.titleOrganizations(first));
        var second = article(2, "AMD, 차세대 AI 플랫폼 신제품 공개", null, first.body());
        // Shared text below the minimum content length cannot erase explicit headline owners.
        var plan = clusterer.cluster(List.of(first, second), true);
        assertFalse(plan.pairScores().getFirst().sameCluster());
    }

    @Test
    void anExplicitTrainingPeriodConnectsDelayedCoverageButSeparatesRepeatedSessions() {
        var first = article(1, "새빛대 '반도체 장비 교육' 성료", null,
                "새빛대학교는 7월 16일~8월 3일 '반도체 장비 교육'을 운영했다.");
        var delayed = articleAt(2, "새빛대학교, '반도체 장비 교육' 마무리", null,
                "새빛대는 7월 16일부터 8월 3일까지 '반도체 장비 교육'을 실시했다고 밝혔다.",
                first.eventTime().plusHours(26));
        var anotherSession = articleAt(3, first.title(), null,
                "새빛대학교는 8월 16일~9월 3일 '반도체 장비 교육'을 운영했다.",
                first.eventTime().plusHours(27));
        assertEquals(Set.of(Set.of(1L, 2L), Set.of(3L)),
                memberships(clusterer.cluster(List.of(first, delayed, anotherSession))));
    }

    private static Set<Set<Long>> memberships(ClusterPlan plan) {
        return plan.issues().stream().map(issue -> Set.copyOf(issue.articleIds())).collect(Collectors.toSet());
    }

    private static ClusterArticle article(long id, String title, String summary, String body) {
        var time = OffsetDateTime.parse("2027-10-12T12:00:00+09:00").plusMinutes(id);
        return articleAt(id, title, summary, body, time);
    }

    private static ClusterArticle articleAt(long id, String title, String summary, String body, OffsetDateTime time) {
        return new ClusterArticle(id, 1, title, summary, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT,
                id, "fixture-" + id, new BigDecimal("0.8"), time, time, List.of(), null, null, null, true);
    }
}
