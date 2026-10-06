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

    @Test
    void readsBoundedValidationFailureWithoutDiscardingUsageOrExecutionMetadata() {
        var response = error(Map.of("validationFailure", validation(),
                "executionMetadata", metadata(), "usage", Map.of("inputTokens", 10)));

        assertEquals(new AgentClientException.ValidationFailure("MAP-002", 2,
                "OutputValidationError", 2, List.of("report_assessment_draft_invalid", "report_fact_mismatch")),
                response.validationFailure());
        assertEquals(10L, response.usage().inputTokens());
        assertEquals("observed-model", response.executionMetadata().model());
        assertNull(new AgentClientException("SCHEMA_VIOLATION", "legacy").getValidationFailure());
    }

    @Test
    void unrecognizedKindsAreOmittedWithoutInventingACause() {
        var failure = new HashMap<>(validation());
        failure.put("errorKinds", List.of("private-kind\nforged", "report_fact_mismatch", "report_fact_mismatch"));
        assertEquals(List.of("report_fact_mismatch"), error(Map.of("validationFailure", failure))
                .validationFailure().errorKinds());
        failure.put("errorKinds", List.of("private-kind"));
        assertEquals(List.of(), error(Map.of("validationFailure", failure)).validationFailure().errorKinds());
    }

    @Test
    void rejectsMalformedValidationContextWithoutDiscardingUsage() {
        var invalid = List.of(
                changedValidation("stage", "MAP-002\nforged"),
                changedValidation("stage", "PRIVATE_STAGE"),
                changedValidation("stage", "MAP-1000"),
                changedValidation("attempt", 3),
                changedValidation("attempt", "2"),
                changedValidation("attempt", 1.5),
                changedValidation("errorType", "PrivateDynamicException"),
                changedValidation("errorCount", -1),
                changedValidation("errorCount", 1_000_001),
                changedValidation("errorKinds", List.of("value_error", "value_error", "value_error",
                        "value_error", "value_error", "value_error")),
                changedValidation("errorKinds", List.of(123)),
                changedValidation("message", "private response"));
        for (var failure : invalid) {
            var response = error(Map.of("validationFailure", failure, "usage", Map.of("inputTokens", 10)));
            assertNull(response.validationFailure());
            assertEquals(10L, response.usage().inputTokens());
        }
    }

    @Test
    void providerErrorsCannotClaimOldValidationDiagnostics() {
        var response = new AgentErrorResponse(new AgentErrorResponse.ErrorDetail(
                "PROVIDER_UNAVAILABLE", "failed repair provider", Map.of("validationFailure", validation())));
        assertNull(response.validationFailure());
        assertNull(new AgentErrorResponse(null).validationFailure());
        assertNull(error(null).validationFailure());
        assertNull(error(Map.of("validationFailure", "invalid")).validationFailure());
    }

    private Map<String, Object> validation() {
        return Map.of("stage", "MAP-002", "attempt", 2, "errorType", "OutputValidationError",
                "errorCount", 2, "errorKinds", List.of("report_assessment_draft_invalid", "report_fact_mismatch"));
    }

    private Map<String, Object> changedValidation(String key, Object value) {
        var result = new HashMap<>(validation());
        result.put(key, value);
        return result;
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
