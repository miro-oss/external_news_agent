package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.client.AgentClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AgentErrorResponse(ErrorDetail error) {

    private static final Set<String> EXECUTION_METADATA_FIELDS =
            Set.of("provider", "model", "promptVersion", "source", "usageCompleteness");
    private static final Set<String> USAGE_COMPLETENESS_VALUES =
            Set.of("COMPLETE", "PARTIAL", "UNKNOWN");
    private static final Set<String> VALIDATION_FAILURE_FIELDS =
            Set.of("stage", "attempt", "errorType", "errorCount", "errorKinds");
    private static final Set<String> VALIDATION_OPTIONAL_FIELDS = Set.of("issues", "issuesTruncated");
    private static final Set<String> VALIDATION_ISSUE_FIELDS = Set.of("audience", "field", "errorKind", "claimIds");

    public AgentClientException.ValidationFailure validationFailure() {
        if (error == null || !"SCHEMA_VIOLATION".equals(error.code())
                || !(error.details() instanceof Map<?, ?> details)
                || !(details.get("validationFailure") instanceof Map<?, ?> failure)
                || !failure.keySet().containsAll(VALIDATION_FAILURE_FIELDS)
                || !failure.keySet().stream().allMatch(key -> VALIDATION_FAILURE_FIELDS.contains(key)
                    || VALIDATION_OPTIONAL_FIELDS.contains(key))
                || !(failure.get("stage") instanceof String stage)
                || !(failure.get("errorType") instanceof String type)
                || !(failure.get("errorKinds") instanceof List<?> kinds)
                || kinds.size() > 5 || kinds.stream().anyMatch(value -> !(value instanceof String))) {
            return null;
        }
        try {
            return new AgentClientException.ValidationFailure(stage,
                    diagnosticInteger(failure.get("attempt")), type,
                    diagnosticInteger(failure.get("errorCount")),
                    kinds.stream().map(String.class::cast).toList(),
                    validationIssues(failure), issuesTruncated(failure));
        } catch (IllegalArgumentException | ArithmeticException ignored) {
            return null;
        }
    }

    private static List<AgentClientException.ValidationIssue> validationIssues(Map<?, ?> failure) {
        if (!failure.containsKey("issues") && !failure.containsKey("issuesTruncated")) {
            return List.of();
        }
        if (!(failure.get("issues") instanceof List<?> issues) || issues.size() > 8
                || !(failure.get("issuesTruncated") instanceof Boolean)) {
            throw new IllegalArgumentException("Invalid validation issues");
        }
        return issues.stream().map(value -> {
            if (!(value instanceof Map<?, ?> issue) || !VALIDATION_ISSUE_FIELDS.equals(issue.keySet())
                    || !(issue.get("audience") instanceof String audience)
                    || !(issue.get("field") instanceof String field)
                    || !(issue.get("errorKind") instanceof String errorKind)
                    || !(issue.get("claimIds") instanceof List<?> refs)
                    || refs.size() > 8 || refs.stream().anyMatch(ref -> !(ref instanceof String))) {
                throw new IllegalArgumentException("Invalid validation issue");
            }
            return new AgentClientException.ValidationIssue(audience, field, errorKind,
                    refs.stream().map(String.class::cast).toList());
        }).toList();
    }

    private static boolean issuesTruncated(Map<?, ?> failure) {
        return Boolean.TRUE.equals(failure.get("issuesTruncated"));
    }

    private static int diagnosticInteger(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Invalid validation failure count");
        }
        return new BigDecimal(number.toString()).intValueExact();
    }

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
