package com.example.be.domain.collection.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String publisherFooter = ("대표이사 : 염영남 주소 : 서울 중구 퇴계로 173 "
                + "사업자등록번호 : 102-81-36588 통신판매업신고 : 서울중구 00665 ").repeat(5);

        assertTrue(SimHash.tryOf(publisherFooter).isPresent());
        assertTrue(SimHash.tryOfArticleBody(publisherFooter).isEmpty());
        assertTrue(SimHash.tryOfArticleBody("짧지만 정상적인 기사 본문").isEmpty());
    }

    @Test
    void fingerprintsArticleTextBeforePublisherFooter() {
        String article = "삼성전자가 HBM4 공급 계약과 생산 일정을 발표했다. ".repeat(12);
        String withFooter = article + "대표이사 : 홍길동 사업자등록번호 : 123-45-67890";

        assertTrue(SimHash.tryOfArticleBody(withFooter).isPresent());
        assertEquals(
                SimHash.tryOfArticleBody(article).orElseThrow(),
                SimHash.tryOfArticleBody(withFooter).orElseThrow());
        assertFalse(SimHash.tryOfArticleBody(article).isEmpty());
    }
}
