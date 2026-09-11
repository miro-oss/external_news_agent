package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockEventEvidenceTest {
    @Test
    void sameCompanyTradingDayConnectsAnIndirectHeadlineWithoutAssumingMissingPrices() {
        ClusterArticle first = article(1, "AMD 주가 급등…신제품 수혜 기대", null,
                "미국 반도체기업 AMD의 주가가 크게 상승했다.\n\n17일 뉴욕 증시에서 AMD 주가는 7.4% 올랐다.");
        ClusterArticle second = article(2, "투자자 관심 쏠려…증시 하락에도 나홀로 폭등",
                "17일 뉴욕 증시가 하락했지만 AMD는 주가가 크게 상승했다.", null);
        assertTrue(evidence(first, second).matches(1, 2));
        assertTrue(evidence(first, second).matches(2, 1));
    }

    @Test
    void explicitDatesAndPriceDirectionRejectOtherDaysOrOppositeMoves() {
        ClusterArticle first = article(1, "AMD 주가 급등", "17일 뉴욕 증시에서 AMD 주가는 상승했다.", null);
        for (String text : List.of("16일 뉴욕 증시에서 AMD 주가는 상승했다.",
                "17일 뉴욕 증시에서 AMD 주가는 하락했다.")) {
            var pair = evidence(first, article(2, "AMD 주가 동향", text, null));
            assertFalse(pair.matches(1, 2));
            assertTrue(pair.conflicts(1, 2));
            assertTrue(pair.conflicts(2, 1));
        }
    }

    @Test
    void companyDateExchangeAndObservedDirectionAreRequired() {
        ClusterArticle first = article(1, "AMD 주가 급등", "17일 뉴욕 증시에서 AMD 주가는 상승했다.", null);
        for (String text : List.of("17일 뉴욕 증시에서 엔비디아 주가는 상승했다.",
                "뉴욕 증시에서 AMD 주가는 상승했다.",
                "17일 AMD 주가는 상승했다.",
                "17일 코스피에서 AMD 주가는 상승했다.",
                "17일 뉴욕 증시에서 AMD 주가는 상승할 것으로 전망했다.",
                "17일 뉴욕 증시에서 AMD 주가는 강세를 보일 전망이다.",
                "17일 뉴욕 증시에서 AMD 주가는 상승 가능성이 커졌다.",
                "17일 뉴욕 증시에서 AMD 주가는 상승을 기대한다.",
                "17일 뉴욕 증시에서 AMD 주가는 장중 상승했다가 하락했다.",
                "지난달 17일 뉴욕 증시에서 AMD 주가는 상승했다.")) {
            assertFalse(evidence(first, article(2, "주가 급등", text, null)).matches(1, 2), text);
        }
    }

    @Test
    void productAnnouncementAndSecondaryCompanyDoNotInheritThePriceEvent() {
        ClusterArticle first = article(1, "AMD 주가 급등", "17일 뉴욕 증시에서 AMD 주가는 상승했다.", null);
        assertFalse(evidence(first, article(2, "AMD 신제품 공개", first.summary(), null)).matches(1, 2));
        assertFalse(evidence(first, article(2, "주가 급등",
                "17일 뉴욕 증시에서 엔비디아 주가는 상승했다.\n\n한편 AMD도 올랐다.", null)).matches(1, 2));
        assertFalse(evidence(first, article(2, "반도체 주가 강세",
                "17일 뉴욕 증시에서 AMD와 엔비디아의 주가가 상승했다.", null)).matches(1, 2));
        assertFalse(evidence(article(1, "엔비디아 주가 강세",
                        "17일 뉴욕 증시에서 엔비디아의 주가가 상승했다.", null),
                article(2, "반도체 주가 강세",
                        "17일 뉴욕 증시에서 AMD와 엔비디아의 주가가 상승했다.", null)).matches(1, 2));
        assertFalse(evidence(first, article(2, "수혜 기업 주가 급등",
                "17일 뉴욕 증시에서 AMD에 장비를 공급하는 온세미컨덕터의 주가가 7% 상승했다.", null)).matches(1, 2));
    }

    @Test
    void tradingDayIsAttachedToTheMarketClauseInsteadOfTheFirstDate() {
        ClusterArticle first = article(1, "AMD 주가 급등", "17일 뉴욕 증시에서 AMD 주가는 상승했다.", null);
        ClusterArticle second = article(2, "신제품 공개 효과로 주가 강세", null,
                "AMD의 주가가 상승했다.\n\n16일 신제품 발표에 이어 17일 뉴욕 증시에서 AMD 주가는 7% 올랐다.");
        assertTrue(evidence(first, second).matches(1, 2));
        assertFalse(evidence(first, second).conflicts(1, 2));
        assertFalse(evidence(first, article(2, "AMD 주가 동향",
                "17일 실적을 발표했다. 뉴욕 증시에서 AMD 주가는 상승했다.", null)).matches(1, 2));
    }

    private static StockEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new StockEventEvidence(List.of(first, second), new BreakingNewsDetector());
    }

    private static ClusterArticle article(long id, String title, String summary, String body) {
        OffsetDateTime time = OffsetDateTime.parse("2026-06-18T09:00:00+09:00");
        return new ClusterArticle(id, 1, title, summary, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT,
                id, "fixture-" + id, null, time, time, List.of(), null, null, null, true);
    }
}
