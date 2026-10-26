package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentTopicRelevanceResponse(List<Decision> decisions, Meta meta) {
    public record Decision(Long articleId, String status, String reason, List<String> evidenceQuotes) {}
    public record Meta(String provider, String model, String promptVersion, Long inputTokens,
                       Long outputTokens, BigDecimal costUsd, BigDecimal credits,
                       Boolean mock, Boolean truncated) {}
}
