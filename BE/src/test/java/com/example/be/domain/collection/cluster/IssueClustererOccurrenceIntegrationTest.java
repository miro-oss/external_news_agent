package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueClustererOccurrenceIntegrationTest {
    private final IssueClusterer clusterer = new IssueClusterer(
            new IssueClusteringProperties(), new BreakingNewsDetector());

    @Test
    void datedIndexCloseConnectsDifferentHeadlinesWithoutMetadataVotes() {
        var first = article(1, "뉴욕증시, 유가 부담에 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.", 0);
        var second = article(2, "나스닥 약세…기술주 매물 쏟아져", "미국증시는 17일 약세였다. 나스닥은 거래를 마쳤다.", 1);
        var metadata = new ClusterArticle(3, 1, first.title(), first.body(), null, FetchStatus.METADATA_ONLY,
                3, "fixture", BigDecimal.ONE, first.publishedAt(), first.observedAt(), List.of(),
                null, null, null, true);
        var plan = clusterer.cluster(List.of(first, second, metadata), true);
        assertEquals(1, plan.issues().size());
        assertEquals(List.of(1L, 2L), plan.issues().getFirst().articleIds());
        assertTrue(plan.pairScores().getFirst().specificEventMatch());
        assertFalse(plan.issues().getFirst().articleIds().contains(3L));
    }

    @Test
    void sessionConflictsVetoTitleEdgesAndUndatedTransitiveBridges() {
        var first = article(1, "뉴욕증시, 기술주 약세로 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.", 0);
        var second = article(2, first.title(), "16일 뉴욕증시에서 다우지수는 하락 마감했다.", 0);
        var bridge = article(3, first.title(), "뉴욕증시에서 다우지수는 하락 마감했다.", 0);
        var plan = clusterer.cluster(List.of(first, second, bridge), true);
        assertEquals(2, plan.issues().size());
        assertTrue(plan.issues().stream().noneMatch(issue ->
                issue.articleIds().contains(1L) && issue.articleIds().contains(2L)));
        assertEquals(List.of(2L), clusterer.eventConflictingArticleIds(List.of(first, second, bridge)).get(1L));
    }

    @Test
    void concreteOccurrenceKeepsTheBreakingNewsWindow() {
        var first = article(1, "[속보] 뉴욕증시, 유가 부담에 하락", "17일 뉴욕증시에서 다우지수는 하락 마감했다.", 0);
        var late = article(2, "나스닥 약세…기술주 매물 쏟아져", "미국증시는 17일 약세였다. 나스닥은 거래를 마쳤다.", 7);
        assertEquals(2, clusterer.cluster(List.of(first, late)).issues().size());
    }

    @Test
    void hostedOccasionAndPublicCallAreWiredIntoTheProductionClusterer() {
        var hosted = clusterer.cluster(List.of(
                article(1, "새빛, 제조 데이터 처리 개선", "새빛 AI연구원은 17일 'AI 토크 콘서트'를 개최했다.", 0),
                article(2, "새빛, 과학 난제 해결 집중", "17일 열린 'AI 토크 콘서트'에서 새빛 AI연구원이 성과를 공개했다.", 1)), true);
        assertEquals(1, hosted.issues().size());
        assertTrue(hosted.pairScores().getFirst().specificEventMatch());
        var call = clusterer.cluster(List.of(
                article(1, "최가온, 로봇 위험론 반박", "최가온 가온국 대통령과 김해솔 해솔전자 CEO가 기술을 논의했다. 최가온 대통령은 17일 '미래 서밋' 무대의 김해솔 CEO에게 전화했다.", 0),
                article(2, "김해솔, 접는 스마트폰으로 눈길", "김해솔 해솔전자 CEO는 17일 '미래 서밋' 무대에서 최가온 가온국 대통령의 전화를 받았다.", 1)), true);
        assertEquals(1, call.issues().size());
        assertTrue(call.pairScores().getFirst().specificEventMatch());
    }

    private static ClusterArticle article(long id, String title, String body, int hours) {
        var time = OffsetDateTime.parse("2026-06-18T09:00:00+09:00").plusHours(hours);
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT, id, "fixture-" + id,
                BigDecimal.ONE, time, time, List.of(), null, null, null, true);
    }
}
