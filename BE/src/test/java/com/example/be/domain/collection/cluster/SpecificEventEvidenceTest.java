package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecificEventEvidenceTest {
    @Test
    void differentStatisticsFromOneQuarterlyRelease() {
        var first = article(1, "GDP 성장하고 GNI 2.7% 증가", "3분기 수출은 2.2% 증가했다.", null);
        var second = article(2, "한국 명목 GDP, 경제규모 확대", null,
                "한국은행은 3분기 잠정 통계를 발표했다. 실질 GNI는 2.7% 늘었다. 수출은 2.2% 증가했다.");
        assertMatch(first, second);
    }

    @Test
    void missingPeriodNeedsNamedStatisticRecordIntervalAndCountry() {
        var first = article(1, "한국 명목 GDP 19년 만에 최고", "수출 디플레이터가 18.7% 상승했다.", null);
        var second = article(2, "한국 국민소득 19년 만의 호황", null,
                "한국은행이 발표한 3분기 잠정 통계다. 수출 디플레이터는 18.7% 상승했다.");
        assertMatch(first, second);
        assertNoMatch(first, article(2, "한국 국민소득 성장세", null, second.body()));
        assertNoMatch(first, article(2, "국민소득 19년 만의 호황", null,
                "수출 디플레이터는 18.7% 상승했다."));
    }

    @Test
    void distinguishesQuarterYearRegionIssuerAndReleaseEdition() {
        var first = article(1, "한국 GDP 성장 통계", null,
                "한국은행이 2026년 3분기 잠정 통계를 발표했다. 실질 GNI 2.7%, 수출 2.2% 증가다.");
        for (String incompatible : List.of(
                first.body().replace("3분기", "4분기"), first.body().replace("2026년", "2025년"),
                first.body().replace("잠정", "개정"), first.body().replace("잠정", "속보"),
                first.body().replace("한국은행", "통계청"))) {
            var second = article(2, "한국 GDP 성장 통계", null, incompatible);
            assertNoMatch(first, second);
            assertTrue(evidence(first, second).conflicts(1, 2));
        }
        var overseas = article(2, "일본 GDP 성장 통계", null, first.body().replace("한국은행", "일본중앙은행"));
        assertNoMatch(first, overseas);
        assertTrue(evidence(first, overseas).conflicts(1, 2));
    }

    @Test
    void relativeYearAndAmbiguousFutureBareDayRemainSeparate() {
        var first = article(1, "한국 GDP 성장 통계", null,
                "한국은행이 올해 3분기 잠정 통계를 발표했다. 실질 GNI 2.7%, 수출 2.2% 증가다.");
        var previous = article(2, first.title(), null, first.body().replace("올해", "지난해"));
        assertNoMatch(first, previous);
        assertTrue(evidence(first, previous).conflicts(1, 2));
        var market = article(1, "코스피 상승", null, "코스피가 지난 30일 상승했다. 4234.56에 출발했다.");
        assertNoMatch(market, article(2, "코스피 강세", null, market.body()));
    }

    @Test
    void genericRatesAndUnrelatedHeadlinesDoNotEstablishReleaseIdentity() {
        var first = article(1, "한국 GDP 성장 통계", null,
                "한국은행이 3분기 잠정 통계를 발표했다. 수출은 2.2% 증가했다.");
        assertNoMatch(first, article(2, "한국 국민소득 성장 통계", null, first.body()));
        assertNoMatch(first, article(2, "화학업체 신제품 판매", null, first.body()));
    }

    @Test
    void numbersAreBoundToNamedIndicators() {
        var first = article(1, "한국 GDP 성장 통계", null,
                "한국은행이 3분기 잠정 통계를 발표했다. 실질 GNI 2.7%, 수출 2.2% 증가다.");
        assertNoMatch(first, article(2, "한국 GDP 성장 통계", null,
                "한국은행이 3분기 잠정 통계를 발표했다. 실질 GNI 2.2%, 수출 2.7% 증가다."));
    }

    @Test
    void marketNeedsExplicitTradingDayAndExactOpeningValue() {
        var first = article(1, "코스피 상승 폭 확대", null,
                "코스피는 12일 오전 강세다. 지수는 4,234.56으로 출발했다.");
        var second = article(2, "수급 회복에 지수 탈환", null,
                "코스피가 12일 장중 상승했다. 전장보다 오른 4234.56에 출발했다.");
        assertMatch(first, second);
        assertNoMatch(first, article(2, second.title(), null, second.body().replace("12일 ", "")));
        assertNoMatch(first, article(2, second.title(), null, second.body().replace("4234.56", "4299.21")));
    }

    @Test
    void differentTradingDatesConflictEvenWithIdenticalIndexNumbers() {
        var first = article(1, "코스피 상승", null, "코스피가 10월 12일 상승했다. 4234.56에 출발했다.");
        for (String date : List.of("10월 13일", "9월 12일", "2025년 10월 12일")) {
            var second = article(2, "코스피 강세", null, "코스피는 " + date + " 상승했다. 4234.56에 출발했다.");
            assertNoMatch(first, second);
            assertTrue(evidence(first, second).conflicts(1, 2));
        }
    }

    @Test
    void anotherIndexAndEtfPortfolioAreSeparateFromMarketReports() {
        var first = article(1, "코스피 상승", null, "코스피가 12일 상승했다. 4234.56에 출발했다.");
        assertNoMatch(first, article(2, "코스닥 상승", null, first.body().replace("코스피", "코스닥")));
        assertNoMatch(first, article(2, "ETF 비중 조정…코스피 상승주 매도", null, first.body()));
    }

    @Test
    void summitOverviewMatchesCountryAliasesAndPolicyDomains() {
        var first = article(1, "韓佛 정상회담, 협력문건 채택", "방산, AI, 양자, 반도체 협력을 추진한다.", null);
        var second = article(2, "한·프, 방산 협력과 교역 확대", "반도체와 양자, AI 협력을 확대한다.",
                "한국과 프랑스 정상은 12일 정상회담을 열고 방산 협력을 강화했다.");
        assertMatch(first, second);
        assertNoMatch(first, article(2, "한·미, 방산 협력과 교역 확대", second.summary(), second.body()));
        assertNoMatch(first, article(2, "삼성전자, 한·프 정상회담 계기 투자 협약 체결", second.summary(), second.body()));
        assertNoMatch(first, article(2, "새빛전자, 한·프 정상회담 계기 투자 협약", second.summary(), second.body()));
    }

    @Test
    void summitOverviewOnAnotherExplicitDayDoesNotMatch() {
        var first = article(1, "韓佛 정상회담, 협력문건 채택", "방산, AI, 양자, 반도체 협력을 추진한다.",
                "한국과 프랑스 정상은 12일 정상회담을 열었다.");
        var second = article(2, "한·프, 방산 협력과 교역 확대", first.summary(), first.body().replace("12일", "13일"));
        assertNoMatch(first, second);
    }

    @Test
    void sameExplicitSummitDayMatchesAcrossMidnightWithin48Hours() {
        var first = article(1, "韓佛 정상회담, 협력문건 채택", "방산, AI, 양자, 반도체 협력을 추진한다.",
                "한국과 프랑스 정상은 12일 정상회담을 열었다.", "2026-10-12T23:50:00+09:00");
        var second = article(2, "한·프, 방산 협력과 교역 확대", first.summary(), first.body(),
                "2026-10-13T00:10:00+09:00");
        assertMatch(first, second);
        assertNoMatch(first, article(2, second.title(), second.summary(), second.body().replace("12일", "13일"),
                "2026-10-13T00:10:00+09:00"));
        assertMatch(first, article(2, second.title(), second.summary(), second.body(),
                "2026-10-14T23:50:00+09:00"));
        assertNoMatch(first, article(2, second.title(), second.summary(), second.body(),
                "2026-10-14T23:51:00+09:00"));
    }

    @Test
    void summitMissingEitherExplicitDayKeepsSamePublicationDayFallback() {
        String title = "한국·프랑스 정상회담, 협력문건 채택";
        String summary = "방산, AI, 양자, 반도체 협력을 추진한다.";
        String dated = "한국과 프랑스 정상은 12일 정상회담을 열었다.";
        String undated = "한국과 프랑스 정상은 정상회담을 열었다.";
        var first = article(1, title, summary, undated, "2026-10-12T23:50:00+09:00");
        assertMatch(first, article(2, title, summary, undated, "2026-10-12T23:55:00+09:00"));
        assertMatch(first, article(2, title, summary, dated, "2026-10-12T23:55:00+09:00"));
        assertNoMatch(first, article(2, title, summary, undated, "2026-10-13T00:10:00+09:00"));
        assertNoMatch(first, article(2, title, summary, dated, "2026-10-13T00:10:00+09:00"));
        assertNoMatch(article(1, title, summary, dated, "2026-10-12T23:50:00+09:00"),
                article(2, title, summary, undated, "2026-10-13T00:10:00+09:00"));
    }

    @Test
    void summitCountriesDistinguishIndiaFromIndonesiaAndKeepDomesticAlias() {
        String summary = "방산, AI, 양자, 반도체 협력을 추진한다.";
        var indonesia = article(1, "한국·인도네시아 정상회담, 협력문건 채택", summary,
                "한국과 인도네시아 정상은 12일 정상회담을 열었다.");
        assertMatch(indonesia, article(2, "우리나라와 인도네시아, 방산 협력과 교역 확대", summary,
                indonesia.body()));
        assertNoMatch(indonesia, article(2, "한국·인도 정상회담, 협력문건 채택", summary,
                "한국과 인도 정상은 12일 정상회담을 열었다."));
        var india = article(1, "한국·인도 정상회담, 협력문건 채택", summary,
                "한국과 인도 정상은 12일 정상회담을 열었다.");
        assertMatch(india, article(2, "우리나라와 인도, 방산 협력과 교역 확대", summary, india.body()));
    }

    @Test
    void projectProposalNeedsNameAndTwoCapacityClues() {
        var first = article(1, "해외 투자 후보, 동부 발전소 건설 유력", null,
                "새울시 청솔 발전소 건설 방안을 검토한다. 2.6GW 가스터빈과 5.8GW 복합화력 설비를 짓는 계획이다.");
        var second = article(2, "청솔 발전소 투자 검토…건설사 참여", "2.6GW 설비에 이어 5.8GW 설비를 추가한다.", null);
        assertMatch(first, second);
        assertNoMatch(first, article(2, "푸른숲 발전소 투자 검토…건설사 참여", second.summary(), null));
        assertNoMatch(first, article(2, second.title(), "2.6GW 설비를 추가한다.", null));
        assertNoMatch(article(1, "정부 발전소 건설 후보 유력", null, "2.6GW 및 5.8GW 설비 계획"),
                article(2, "정부 발전소 건설 후보 검토", null, "2.6GW 및 5.8GW 설비 계획"));
    }

    @Test
    void projectProposalAndConstructionOrOperationConflict() {
        var first = article(1, "청솔 발전소 투자 검토", null,
                "청솔 발전소를 검토한다. 2.6GW 설비와 5.8GW 설비를 계획한다.");
        for (String stage : List.of("착공", "준공", "상업 가동", "계약 체결")) {
            var second = article(2, "청솔 발전소 " + stage, null,
                    "청솔 발전소 소식이다. 2.6GW 설비와 5.8GW 설비를 갖춘다.");
            assertNoMatch(first, second);
            assertTrue(evidence(first, second).conflicts(1, 2));
        }
    }

    @Test
    void demonstrationConnectsForumOverviewToFocusedReport() {
        var first = article(1, "휴머노이드 고도화 성과 공개", null,
                "휴머노이드 기술을 논의한다. 새빛연구원은 12일 '2026 미래로봇 포럼'을 개최했다. "
                        + "로봇 '누리봇(NURIBOT)'은 전신 동작을 시연했다.");
        var second = article(2, "춤추는 로봇, 유연한 동작 시연", null,
                "새빛연구원은 12일 로봇 '누리봇'을 시연했다. '2026 미래로봇 포럼' 무대에서 동작을 선보였다.");
        assertMatch(first, second);
    }

    @Test
    void forumNameCannotJoinDifferentProductsInstitutesDaysOrVersions() {
        var first = article(1, "휴머노이드 고도화 성과 공개", null,
                "새빛연구원은 12일 '2026 미래로봇 포럼'에서 로봇 '누리봇' v1.2를 시연했다.");
        for (String changed : List.of(first.body().replace("누리봇", "바다봇"),
                first.body().replace("새빛연구원", "해솔연구원"), first.body().replace("12일", "13일"),
                first.body().replace("v1.2", "v1.3"))) {
            assertNoMatch(first, article(2, "로봇의 전신 동작 시연", null, changed));
        }
    }

    @Test
    void commonQuotedDanceDoesNotIdentifyDifferentRobots() {
        var first = article(1, "휴머노이드 고도화 성과 공개", null,
                "새빛연구원은 12일 '2026 미래로봇 포럼'에서 로봇 '누리봇'을 시연했다. "
                        + "누리봇은 '국민체조' 동작을 선보였다.");
        var second = article(2, "로봇의 전신 동작 시연", null, first.body().replace("누리봇", "바다봇"));
        assertNoMatch(first, second);
    }

    @Test
    void distantOrMarkedBackgroundCannotSupplyTheMainEvent() {
        var first = article(1, "휴머노이드 고도화 성과 공개", null,
                "새빛연구원은 12일 '2026 미래로봇 포럼'에서 로봇 '누리봇'을 시연했다.");
        assertNoMatch(first, article(2, "로봇 가격과 공급망 분석", null,
                "로봇 시장을 분석한다. " + "일반적인 연구 배경을 검토한다. ".repeat(100) + first.body()));
        assertNoMatch(first, article(2, "로봇 가격과 공급망 분석", null,
                "로봇 시장을 분석한다.\n\n한편 " + first.body()));
        var economic = article(1, "한국 GDP 통계 발표", null,
                "한국은행 3분기 잠정 통계다. GNI 2.7%, 수출 2.2% 증가했다.");
        assertNoMatch(economic, article(2, "한국 GDP 통계 발표", "한편 " + economic.body(),
                "다른 조사의 표본집계 방식을 설명한다."));
    }

    @Test
    void absentFactsDoNotBecomeConflictsOrEventEvidence() {
        var first = article(1, "코스피 상승", null, "코스피가 12일 상승했다. 4234.56에 출발했다.");
        var second = article(2, "코스피 강세", null, null);
        assertNoMatch(first, second);
        assertFalse(evidence(first, second).conflicts(1, 2));
        assertFalse(evidence(first, second).matches(1, 99));
        assertFalse(evidence(first, second).conflicts(1, 99));
    }

    private static void assertMatch(ClusterArticle first, ClusterArticle second) {
        var evidence = evidence(first, second);
        assertTrue(evidence.matches(1, 2));
        assertTrue(evidence.matches(2, 1));
        assertFalse(evidence.conflicts(1, 2));
    }

    private static void assertNoMatch(ClusterArticle first, ClusterArticle second) {
        var evidence = evidence(first, second);
        assertFalse(evidence.matches(1, 2));
        assertFalse(evidence.matches(2, 1));
    }

    private static SpecificEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new SpecificEventEvidence(List.of(first, second), new BreakingNewsDetector());
    }

    private static ClusterArticle article(long id, String title, String summary, String body) {
        OffsetDateTime time = OffsetDateTime.parse("2026-10-12T12:00:00+09:00").plusMinutes(id);
        return article(id, title, summary, body, time.toString());
    }

    private static ClusterArticle article(long id, String title, String summary, String body, String timestamp) {
        OffsetDateTime time = OffsetDateTime.parse(timestamp);
        return new ClusterArticle(id, 1, title, summary, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT,
                id, "fixture-" + id, new BigDecimal("0.8"), time, time, List.of(), null, null, null, true);
    }
}
