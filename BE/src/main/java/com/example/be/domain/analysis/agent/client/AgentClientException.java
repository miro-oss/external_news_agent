package com.example.be.domain.analysis.agent.client;

public class AgentClientException extends RuntimeException {

    private final String code;

    public AgentClientException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AgentClientException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
