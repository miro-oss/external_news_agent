package com.example.be.domain.analysis.agent.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentPropertiesTest {

    @Test
    void isDisabledByDefault() {
        AgentProperties properties = new AgentProperties();

        assertFalse(properties.isEnabled());
        assertEquals(
                "analyze.ko.v6+perspective.ko.v1+sensitivity.ko.v2",
                properties.getAnalysisPromptVersion());
        assertEquals("insight.ko.v2+perspective.ko.v1", properties.getInsightPromptVersion());
        assertEquals(30, properties.getInsightHistory().getDays());
        assertEquals(6, properties.getInsightHistory().getLimit());
        assertEquals(15, properties.getQuota().getPaidDailyInsightCap());
    }

    @Test
    void rejectsEnabledAgentWithoutSharedSecret() {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void acceptsEnabledAgentWithSharedSecret() {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setToken("agent-secret");

        assertDoesNotThrow(properties::afterPropertiesSet);
    }

    @Test
    void rejectsPerRequestMaximumAbovePaidBudget() {
        AgentProperties properties = new AgentProperties();
        properties.getQuota().setPaidMaxCreditsPerRequest(new BigDecimal("91"));

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsInsightCapAboveWorkBudget() {
        AgentProperties properties = new AgentProperties();
        properties.getQuota().setPaidDailyInsightCap(71);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsInsightHistoryLimitAboveAgentFindingContract() {
        AgentProperties properties = new AgentProperties();
        properties.getInsightHistory().setLimit(7);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsInsightHistoryLimitWithoutIntegerOverflow() {
        AgentProperties properties = new AgentProperties();
        properties.getInsightHistory().setLimit(Integer.MAX_VALUE);

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }
}
