package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommercialProductEventEvidenceTest {
    @Test
    void namedProductWithoutDigitsConnectsPartnerAndCompetitorAngles() {
        var first = article(1, "한빛전자 美·아시아 공략…TSMC 생산", null,
                "한빛전자는 신형 프로세서 '가온'을 2027년 미국과 아시아 시장에 수출할 계획이다. "
                        + "이 프로세서는 3nm 공정으로 제작된다.");
        var second = article(2, "인텔·AMD 독점 시장에 한빛전자 도전", null,
                "한빛전자가 서버용 반도체 '가온'을 내년 미국과 아시아 시장에 수출한다. "
                        + "제품에는 3나노미터 공정이 적용된다.");
        assertMatch(first, second);
        assertEquals("한빛전자", evidence(first, second).subject(1));
    }

    @Test
    void ownerSeparatesProductMakerFromContractManufacturer() {
        var first = product(1);
        var second = article(2, "한빛전자 새 제품 판매…대만 기업 생산", null,
                "TSMC가 생산하는 한빛전자의 CPU '가온'을 2027년 미국 시장에 수출할 예정이다. "
                        + "3nm 공정을 사용한다.");
        assertMatch(first, second);
        assertEquals("한빛전자", evidence(first, second).subject(2));
        var processOwner = article(2, second.title(), null,
                "한빛전자는 TSMC의 3nm 공정을 적용한 CPU '가온'을 2027년 미국 시장에 수출한다.");
        assertMatch(first, processOwner);
    }

    @Test
    void competitorInQuotedHeadlineDoesNotBecomeTheFocalMaker() {
        var first = product(1);
        var second = article(2, "한빛전자 제품 수출, '인텔·AMD 아성에 도전'", null,
                "인텔과 AMD가 장악한 시장에서 한빛전자는 프로세서 '가온'을 2027년 미국 시장에 수출한다. "
                        + "생산에는 3nm 공정을 활용한다.");
        assertMatch(first, second);
        assertEquals("한빛전자", evidence(first, second).subject(2));
    }

    @Test
    void openingQuotationAndCountryPrefixLeaveTheCommercialMakerVisible() {
        String body = "새빛컴퓨팅은 하늘 프로세서를 2028년 미국에 수출할 계획이다. 3nm 공정을 사용한다.";
        var first = article(1, "새빛컴퓨팅 하늘 칩 미국 수출 추진", null, body);
        for (String prefix : List.of("日", "일본")) {
            var second = article(2, "“기존 독점 깬다”… " + prefix + " 새빛컴퓨팅, 하늘 칩 미국 수출 추진",
                    null, body);
            assertMatch(first, second);
            assertEquals("새빛컴퓨팅", evidence(first, second).subject(2));
        }
    }

    @Test
    void skippingQuotationAndCountryDoesNotSkipAnotherCompaniesFocalAction() {
        String body = "새빛컴퓨팅은 하늘 프로세서를 2028년 미국에 수출할 계획이다. 3nm 공정을 사용한다.";
        var first = article(1, "새빛컴퓨팅 하늘 칩 미국 수출 추진", null, body);
        for (String title : List.of(
                "“신제품 살폈다”… 美 구글, 새빛컴퓨팅 신제품 평가",
                "“기존 독점 깬다”… 美 구글, 새빛컴퓨팅 칩 탑재한 신제품 출시",
                "“기존 독점 깬다”… 美 구글 신제품 출시… 日 새빛컴퓨팅 칩 수출 추진")) {
            var second = article(2, title, null, body);
            assertNoMatch(first, second);
            assertNull(evidence(first, second).subject(2));
        }
    }

    @Test
    void organizationAliasesCanonicalizeOnlyTheIdentifiedMaker() {
        var first = article(1, "후지쓰 해외 신제품 공급", null,
                "후지쓰는 CPU '누리온'을 2027년 미국 시장에 수출한다. 4nm 공정을 적용한다.");
        var second = article(2, "Fujitsu 제품 수출 계획", null,
                "후지쯔가 프로세서 '누리온'을 내년 미국 시장에 수출할 예정이다. 4나노 공정으로 제조한다.");
        assertMatch(first, second);
    }

    @Test
    void differentIndependentProductNamesAreNotAProductDictionary() {
        for (String name : List.of("새롬", "오로라", "zenith", "리브라 7")) {
            var first = replace(product(1), "가온", name);
            var second = replace(product(2), "가온", name);
            assertMatch(first, second);
        }
        assertNoMatch(product(1), replace(product(2), "가온", "새롬"));
    }

    @Test
    void unquotedProductFollowedByItsCategoryIsAnExplicitName() {
        var first = product(1);
        var second = article(2, "한빛전자 수출 추진", null,
                "한빛전자는 가온 프로세서를 2027년 미국 시장에 수출할 계획이다. 3nm 공정을 사용한다.");
        assertMatch(first, second);
    }

    @Test
    void additionalFactsCountCategoriesAndDoNotCountTwoMarketsAsTwoFacts() {
        var first = product(1);
        var sparse = article(2, "한빛전자 美·아시아 공략", null,
                "한빛전자는 CPU '가온'을 미국과 아시아 시장에 수출할 계획이다.");
        assertNoMatch(first, sparse);
        assertNull(evidence(first, sparse).subject(2));
        var noYear = article(2, sparse.title(), null, sparse.body() + " 3nm 공정을 적용한다.");
        assertMatch(first, noYear);
    }

    @Test
    void explicitHeadlineDestinationCanComplementSubstantiveSummary() {
        var first = product(1);
        var second = article(2, "한빛전자 미국 시장 공략",
                "한빛전자는 CPU '가온'을 수출할 계획이다. 3나노 공정을 적용한다.", null);
        assertMatch(first, second);
        assertNoMatch(first, article(2, "다른전자 미국 시장 공략", second.summary(), null));
    }

    @Test
    void differentImplementationYearsRegionsProcessesAndStagesVeto() {
        var first = product(1);
        for (String body : List.of(
                first.body().replace("2027년", "2028년"),
                first.body().replace("미국", "유럽"),
                first.body().replace("3nm", "5nm"),
                first.body().replace("수출할 계획이다", "수출을 시작했다"),
                first.body().replace("수출할 계획이다", "수출을 검토한다"),
                first.body().replace("수출할 계획이다", "수출 계약을 체결했다"),
                first.body().replace("수출", "출시"))) {
            var second = article(2, "한빛전자 제품 공급", null, body);
            assertNoMatch(first, second);
            assertTrue(evidence(first, second).conflicts(1, 2), body);
            assertTrue(evidence(first, second).conflicts(2, 1), body);
        }
    }

    @Test
    void explicitProductGenerationsCannotConnectViaTheSameBaseName() {
        var first = replace(product(1), "가온", "가온 2");
        var second = replace(product(2), "가온", "가온 3");
        assertNoMatch(first, second);
        assertTrue(evidence(first, second).conflicts(1, 2));
        var explicit = replace(product(1), "'가온'", "'가온' 2세대");
        var newer = replace(product(2), "'가온'", "'가온' 3세대");
        assertNoMatch(explicit, newer);
        assertTrue(evidence(explicit, newer).conflicts(1, 2));
    }

    @Test
    void partiallyOverlappingButDifferentDestinationScopesConflict() {
        var first = replace(product(1), "미국", "미국과 아시아");
        var second = replace(product(2), "미국", "미국과 유럽");
        assertNoMatch(first, second);
        assertTrue(evidence(first, second).conflicts(1, 2));
        // Naming only the shared destination leaves the other destination unknown.
        assertMatch(first, product(2));
    }

    @Test
    void missingImplementationYearIsUnknownAndPublicationYearIsNotEvidence() {
        var first = product(1);
        var missing = replace(product(2), "2027년 ", "");
        assertMatch(first, missing);
        assertFalse(evidence(first, missing).conflicts(1, 2));
        var onlyOneFact = article(2, "한빛전자 제품 공급", null,
                "한빛전자는 CPU '가온'을 미국 시장에 수출할 계획이다.");
        assertNoMatch(first, onlyOneFact);
        assertFalse(evidence(first, onlyOneFact).conflicts(1, 2));
    }

    @Test
    void sparseMetadataAndGenericProductsCannotCreateAnIdentity() {
        var first = product(1);
        for (ClusterArticle second : List.of(
                article(2, first.title(), null, null),
                article(2, first.title(), "한빛전자 제품 수출 계획", null),
                replace(product(2), "'가온'", "'신제품'"),
                replace(product(2), "한빛전자는", "업계는"))) {
            assertNoMatch(first, second);
            assertFalse(evidence(first, second).conflicts(1, 2));
            assertNull(evidence(first, second).subject(2));
        }
    }

    @Test
    void backgroundProductsAndGeneralMarketForecastsDoNotMatch() {
        var first = product(1);
        for (String body : List.of(
                "서버 반도체 시장 수요가 늘어날 전망이다. " + first.body(),
                "전자업체들의 공급망 비용을 비교했다.\n한편 " + first.body(),
                "부품 비용이 올랐다. ".repeat(80) + first.body(),
                first.body().replace("수출할 계획이다", "수출할 것으로 예상된다"))) {
            assertNoMatch(first, article(2, first.title(), null, body));
        }
        // A substantive primary body keeps its own focal story despite an optimistic summary.
        assertNoMatch(first, article(2, first.title(), first.body(),
                "업계의 채용 인원 변화를 분석했다. 신규 채용이 줄었다."));
    }

    @Test
    void evaluatingAnotherMakersProductCannotChangeTheHeadlineSubject() {
        var first = product(1);
        var evaluation = article(2, "구글, 한빛전자 신제품 평가", null, first.body());
        assertNoMatch(first, evaluation);
        assertNull(evidence(first, evaluation).subject(2));
        var anotherMaker = article(2, "구글, 한빛전자 칩 탑재한 신제품 출시", null, first.body());
        assertNoMatch(first, anotherMaker);
        assertNull(evidence(first, anotherMaker).subject(2));
        var background = article(3, "구글, 누리칩 신제품 출시", null,
                "한빛전자는 CPU '가온'을 2027년 미국 시장에 수출할 계획이다. 3nm 공정을 사용한다. "
                        + "구글은 이를 평가하고 있다.");
        var pair = new CommercialProductEventEvidence(List.of(evaluation, background), new BreakingNewsDetector());
        assertFalse(pair.matches(2, 3));
        assertNull(pair.subject(2));
        assertNull(pair.subject(3));
    }

    @Test
    void anotherCompaniesFollowingLaunchCannotSupplyTheProductAction() {
        var first = article(1, "가온전자, 별빛칩 생산 확대", null,
                "가온전자는 '별빛칩' 프로세서의 2나노 시험 생산을 확대했다. "
                        + "누리전자는 2027년 미국에 로봇을 출시할 계획이다.");
        var second = article(2, "가온전자, 별빛칩 출시 계획", null,
                "가온전자는 '별빛칩' 프로세서를 2나노로 생산해 2027년 미국에 출시할 계획이다.");
        assertNoMatch(first, second);
        assertNull(evidence(first, second).subject(1));
        var oneSentence = article(1, first.title(), null, first.body().replace("확대했다. 누리전자", "확대했으며 누리전자"));
        assertNoMatch(oneSentence, second);
        assertNull(evidence(oneSentence, second).subject(1));
    }

    @Test
    void anotherProductsProcessDoesNotSupplyAMissingIndependentFact() {
        var first = article(1, "한빛전자 가온 수출 계획", null,
                "한빛전자는 CPU '가온'을 미국 시장에 수출할 계획이다. "
                        + "누리전자는 3nm 공정을 적용한 로봇을 생산한다.");
        var second = product(2);
        assertNoMatch(first, second);
        assertNull(evidence(first, second).subject(1));
        var misleadingReference = article(1, first.title(), null,
                "한빛전자는 CPU '가온'을 미국 시장에 수출할 계획이다. "
                        + "이 제품과 달리 누리전자는 3nm 공정의 칩을 생산한다.");
        assertNoMatch(misleadingReference, second);
        assertNull(evidence(misleadingReference, second).subject(1));
        var anotherSubjectAfterAction = article(1, first.title(), null,
                "한빛전자는 CPU '가온'을 미국 시장에 수출할 계획이며 "
                        + "누리전자는 2027년 3nm 공정의 칩을 생산한다.");
        assertNoMatch(anotherSubjectAfterAction, second);
        assertNull(evidence(anotherSubjectAfterAction, second).subject(1));
    }

    @Test
    void publicationGapAndAbsentTimesDoNotSupplyEventIdentity() {
        var first = product(1);
        var second = product(2);
        assertNoMatch(first, withTime(second, first.eventTime().plusDays(3)));
        assertNoMatch(first, withTime(second, null));
        assertFalse(evidence(first, second).matches(1, 999));
        assertFalse(evidence(first, second).conflicts(1, 999));
        assertNull(evidence(first, second).subject(999));
    }

    private static ClusterArticle product(long id) {
        return article(id, "한빛전자 해외 제품 공급 계획", null,
                "한빛전자는 CPU '가온'을 2027년 미국 시장에 수출할 계획이다. 3nm 공정을 사용한다.");
    }

    private static ClusterArticle replace(ClusterArticle article, String before, String after) {
        return article(article.articleId(), article.title(), article.summary(), article.body().replace(before, after));
    }

    private static ClusterArticle withTime(ClusterArticle article, OffsetDateTime time) {
        return new ClusterArticle(article.articleId(), 1, article.title(), article.summary(), article.body(),
                article.fetchStatus(), article.sourceId(), article.publisher(), article.reliabilityScore(),
                time, time, List.of(), null, null, null, true);
    }

    private static CommercialProductEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new CommercialProductEventEvidence(List.of(first, second), new BreakingNewsDetector());
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

    private static ClusterArticle article(long id, String title, String summary, String body) {
        OffsetDateTime time = OffsetDateTime.parse("2026-10-12T12:00:00+09:00").plusMinutes(id);
        return new ClusterArticle(id, 1, title, summary, body,
                body == null ? FetchStatus.METADATA_ONLY : FetchStatus.FULLTEXT,
                id, "fixture-" + id, new BigDecimal("0.8"), time, time, List.of(), null, null, null, true);
    }
}
