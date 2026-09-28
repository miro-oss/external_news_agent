package com.example.be.domain.analysis.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

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
        assertEquals(
                "analyze.ko.v11+perspective.ko.v1+sensitivity.ko.v2",
                properties.getFreeAnalysisPromptVersion());
        assertEquals("insight.ko.v2+perspective.ko.v1", properties.getInsightPromptVersion());
        assertEquals("gpt-5.6-terra", properties.getRelevanceFreeModel());
        assertEquals(Duration.ofSeconds(180), properties.getRelevanceTimeout());
        assertEquals(Duration.ofSeconds(90), properties.getAnalyzeTimeout());
        assertEquals(Duration.ofSeconds(60), properties.getInsightTimeout());
        assertEquals(Duration.ofSeconds(120), properties.getReportTimeout());
        assertEquals(30, properties.getInsightHistory().getDays());
        assertEquals(6, properties.getInsightHistory().getLimit());
        assertEquals(15, properties.getQuota().getPaidDailyInsightCap());
    }

    @Test
    void bindsDedicatedRelevanceModelIndependentlyFromGlobalModel() throws IOException {
        // Read only the checked-in YAML; do not process external Spring config imports.
        var source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst();
        MockEnvironment environment = new MockEnvironment().withProperty("OPENAI_MODEL", "global-free-model");
        environment.getPropertySources().addLast(source);

        AgentProperties defaults = Binder.get(environment).bind("news.agent", AgentProperties.class).get();
        assertEquals("global-free-model", defaults.getFreeModel());
        assertEquals("gpt-5.6-terra", defaults.getRelevanceFreeModel());

        environment.setProperty("TOPIC_RELEVANCE_OPENAI_MODEL", "dedicated-relevance-model");
        AgentProperties configured = Binder.get(environment).bind("news.agent", AgentProperties.class).get();
        assertEquals("global-free-model", configured.getFreeModel());
        assertEquals("dedicated-relevance-model", configured.getRelevanceFreeModel());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void rejectsMissingOrNonPositiveRelevanceTimeout(String value) {
        AgentProperties properties = new AgentProperties();
        properties.setRelevanceTimeout(value == null ? null : Duration.parse(value));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, properties::afterPropertiesSet);
        assertEquals("news.agent.relevance-timeout은 양수여야 합니다.", error.getMessage());
    }

    @Test
    void acceptsCustomPositiveRelevanceTimeout() {
        AgentProperties properties = new AgentProperties();
        properties.setRelevanceTimeout(Duration.ofSeconds(240));

        assertDoesNotThrow(properties::afterPropertiesSet);
        assertEquals(Duration.ofSeconds(240), properties.getRelevanceTimeout());
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
