package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MarketIndexQuoteEvidenceTest {
    @Test
    void normalizesKoreanUnitsCommasAndFullWidthPunctuation() {
        var quotes = MarketIndexQuoteEvidence.extract("다우지수는 0.31% 내린 4만1,234.50에 마감했다. "
                + "S＆P500지수는 0.42% 떨어진 6천789.12로 거래를 마쳤다. 나스닥은 0.53% 내린 23,456.78을 기록했다.");
        assertEquals(3, quotes.size());
        assertEquals(0, quotes.get(MarketIndexQuoteEvidence.Index.DOW).level().compareTo(new BigDecimal("41234.50")));
        assertEquals(0, quotes.get(MarketIndexQuoteEvidence.Index.SP500).level().compareTo(new BigDecimal("6789.12")));
        assertEquals(0, quotes.get(MarketIndexQuoteEvidence.Index.NASDAQ).rate().compareTo(new BigDecimal("-0.53")));
    }

    @Test
    void twoExactSignedReturnsAndOneExactLevelAreRequired() {
        var anchor = MarketIndexQuoteEvidence.extract("다우는 0.31% 내린 41234.50에 마감했다. 나스닥은 0.53% 내린 23456.78에 마감했다.");
        var snapshot = MarketIndexQuoteEvidence.extract("다우는 0.31% 하락한 41234.49를 기록했다. 나스닥은 0.53% 하락한 23456.78을 기록했다.");
        assertTrue(MarketIndexQuoteEvidence.matches(anchor, snapshot));
        assertFalse(MarketIndexQuoteEvidence.matches(anchor, MarketIndexQuoteEvidence.extract("나스닥은 0.53% 내린 23456.78에 마감했다.")));
        assertFalse(MarketIndexQuoteEvidence.matches(anchor, MarketIndexQuoteEvidence.extract("다우는 0.31% 내린 41234.49에 마감했다. 나스닥은 0.53% 내린 23456.77에 마감했다.")));
        assertFalse(MarketIndexQuoteEvidence.matches(anchor, MarketIndexQuoteEvidence.extract("다우는 0.31% 오른 41234.50에 마감했다. 나스닥은 0.53% 내린 23456.78에 마감했다.")));
    }

    @Test
    void pointChangesAreNotMistakenForIndexLevels() {
        var quotes = MarketIndexQuoteEvidence.extract("다우지수는 전장보다 120.15포인트(0.31%) 하락한 4만1234.50에 마감했다.");
        assertEquals(1, quotes.size());
        assertEquals(0, quotes.get(MarketIndexQuoteEvidence.Index.DOW).level().compareTo(new BigDecimal("41234.50")));
    }

    @Test
    void companyPricesRoundedRangesAndIntradayObservationsAreNotExactIndexQuotes() {
        for (String text : java.util.List.of(
                "나스닥시장에서 회사는 3.2% 오른 349.39달러에 마감했다.",
                "나스닥은 0.5% 안팎 하락한 23456.78에 마감했다.",
                "나스닥은 장중 0.53% 내린 23456.78을 기록했다.",
                "나스닥은 0.53% 상승할 전망이며 23456.78에 이를 것으로 예상했다.")) {
            assertTrue(MarketIndexQuoteEvidence.extract(text).isEmpty(), text);
        }
    }

    @Test
    void conflictingDuplicateObservationsCannotChooseAFavorableQuote() {
        assertTrue(MarketIndexQuoteEvidence.extract("다우는 0.31% 내린 41234.50에 마감했다. 다우는 0.31% 오른 41234.50에 마감했다.").isEmpty());
    }

    @Test
    void qualifiersBeforeAnIndexCannotBeReusedAsCurrentClosingNumbers() {
        for (String prefix : java.util.List.of("장중 ", "전날 ", "어제 ", "지난주 ", "개장 직후 ", "예상대로 ")) {
            assertTrue(MarketIndexQuoteEvidence.extract(prefix + "나스닥은 0.53% 내린 23456.78을 기록했다.").isEmpty(), prefix);
        }
        assertEquals(1, MarketIndexQuoteEvidence.extract("나스닥은 전날보다 0.53% 내린 23456.78에 마감했다.").size());
    }

    @Test
    void parallelIndexListingsAndContextualRatesKeepEachIndexsOwnResult() {
        var quotes = MarketIndexQuoteEvidence.extract("다우존스30: 국채금리 4% 부담에 0.31% 내린 41234.50에 마감했다.\n"
                + "S&P500: 기술업종(-1.2%) 약세로 0.42% 하락한 6789.12를 기록했다.\n"
                + "나스닥은 0.53% 하락한 23456.78, 다우지수는 0.31% 내린 41234.50이었다.");
        assertEquals(3, quotes.size());
        assertEquals(0, quotes.get(MarketIndexQuoteEvidence.Index.DOW).rate().compareTo(new BigDecimal("-0.31")));
        assertEquals(0, quotes.get(MarketIndexQuoteEvidence.Index.SP500).rate().compareTo(new BigDecimal("-0.42")));
        assertEquals(0, quotes.get(MarketIndexQuoteEvidence.Index.NASDAQ).level().compareTo(new BigDecimal("23456.78")));
    }

    @Test
    void pastObservationsListedCompaniesAndOtherIndexFamiliesDoNotBorrowAnIndexName() {
        for (String text : java.util.List.of("나스닥은 전날 0.53% 내린 23456.78을 기록했다.",
                "나스닥 상장사 회사는 0.53% 하락한 234.56을 기록했다.",
                "다우 운송지수는 0.31% 내린 12345.67에 마감했다.")) {
            assertTrue(MarketIndexQuoteEvidence.extract(text).isEmpty(), text);
        }
    }

    @Test
    void exactSectorAnchorRejectsAnyContradictorySharedLevelOrSignedRate() {
        String sector = "필라델피아 반도체지수는 2.35% 내린 4200.50에 마감했다. ";
        var anchor = MarketIndexQuoteEvidence.extract(sector + "다우는 0.31% 내린 41234.50에 마감했다.");
        assertTrue(MarketIndexQuoteEvidence.matchesExactSectorQuote(anchor,
                MarketIndexQuoteEvidence.extract("SOX는 2.350% 내린 4천200.5를 기록했다.")));
        for (String quote : java.util.List.of(
                "다우는 0.31% 내린 41234.51에 마감했다.",
                "다우는 0.31% 오른 41234.50에 마감했다.",
                "다우는 0.32% 내린 41234.50에 마감했다.")) {
            var other = MarketIndexQuoteEvidence.extract(sector + quote);
            assertFalse(MarketIndexQuoteEvidence.matchesExactSectorQuote(anchor, other));
            assertFalse(MarketIndexQuoteEvidence.matchesExactSectorQuote(other, anchor));
        }
        assertFalse(MarketIndexQuoteEvidence.matchesExactSectorQuote(anchor,
                MarketIndexQuoteEvidence.extract("다우는 0.31% 내린 41234.50에 마감했다.")));
    }
}
