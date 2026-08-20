package com.example.be.domain.analysis.agent.client;

import java.math.BigDecimal;

public class AgentClientException extends RuntimeException {

    private final String code;
    private final Usage usage;
    private final boolean timeout;

    public AgentClientException(String code, String message) {
        this(code, message, null, null, false);
    }

    public AgentClientException(String code, String message, Throwable cause) {
        this(code, message, cause, null, false);
    }

    public AgentClientException(String code, String message, Throwable cause, Usage usage) {
        this(code, message, cause, usage, false);
    }

    public AgentClientException(String code,
                                String message,
                                Throwable cause,
                                Usage usage,
                                boolean timeout) {
        super(message, cause);
        this.code = code;
        this.usage = usage;
        this.timeout = timeout;
    }

    public String getCode() {
        return code;
    }

    public Usage getUsage() {
        return usage;
    }

    public boolean isTimeout() {
        return timeout;
    }

    public record Usage(Long inputTokens,
                        Long outputTokens,
                        BigDecimal costUsd,
                        BigDecimal credits) {
    }
}
