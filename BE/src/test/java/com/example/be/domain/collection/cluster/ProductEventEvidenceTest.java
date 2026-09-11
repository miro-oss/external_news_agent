package com.example.be.domain.collection.cluster;

import com.example.be.domain.collection.entity.FetchStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductEventEvidenceTest {
    @Test
    void datedNamedProductConnectsPriceAndCompetitionAngles() {
        var first = release(1);
        var second = article(2, "접는폰 경쟁, 대화면 신제품 등장", null,
                "한빛전자는 11일 신형 폴더블폰 '누리X 4'를 공개했다. 새 힌지를 적용했다.");
        assertMatch(first, second);
    }

    @Test
    void longSummarySuppliesAnnouncementWhenBodyContainsOnlyPhotos() {
        var first = release(1);
        var second = article(2, "폰 시장 경쟁 구도 재편", "폴더블폰 경쟁이 치열하다. ".repeat(25)
                + "한빛전자는 11일 신제품 '누리 X 4'를 공개했다.",
                "신제품 사진. (사진=업체 제공)\nCopyright 무단 전재 및 재배포 금지");
        assertMatch(first, second);
    }

    @Test
    void reportingDayAndPreviousDayResolveToTheAnnouncementDay() {
        var first = release(1);
        var second = article(2, "접는폰 경쟁, 신제품 공개", null,
                "12일 업계에 따르면 한빛전자는 전날 폴더블폰 신제품 '누리X4'를 공개했다.");
        assertMatch(first, second);
    }

    @Test
    void explicitlyNamedPreviousDayIsNotSubtractedTwice() {
        var previousDay = article(1, "폴더블폰 공개", null,
                "한빛전자는 전날인 11일 폴더블폰 신제품 '누리 X4'를 공개했다.");
        assertMatch(previousDay, release(2));
        var earlier = article(2, "폴더블폰 공개", null,
                release(2).body().replace("11일", "10일"));
        assertNoMatch(previousDay, earlier);
        assertTrue(evidence(previousDay, earlier).conflicts(1, 2));
    }

    @Test
    void metadataPreorderResultNeedsMakerProductAndInstalledProcessor() {
        var first = release(1);
        var second = preorder(2);
        assertMatch(first, second);
        assertMatch(first, article(2, second.title(), second.summary()
                .replace("누리 X4", "'누리X 4'").replace("솔라 730 프로", "솔라730프로"), null));
        assertNoMatch(first, article(2, second.title(),
                second.summary().replace("솔라 730 프로", "솔라 920 프로"), null));
        assertNoMatch(first, article(2, second.title(),
                "한빛전자의 누리 X4 시리즈는 화면과 방수 기능을 개선했다.", null));
        assertNoMatch(first, article(2, "한빛전자 폴더블폰 판매 전망",
                second.summary(), null));
        assertNoMatch(first, article(2, "다른전자의 사전 예약 완판",
                second.summary(), null));
    }

    @Test
    void twoUndatedPreorderSnippetsCannotBootstrapAnAnnouncement() {
        assertNoMatch(preorder(1), preorder(2));
    }

    @Test
    void explicitDifferentProductsMakersDaysAndVersionsVetoOtherEdges() {
        var first = release(1);
        for (String changed : List.of(
                first.body().replace("누리 X4", "바다 X4"),
                first.body().replace("누리 X4", "누리 X5"),
                first.body().replace("한빛전자", "새빛전자"),
                first.body().replace("11일", "12일"),
                first.body().replace("11일", "지난달 11일"),
                first.body().replace("11일", "지난해 10월 11일"),
                first.body().replace("11일", "9월 11일"),
                first.body().replace("11일", "2025년 10월 11일"))) {
            var second = article(2, "폴더블폰 신제품 공개", null, changed);
            assertNoMatch(first, second);
            assertTrue(evidence(first, second).conflicts(1, 2));
        }
        var versioned = article(1, first.title(), null, first.body().replace("'를", "' v1.2를"));
        var newer = article(2, first.title(), null, versioned.body().replace("v1.2", "v1.3"));
        assertNoMatch(versioned, newer);
        assertTrue(evidence(versioned, newer).conflicts(1, 2));
    }

    @Test
    void forecastAboutTheSameMarketConflictsWithAnnouncementAndPreorder() {
        var forecast = forecast(2);
        for (ClusterArticle first : List.of(release(1), preorder(1))) {
            assertNoMatch(first, forecast);
            assertTrue(evidence(first, forecast).conflicts(1, 2));
            assertTrue(evidence(first, forecast).conflicts(2, 1));
        }
    }

    @Test
    void marketForecastInLaterBackgroundDoesNotReplaceFocalAnnouncement() {
        var first = release(1);
        var second = article(2, "폴더블폰 경쟁 구도", forecast(2).body(),
                "한빛전자는 11일 폴더블폰 신제품 '누리X4'를 공개했다.\n\n"
                        + forecast(2).body());
        assertMatch(first, second);
    }

    @Test
    void completedLaunchInForecastBackgroundDoesNotChangeTheFocalMarketEvent() {
        var first = release(1);
        var second = article(2, "폴더블폰 수요 전망", null,
                forecast(2).body() + " " + first.body());
        assertNoMatch(first, second);
        assertTrue(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void productSummaryCannotOverridePrimaryBodyMarketForecast() {
        var first = release(1);
        var second = article(2, "스마트폰 출하량 전망", first.body(), forecast(2).body());
        assertNoMatch(first, second);
        assertTrue(evidence(first, second).conflicts(1, 2));
        var preorderSummary = article(2, preorder(2).title(), preorder(2).summary(), forecast(2).body());
        assertNoMatch(first, preorderSummary);
        assertTrue(evidence(first, preorderSummary).conflicts(1, 2));
    }

    @Test
    void productSummaryCannotReplaceASubstantiveBodyWithAnotherFocus() {
        var first = release(1);
        var second = article(2, "스마트폰 부품 비용 분석", first.body(),
                "스마트폰 부품 비용의 변화를 조사했다. 패널 단가와 물류비를 비교했다.");
        assertNoMatch(first, second);
    }

    @Test
    void differentMarketForecastIsNotAnExplicitConflict() {
        var first = release(1);
        var second = article(2, "전기차 시장 전망", null,
                "전기차 시장 출하량이 증가할 전망이다. 시장조사업체가 12일 수요 전망을 공개했다.");
        assertNoMatch(first, second);
        assertFalse(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void futureProductSpeculationDoesNotBecomeACompletedAnnouncement() {
        var first = release(1);
        for (String future : List.of("공개할 것으로 예상된다", "공개할 예정이다", "공개를 검토한다")) {
            var second = article(2, "신제품 발표 전망", null,
                    first.body().replace("공개했다", future));
            assertNoMatch(first, second);
        }
    }

    @Test
    void futureInstalledProcessorCannotSupportMetadataBridge() {
        var first = release(1);
        var second = preorder(2);
        assertNoMatch(first, article(2, second.title(),
                second.summary().replace("탑재됐다", "탑재될 것으로 예상된다"), null));
    }

    @Test
    void sharedBackgroundProductCannotOverrideDifferentPrimaryRelease() {
        var first = release(1);
        var second = article(2, "새 폴더블폰 출시", null,
                "한빛전자는 11일 폴더블폰 신제품 '바다 X6'를 공개했다. " + first.body());
        assertNoMatch(first, second);
        assertTrue(evidence(first, second).conflicts(1, 2));
    }

    @Test
    void distantAndExplicitBackgroundAreNotProductEventEvidence() {
        var first = release(1);
        assertNoMatch(first, article(2, "신제품 공급망 분석", null,
                "부품 비용을 검토한다. ".repeat(150) + first.body()));
        assertNoMatch(first, article(2, "신제품 공급망 분석", null,
                "부품 비용을 검토한다.\n\n한편 " + first.body()));
    }

    @Test
    void missingDatesNamesAndSparseMetadataRemainUnknown() {
        var first = release(1);
        for (ClusterArticle second : List.of(
                article(2, "폴더블폰 공개", null, first.body().replace("11일 ", "")),
                article(2, "폴더블폰 공개", null, first.body().replace("'누리 X4'", "새 모델")),
                article(2, "한빛전자 폴더블폰 공개", null, null))) {
            assertNoMatch(first, second);
            assertFalse(evidence(first, second).conflicts(1, 2));
        }
        assertFalse(evidence(first, preorder(2)).matches(1, 99));
        assertFalse(evidence(first, preorder(2)).conflicts(1, 99));
    }

    @Test
    void bareFutureDayAndOldPublicationCannotSupplyCurrentEventMatch() {
        var first = release(1);
        assertNoMatch(first, article(2, "폴더블폰 공개", null, first.body().replace("11일", "30일")));
        var second = article(2, first.title(), null, first.body());
        var late = new ClusterArticle(2, 1, second.title(), second.summary(), second.body(), second.fetchStatus(),
                second.sourceId(), second.publisher(), second.reliabilityScore(),
                second.publishedAt().plusDays(3), second.observedAt().plusDays(3),
                List.of(), null, null, null, true);
        assertNoMatch(first, late);
    }

    private static ClusterArticle release(long id) {
        return article(id, "비싼데도 인기, 접는폰 신작 공개", null,
                "한빛전자는 11일 폴더블폰 신제품 '누리 X4'를 공개했다. "
                        + "신제품에는 신형 '솔라 730 프로'가 탑재됐다.");
    }

    private static ClusterArticle preorder(long id) {
        return article(id, "한빛전자 폴더블폰 사전 예약 완판",
                "한빛전자의 누리 X4 시리즈는 새로운 접는 기술과 솔라 730 프로 칩이 탑재됐다.", null);
    }

    private static ClusterArticle forecast(long id) {
        return article(id, "삼성·애플·한빛전자, 폴더블 시장 전망", null,
                "폴더블폰 시장의 수요가 늘어날 전망이다. 애플과 한빛전자 점유율도 상승할 것으로 예상된다. "
                        + "시장조사업체 미래리서치가 11일 폴더블 스마트폰 시장 전망을 공개했다. "
                        + "한빛전자는 앞으로 폴더블 신제품 '누리 X4'를 공개할 것으로 예상된다.");
    }

    private static ProductEventEvidence evidence(ClusterArticle first, ClusterArticle second) {
        return new ProductEventEvidence(List.of(first, second), new BreakingNewsDetector());
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
