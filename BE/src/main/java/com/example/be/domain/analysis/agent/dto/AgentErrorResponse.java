package com.example.be.domain.analysis.agent.dto;

public record AgentErrorResponse(ErrorDetail error) {

    public record ErrorDetail(String code, String message, Object details) {
    }
}
