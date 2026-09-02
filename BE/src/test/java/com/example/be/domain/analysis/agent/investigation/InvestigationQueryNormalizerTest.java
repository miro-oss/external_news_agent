package com.example.be.domain.analysis.agent.investigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class InvestigationQueryNormalizerTest {

    @Test
    void normalizesCaseSpacingOrderAndKoreanParticlesBeforeHashing() {
        String first = InvestigationQueryNormalizer.hash("삼성전자는 HBM 투자");
        String second = InvestigationQueryNormalizer.hash("  투자, hbm   삼성전자 ");

        assertEquals(first, second);
        assertNotEquals(first, InvestigationQueryNormalizer.hash("현대차 전기차 판매"));
    }

    @Test
    void preservesShortKoreanNounsAndNormalizesEntitySpacing() {
        assertEquals("사과", InvestigationQueryNormalizer.normalize("사과"));
        assertEquals("결과", InvestigationQueryNormalizer.normalize("결과"));
        assertEquals("도로", InvestigationQueryNormalizer.normalize("도로"));
        assertEquals(
                InvestigationQueryNormalizer.normalizeEntity("SK하이닉스"),
                InvestigationQueryNormalizer.normalizeEntity("sk 하이닉스"));
    }
}
