package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.client.AgentClientException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public record AgentErrorResponse(ErrorDetail error) {

    private static final Set<String> EXECUTION_METADATA_FIELDS =
            Set.of("provider", "model", "promptVersion", "source", "usageCompleteness");
    private static final Set<String> USAGE_COMPLETENESS_VALUES =
            Set.of("COMPLETE", "PARTIAL", "UNKNOWN");

    public AgentClientException.ExecutionMetadata executionMetadata() {
        if (error == null || !(error.details() instanceof Map<?, ?> details)
                || !(details.get("executionMetadata") instanceof Map<?, ?> metadata)
                || !metadata.keySet().stream().allMatch(key -> key instanceof String name
                        && EXECUTION_METADATA_FIELDS.contains(name))
                || !"AGENT_ERROR".equals(metadata.get("source"))) {
            return null;
        }
        try {
            return new AgentClientException.ExecutionMetadata(
                    metadataValue(metadata.get("provider"), 30),
                    metadataValue(metadata.get("model"), 100),
                    metadataValue(metadata.get("promptVersion"), 50),
                    "AGENT_ERROR",
                    usageCompleteness(metadata));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String usageCompleteness(Map<?, ?> metadata) {
        if (!metadata.containsKey("usageCompleteness")) {
            return "UNKNOWN";
        }
        if (!(metadata.get("usageCompleteness") instanceof String value)
                || !USAGE_COMPLETENESS_VALUES.contains(value)) {
            throw new IllegalArgumentException("Invalid usage completeness");
        }
        return value;
    }

    private static String metadataValue(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()
                || text.length() > maxLength || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid execution metadata");
        }
        return text;
    }

    public AgentClientException.Usage usage() {
        if (error == null || !(error.details() instanceof Map<?, ?> details)
                || !(details.get("usage") instanceof Map<?, ?> usage)) {
            return null;
        }
        try {
            return new AgentClientException.Usage(
                    longValue(usage.get("inputTokens")),
                    longValue(usage.get("outputTokens")),
                    decimalValue(usage.get("costUsd")),
                    decimalValue(usage.get("credits")));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long longValue(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static BigDecimal decimalValue(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    public record ErrorDetail(String code, String message, Object details) {
    }
}
