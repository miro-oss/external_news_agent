package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimHashTest {

    @Test
    void keepsFingerprintStableAcrossHexRoundTrip() {
        long fingerprint = SimHash.of("삼성전자가 HBM4 양산 일정을 발표했다. ".repeat(20));

        assertEquals(fingerprint, SimHash.fromHex(SimHash.toHex(fingerprint)));
    }

    @Test
    void smallSyndicationEditStaysInsideConservativeDistance() {
        String original = "삼성전자가 HBM4 양산 일정을 발표하고 공급 계획을 설명했다. ".repeat(30);
        String edited = original.replaceFirst("공급 계획", "출하 계획");

        assertTrue(SimHash.distance(SimHash.of(original), SimHash.of(edited)) <= 3);
    }

    @Test
    void supportsUnicodeLetterBodiesAndSkipsTokenlessBodies() {
        assertTrue(SimHash.tryOf("日本の半導体産業について詳しく説明する記事です").isPresent());
        assertTrue(SimHash.tryOf("… !!!").isEmpty());
    }

    @Test
    void excludesPublisherBoilerplateAndShortFragmentsFromArticleFingerprint() {
        assertTrue(SimHash.tryOf(ClusterTestFixtures.PUBLISHER_FOOTER).isPresent());
        assertTrue(SimHash.tryOfArticleBody(ClusterTestFixtures.PUBLISHER_FOOTER, 200).isEmpty());
        assertTrue(SimHash.tryOfArticleBody("짧지만 정상적인 기사 본문", 200).isEmpty());
    }

    @Test
    void fingerprintsArticleTextBeforePublisherFooter() {
        String article = "삼성전자가 HBM4 공급 계약과 생산 일정을 발표했다. ".repeat(12);
        String withFooter = article + "대표이사 : 홍길동 사업자등록번호 : 123-45-67890";

        assertEquals(
                SimHash.tryOfArticleBody(article, 200).orElseThrow(),
                SimHash.tryOfArticleBody(withFooter, 200).orElseThrow());
    }

    @Test
    void keepsLegalMarkerInsideArticleAndRemovesOnlyTheActualTrailingFooter() {
        String lead = "언론 저작권 분쟁의 법적 쟁점을 설명하는 기사 본문이다. ".repeat(8);
        String quotedRule = "법원은 무단전재 금지 조항의 적용 범위를 따져야 한다고 밝혔다. ";
        String conclusion = "당사자 의견과 향후 재판 일정을 함께 정리했다. ".repeat(12);
        String article = lead + quotedRule + conclusion;
        String withFooter = article + ClusterTestFixtures.PUBLISHER_FOOTER;

        assertEquals(
                SimHash.tryOfArticleBody(article, 200).orElseThrow(),
                SimHash.tryOfArticleBody(withFooter, 200).orElseThrow());
    }

    @Test
    void recognizesNormalizedCopyrightSymbolsInTrailingFooter() {
        String article = "삼성전자가 HBM4 공급 계약과 생산 일정을 발표했다. ".repeat(12);
        String withFooter = article + "저작권자 ⓒ 뉴시스 무단전재 및 재배포 금지";

        assertEquals(
                SimHash.tryOfArticleBody(article, 200).orElseThrow(),
                SimHash.tryOfArticleBody(withFooter, 200).orElseThrow());
    }
}
