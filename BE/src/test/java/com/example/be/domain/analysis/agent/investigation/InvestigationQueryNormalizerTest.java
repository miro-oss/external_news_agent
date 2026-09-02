package com.example.be.domain.analysis.agent.investigation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvestigationQueryNormalizerTest {

    @Test
    void normalizesCaseSpacingOrderAndKoreanParticlesBeforeHashing() {
        String first = InvestigationQueryNormalizer.hash("삼성전자는 HBM 투자");
        String second = InvestigationQueryNormalizer.hash("  투자, hbm   삼성전자 ");

        assertEquals(first, second);
    }
}
