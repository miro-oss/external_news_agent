package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentErrorResponseTest {

    @Test
    void readsObservedMetadataSeparatelyFromUsage() {
        AgentErrorResponse response = error(Map.of(
                "executionMetadata", metadata(),
                "usage", Map.of("inputTokens", 10, "outputTokens", 3,
                        "costUsd", "0.001", "credits", 0)));

        assertEquals(new AgentClientException.ExecutionMetadata(
                "openai", "observed-model", "insight.ko.v2", "AGENT_ERROR"),
                response.executionMetadata());
        assertEquals(10L, response.usage().inputTokens());
    }

    @Test
    void acceptsKnownPromptWithUnobservedProviderAndModel() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", null);
        metadata.put("model", null);
        metadata.put("promptVersion", "insight.ko.v2");
        metadata.put("source", "AGENT_ERROR");

        assertEquals(new AgentClientException.ExecutionMetadata(
                null, null, "insight.ko.v2", "AGENT_ERROR"),
                error(Map.of("executionMetadata", metadata)).executionMetadata());
    }

    @Test
    void rejectsInvalidMetadataWithoutDiscardingUsage() {
        List<Map<String, Object>> invalid = List.of(
                changed("provider", 123),
                changed("provider", "x".repeat(31)),
                changed("model", "x".repeat(101)),
                changed("promptVersion", "x".repeat(51)),
                changed("provider", " openai"),
                changed("model", "model\nvalue"),
                changed("promptVersion", ""),
                changed("source", "CONFIGURED"),
                changed("usageCompleteness", "complete"),
                changed("usageCompleteness", 123),
                changed("unapprovedField", "do not store"));

        for (Map<String, Object> metadata : invalid) {
            AgentErrorResponse response = error(Map.of(
                    "executionMetadata", metadata,
                    "usage", Map.of("inputTokens", 10)));
            assertNull(response.executionMetadata());
            assertEquals(10L, response.usage().inputTokens());
        }
    }

    @Test
    void readsUsageCompletenessAndDefaultsOlderMetadataToUnknown() {
        for (String completeness : List.of("COMPLETE", "PARTIAL", "UNKNOWN")) {
            AgentErrorResponse response = error(Map.of(
                    "executionMetadata", changed("usageCompleteness", completeness)));
            assertEquals(completeness, response.executionMetadata().usageCompleteness());
        }
        assertEquals("UNKNOWN", error(Map.of("executionMetadata", metadata()))
                .executionMetadata().usageCompleteness());
        assertEquals("UNKNOWN", new AgentClientException.ExecutionMetadata(
                "openai", "observed-model", "insight.ko.v2", "AGENT_ERROR")
                .usageCompleteness());
    }

    @Test
    void missingOrMalformedMetadataKeepsLegacyErrorCompatible() {
        assertNull(new AgentErrorResponse(null).executionMetadata());
        assertNull(error(null).executionMetadata());
        assertNull(error(List.of("validation details")).executionMetadata());
        assertNull(error(Map.of("executionMetadata", "invalid")).executionMetadata());
        assertNull(error(Map.of("executionMetadata", Map.of("provider", "openai")))
                .executionMetadata());
        assertNull(new AgentClientException("ERROR", "legacy error").getExecutionMetadata());
    }

    @Test
    void malformedUsageDoesNotDiscardValidMetadata() {
        AgentErrorResponse response = error(Map.of(
                "executionMetadata", metadata(),
                "usage", Map.of("inputTokens", "invalid")));

        assertNull(response.usage());
        assertEquals("observed-model", response.executionMetadata().model());
    }

    private Map<String, Object> metadata() {
        return Map.of("provider", "openai", "model", "observed-model",
                "promptVersion", "insight.ko.v2", "source", "AGENT_ERROR");
    }

    private Map<String, Object> changed(String key, Object value) {
        Map<String, Object> changed = new HashMap<>(metadata());
        changed.put(key, value);
        return changed;
    }

    private AgentErrorResponse error(Object details) {
        return new AgentErrorResponse(new AgentErrorResponse.ErrorDetail(
                "SCHEMA_VIOLATION", "invalid output", details));
    }
}
