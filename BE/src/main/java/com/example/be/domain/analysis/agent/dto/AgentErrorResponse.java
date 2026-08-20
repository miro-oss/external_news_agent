package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.client.AgentClientException;

import java.math.BigDecimal;
import java.util.Map;

public record AgentErrorResponse(ErrorDetail error) {

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
