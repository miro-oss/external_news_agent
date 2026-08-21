package com.example.be.domain.analysis.agent.client;

import java.math.BigDecimal;

public class AgentClientException extends RuntimeException {

    private final String code;
    private final Usage usage;
    private final TimeoutPhase timeoutPhase;

    public AgentClientException(String code, String message) {
        this(code, message, null, null, TimeoutPhase.NONE);
    }

    public AgentClientException(String code, String message, Throwable cause) {
        this(code, message, cause, null, TimeoutPhase.NONE);
    }

    public AgentClientException(String code, String message, Throwable cause, Usage usage) {
        this(code, message, cause, usage, TimeoutPhase.NONE);
    }

    public AgentClientException(String code,
                                String message,
                                Throwable cause,
                                Usage usage,
                                TimeoutPhase timeoutPhase) {
        super(message, cause);
        this.code = code;
        this.usage = usage;
        this.timeoutPhase = timeoutPhase;
    }

    public String getCode() {
        return code;
    }

    public Usage getUsage() {
        return usage;
    }

    public boolean isConnectTimeout() {
        return timeoutPhase == TimeoutPhase.CONNECT;
    }

    public boolean isReadTimeout() {
        return timeoutPhase == TimeoutPhase.READ;
    }

    public TimeoutPhase getTimeoutPhase() {
        return timeoutPhase;
    }

    public enum TimeoutPhase {
        NONE,
        CONNECT,
        READ
    }

    public record Usage(Long inputTokens,
                        Long outputTokens,
                        BigDecimal costUsd,
                        BigDecimal credits) {
    }
}
