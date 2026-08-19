package com.example.be.news.agent;

public record AgentErrorResponse(ErrorDetail error) {

    public record ErrorDetail(String code, String message, Object details) {
    }
}
