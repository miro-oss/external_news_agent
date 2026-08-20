package com.example.be.domain.analysis.agent.client;

import java.math.BigDecimal;

public class AgentClientException extends RuntimeException {

    private final String code;
    private final Usage usage;

    public AgentClientException(String code, String message) {
        this(code, message, null, null);
    }

    public AgentClientException(String code, String message, Throwable cause) {
        this(code, message, cause, null);
    }

    public AgentClientException(String code, String message, Throwable cause, Usage usage) {
        super(message, cause);
        this.code = code;
        this.usage = usage;
    }

    public String getCode() {
        return code;
    }

    public Usage getUsage() {
        return usage;
    }

    public record Usage(Long inputTokens,
                        Long outputTokens,
                        BigDecimal costUsd,
                        BigDecimal credits) {
    }
}
