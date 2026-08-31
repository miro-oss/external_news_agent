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
}
