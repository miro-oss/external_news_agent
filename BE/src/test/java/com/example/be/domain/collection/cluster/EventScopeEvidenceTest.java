package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventScopeEvidenceTest {
    @Test
    void portfolioAdjustmentDoesNotBecomeAMarketRallyBecauseItsBackgroundRallied() {
        var adjustment = article(1, "ETF 리밸런싱 앞둔 가온해솔, 매물 대기",
                "가온전자와 해솔반도체의 ETF 비중 조절이 예정됐다. 두 회사 주가는 최근 상승했다.");
        var rally = article(2, "가온해솔 강세…한빛지수 재등정",
                "한빛지수가 장중 상승했다. 가온전자와 해솔반도체의 강세가 지수를 이끌었다.");
        assertConflict(adjustment, rally);
    }

    @Test
    void compositionSynonymsAndChangedAmountsStillConflictWithObservedTrading() {
        for (String wording : List.of("지수 정기 변경", "비중 재조정", "구성 종목 편출", "종목 비중 상한")) {
            var adjustment = article(10, "가온해솔 " + wording + " 앞두고 870억원 매도 예상",
                    "오는 23일 상장지수펀드의 매매가 예정됐다.");
            var market = article(98, "외국인 매수에 한빛지수 2400선 돌파",
                    "한빛지수가 21일 오전 2418.6으로 출발해 상승 폭을 키웠다.");
            assertConflict(adjustment, market);
        }
    }

    @Test
    void twoViewsOfTheSameAdjustmentOrRallyRemainCompatible() {
        var rebalance = article(1, "가온해솔 ETF 리밸런싱 임박", "지수의 정기 변경이 예정됐다.");
        var weights = article(2, "비중 조절 앞둔 가온해솔, 매도 물량 예고", "가온해솔 비중 조정이 예정됐다.");
        var rally = article(3, "한빛지수 급등…외국인 매수", "한빛지수가 상승했다.");
        var broadRally = article(4, "가온해솔 강세에 한빛지수 회복",
                "한빛지수가 상승했고 여러 업종의 등락이 엇갈렸다. ETF 비중 조절은 향후 변수다.");
        assertCompatible(rebalance, weights);
        assertCompatible(rally, broadRally);
    }

    @Test
    void ordinaryEtfPerformanceDoesNotMeanRecomposition() {
        assertCompatible(article(1, "반도체 ETF 강세…투자심리 회복", "한빛지수와 ETF가 동반 상승했다."),
                article(2, "한빛지수 급등", "외국인 매수세가 지수 상승을 이끌었다."));
    }

    @Test
    void distinctReportsInADigestConflictWithAnIncludedSingleAgreement() {
        var single = article(1, "가온전자, 푸른AI와 공동 개발", "가온전자는 푸른AI와 파트너십을 체결했다.");
        for (String title : List.of("[증시키워드] 가온전자 동맹·해솔조선 수주", "개장 전 관심 기업들")) {
            var digest = article(2, title, "개장 전 종목별 이슈를 살펴본다.\n\n"
                    + "가장 관심을 끈 곳은 가온전자다. 가온전자는 푸른AI와 공동 개발을 발표했다.\n\n"
                    + "해솔조선은 해군 함정 수주 계약을 체결했다.");
            assertConflict(single, digest);
        }
    }

    @Test
    void independentAgreementsNeedNotUseDifferentActionWords() {
        assertConflict(article(1, "가온전자, 푸른AI와 파트너십", "가온전자는 푸른AI와 공동 개발에 나선다."),
                article(2, "[종목 브리핑] 가온전자·해솔통신 새 동맹",
                        "가온전자는 푸른AI와 파트너십을 체결했다.\n\n해솔통신은 별빛연구소와 파트너십을 체결했다."));
    }

    @Test
    void digestParagraphsAcceptSingleLineBreaksAndDifferentSubjectParticles() {
        assertConflict(article(1, "가온전자와 푸른AI 제휴", "가온전자는 푸른AI와 파트너십을 체결했다."),
                article(2, "[기업 브리핑] 가온전자 동맹·해솔조선 수주",
                        "가온전자가 푸른AI와 공동 개발을 발표했다.\n해솔조선 역시 해외 함정 수주 계약을 체결했다."));
    }

    @Test
    void multipleParticipantsAndInvestmentInTheSameAgreementAreNotADigest() {
        var first = article(1, "[증시키워드] 가온전자·푸른AI 공동 개발", "가온전자는 푸른AI에 지분 투자했다.\n\n"
                + "푸른AI는 가온전자와 파트너십을 맺고 반도체 모델을 공동 개발한다.");
        var second = article(2, "가온전자, 푸른AI와 제휴", "가온전자는 푸른AI와 파트너십을 체결했다.");
        assertCompatible(first, second);
    }

    @Test
    void singleAgreementInAMarkedColumnDoesNotInheritIndependentBackgroundCompanies() {
        var overview = article(1, "[증시키워드] 가온전자, 푸른AI와 파트너십",
                "가온전자는 푸른AI와 공동 개발 협약을 체결했다.\n\n"
                        + "한편 해솔통신은 지난해 신제품을 출시하며 업계의 주목을 받았다.\n\n"
                        + "별빛조선은 이전에 함정 수주 계약을 발표한 바 있다.");
        var focused = article(2, "가온전자, 푸른AI에 지분 투자", "가온전자는 푸른AI와 파트너십을 체결했다.");
        assertCompatible(overview, focused);
    }

    @Test
    void digestMarkerAndSeveralRisingCompaniesAloneAreNotIndependentEvents() {
        assertCompatible(article(1, "[증시 브리핑] 한빛지수 상승", "가온전자는 상승했다.\n\n해솔반도체는 강세다."),
                article(2, "가온해솔 상승에 한빛지수 회복", "한빛지수는 외국인 매수세에 올랐다."));
    }

    @Test
    void summitAndForumOverviewsAreNotMarketDigests() {
        assertCompatible(article(1, "한빛·해솔 정상회담 경제 협력 확대",
                        "한빛정부는 해솔정부와 협약을 체결했다.\n\n미래기업은 현지 공장 착공 계획을 발표했다."),
                article(2, "정상회담서 산업 협약 체결", "한빛정부와 해솔정부가 공동 개발에 합의했다."));
        assertCompatible(article(3, "기계포럼서 로봇 공개와 산업 논의",
                        "새빛연구원은 기계포럼에서 로봇을 공개했다.\n\n별빛연구소는 신제품을 발표했다."),
                article(4, "로봇 춤 시연 현장", "기계포럼에서 로봇 시연이 열렸다."));
    }

    @Test
    void aMissingBodyUsesProvidedSummaryAndUnknownArticlesAddNoConflict() {
        var summaryOnly = new ClusterArticle(2, 1, "[종목 브리핑] 개별 기업 소식",
                "가온전자는 지분 투자를 발표했다.\n\n해솔조선은 함정 수주에 성공했다.", null,
                FetchStatus.METADATA_ONLY, 2, "fixture-2", null, null, null, List.of(), null, null, null, true);
        var single = article(1, "가온전자 지분 투자", "가온전자는 지분 투자를 발표했다.");
        assertConflict(single, summaryOnly);
        var evidence = evidence(List.of(single, summaryOnly));
        assertFalse(evidence.conflicts(1, 999));
        assertFalse(evidence.conflicts(2, 999));
    }

    @Test
    void primaryMarketHeadlineDoesNotInheritItsSecondaryRebalanceExplanation() {
        assertCompatible(article(1, "한빛지수 상승…ETF 리밸런싱은 향후 변수",
                        "한빛지수가 오전 상승했다.\n\nETF 리밸런싱은 다음 주에 예정돼 있다."),
                article(2, "한빛지수 급등", "한빛지수가 매수세에 상승했다."));
    }

    private void assertConflict(ClusterArticle first, ClusterArticle second) {
        var evidence = evidence(List.of(first, second));
        assertTrue(evidence.conflicts(first.articleId(), second.articleId()));
        assertTrue(evidence.conflicts(second.articleId(), first.articleId()));
    }

    private void assertCompatible(ClusterArticle first, ClusterArticle second) {
        var evidence = evidence(List.of(first, second));
        assertFalse(evidence.conflicts(first.articleId(), second.articleId()));
        assertFalse(evidence.conflicts(second.articleId(), first.articleId()));
    }

    private EventScopeEvidence evidence(List<ClusterArticle> articles) {
        return new EventScopeEvidence(articles, new BreakingNewsDetector());
    }

    private ClusterArticle article(long id, String title, String body) {
        var time = OffsetDateTime.parse("2026-03-21T10:00:00+09:00");
        return new ClusterArticle(id, 1, title, null, body, FetchStatus.FULLTEXT,
                id, "fixture-" + id, new BigDecimal("0.8"), time, time, List.of(),
                null, null, null, true);
    }
}
