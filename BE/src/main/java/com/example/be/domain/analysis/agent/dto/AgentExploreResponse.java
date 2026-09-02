package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentExploreResponse(
        Proposal proposal,
        Meta meta
) {

    public record Proposal(
            String action,
            String sourceKey,
            String query,
            Long articleId,
            List<String> entities,
            Integer days,
            String reason
    ) {

        public Proposal {
            entities = entities == null ? List.of() : List.copyOf(entities);
        }
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
