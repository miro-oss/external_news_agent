package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererMarketScopeTest {
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void differentHeadlineCompanyExamplesCannotVetoTheSameVerifiedMarketSession() {
        var first = article(1, "[뉴욕증시] 엔비디아·AMD 급락…나스닥 약세", close("17"));
        var second = article(2, "[뉴욕증시] SK하이닉스 ADR 하락…금리 부담",
                "17일(현지시간) 뉴욕증시에서 나스닥 지수는 0.5% 떨어진 18500.25에 마감했다.");
        assertEquals(Set.of(), clusterer.titleOrganizations(first));
        assertEquals(Set.of(), clusterer.titleOrganizations(second));
        var plan = clusterer.cluster(List.of(first, second), true);
        assertTrue(plan.pairScores().getFirst().specificEventMatch());
        assertEquals(1, plan.issues().size());
    }

    @Test
    void anEarlierCompanyAngleCannotPoisonALaterVerifiedSessionBridge() {
        var companyAngle = article(1, "[뉴욕증시] 엔비디아·AMD 급락…나스닥 약세", close("17"));
        var market = article(2, "뉴욕증시, 유가 부담에 하락", close("17"));
        var otherAngle = article(3, "[뉴욕증시] SK하이닉스 ADR 하락…금리 부담",
                "17일(현지시간) 뉴욕증시에서 나스닥 지수는 0.5% 떨어진 18500.25에 마감했다.");
        var plan = clusterer.cluster(List.of(otherAngle, market, companyAngle), true);
        assertEquals(1, plan.issues().size());
        assertEquals(List.of(1L, 2L, 3L), plan.issues().getFirst().articleIds());
    }

    @Test
    void aSectorRoundupUsesTheMarketSessionInsteadOfTheHeadlineVendors() {
        var sector = article(1, "인텔·AMD 주가 급락 [뉴욕증시 무버]",
                "17일(현지시간) 뉴욕증시에서 주목할 만한 종목은 인텔, AMD 등이다. "
                        + "반도체 관련주에 매도세가 확산했다. 필라델피아 반도체지수는 2.3% 하락한 4200.25에 거래를 마쳤다.");
        var market = article(2, "[뉴욕증시] SK하이닉스 ADR 하락…금리 부담", close("17"));
        assertEquals(Set.of(), clusterer.titleOrganizations(sector));
        var plan = clusterer.cluster(List.of(sector, market), true);
        assertTrue(plan.pairScores().getFirst().specificEventMatch());
        assertEquals(1, plan.issues().size());
    }

    @Test
    void marketScopeDoesNotEraseDifferentTradingDayConflicts() {
        var first = article(1, "[뉴욕증시] 엔비디아 급락…나스닥 약세", close("17"));
        var previous = article(2, "[뉴욕증시] SK하이닉스 ADR 하락…나스닥 약세", close("16"));
        var bridge = article(3, "뉴욕증시, 나스닥 약세", "나스닥 지수는 하락 마감했다.");
        var plan = clusterer.cluster(List.of(first, previous, bridge), true);
        assertTrue(plan.issues().stream().noneMatch(issue ->
                issue.articleIds().contains(1L) && issue.articleIds().contains(2L)));
        assertEquals(List.of(2L), clusterer.eventConflictingArticleIds(List.of(first, previous, bridge)).get(1L));
    }

    @Test
    void differentCompanyAnnouncementsRetainTheirVendorProfilesDespiteMarketBackground() {
        var first = article(1, "엔비디아, 신제품 출시…인공지능 반도체 공개",
                "엔비디아는 새 인공지능 반도체를 출시했다. " + close("17"));
        var second = article(2, "AMD, 신제품 출시…인공지능 반도체 공개",
                "AMD는 새 인공지능 반도체를 출시했다. " + close("17"));
        assertEquals(Set.of("엔비디아"), clusterer.titleOrganizations(first));
        assertEquals(Set.of("AMD"), clusterer.titleOrganizations(second));
        assertEquals(2, clusterer.cluster(List.of(first, second)).issues().size());
    }

    @Test
    void companyEarningsRetainTheirVendorProfilesDespiteTwoClosingIndexQuotes() {
        var first = article(1, "엔비디아 사상 최대 실적 발표",
                "엔비디아는 매출과 영업이익이 사상 최고치를 기록한 분기 실적을 발표했다. " + close("17"));
        var second = article(2, "AMD 사상 최대 실적 발표",
                "AMD는 매출과 영업이익이 사상 최고치를 기록한 분기 실적을 발표했다. " + close("17"));
        assertEquals(Set.of("엔비디아"), clusterer.titleOrganizations(first));
        assertEquals(Set.of("AMD"), clusterer.titleOrganizations(second));
        var plan = clusterer.cluster(List.of(first, second), true);
        assertFalse(plan.pairScores().getFirst().specificEventMatch());
        assertEquals(2, plan.issues().size());
    }

    @Test
    void singleCompanyPriceMovesRetainTheirVendorProfilesDespiteTwoClosingIndexQuotes() {
        var first = article(1, "엔비디아, 실적 부진에 10% 급락",
                "엔비디아는 시장 기대를 밑도는 실적을 공개한 뒤 급락했다. " + close("17"));
        var second = article(2, "AMD, 실적 부진에 10% 급락",
                "AMD는 시장 기대를 밑도는 실적을 공개한 뒤 급락했다. " + close("17"));
        assertEquals(Set.of("엔비디아"), clusterer.titleOrganizations(first));
        assertEquals(Set.of("AMD"), clusterer.titleOrganizations(second));
        var plan = clusterer.cluster(List.of(first, second), true);
        assertFalse(plan.pairScores().getFirst().specificEventMatch());
        assertEquals(2, plan.issues().size());
    }

    @Test
    void missingBodyCannotSupplyMarketScopeOrAnIssueVote() {
        var first = article(1, "뉴욕증시, 유가 부담에 하락", close("17"));
        var metadata = new ClusterArticle(2, 1, first.title(), first.body(), null, FetchStatus.FETCH_FAILED,
                2, "fixture", BigDecimal.ONE, first.publishedAt(), first.observedAt(), List.of(),
                null, null, null, true);
        var plan = clusterer.cluster(List.of(first, metadata), true);
        assertEquals(1, plan.issues().size());
        assertFalse(plan.issues().getFirst().articleIds().contains(2L));
    }

    @Test
    void primarySectorSnapshotConnectsToAnActualCloseWithoutAHeadlineVendorVeto() {
        var snapshot = article(1, "오늘 증시 이슈…코스피 외국인 동향",
                "뉴욕 증시가 17일(현지시간) 일제히 하락했습니다. "
                        + "필라델피아 반도체지수는 2.35% 내린 4200.50을 기록했다.");
        var actualClose = article(2, "[뉴욕증시] SK하이닉스 ADR 급락",
                "17일 뉴욕증시에서 필라델피아 반도체지수는 2.35% 내린 4200.50에 마감했다.");
        var plan = clusterer.cluster(List.of(snapshot, actualClose), true);
        assertEquals(1, plan.issues().size());
        assertTrue(plan.pairScores().getFirst().specificEventMatch());
    }

    @Test
    void undatedRoundupKeepsVendorsAndCannotJoinAnOutlookThroughAnUnknownBridge() {
        String title = "인텔·AMD 증시 희비…반도체 산업 비관론은 아직";
        // Scope conflicts require a roundup headline without a primary outlook focus.
        var roundup = article(1, "인텔·AMD 증시 희비…반도체 관련주 하락",
                "간밤 뉴욕증시에서는 반도체와 인프라 관련주가 하락했다. 소프트웨어 업종은 반등했다.");
        var outlook = article(2, title,
                "기술 개발 논쟁이 기업의 사업에 미칠 영향에도 관심이 쏠린다.");
        var bridge = article(3, "인텔·AMD 증시 희비…반도체 관련주 하락",
                "시장 참가자들이 새 보고서에 관심을 보였다.");
        assertEquals(Set.of("인텔", "AMD"), clusterer.titleOrganizations(roundup));
        var plan = clusterer.cluster(List.of(roundup, bridge, outlook), true);
        assertTrue(plan.issues().stream().noneMatch(issue ->
                issue.articleIds().contains(1L) && issue.articleIds().contains(2L)));
        assertTrue(clusterer.eventConflictingArticleIds(List.of(roundup, bridge, outlook)).get(1L).contains(2L));
    }

    private static String close(String day) {
        return day + "일(현지시간) 뉴욕증시에서 다우지수는 0.3% 내린 42000.25에 거래를 마쳤다. "
                + "나스닥 지수는 0.5% 하락한 18500.25에 마감했다.";
    }

    private static ClusterArticle article(long id, String title, String body) {
        var time = OffsetDateTime.parse("2026-06-18T09:00:00+09:00");
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT, id, "fixture-" + id,
                BigDecimal.ONE, time, time, List.of(), null, null, null, true);
    }
}
