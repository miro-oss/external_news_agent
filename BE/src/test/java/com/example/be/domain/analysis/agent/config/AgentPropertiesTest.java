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
        assertEquals("analyze.ko.v1", properties.getAnalysisPromptVersion());
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
}
