package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentKeywordStrategyResponse(
        String summary,
        List<Proposal> proposals,
        Meta meta
) {

    public AgentKeywordStrategyResponse {
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }

    public record Proposal(
            String bucket,
            String action,
            String keyword,
            String reason
    ) {
    }

    public record Meta(
            String provider,
            String model,
            String promptVersion,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd,
            BigDecimal credits,
            boolean mock,
            boolean truncated
    ) {
    }
}
