package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentEvidenceResponse(
        List<Result> results,
        Meta meta
) {

    public record Result(
            String claimId,
            String status,
            List<Integer> acceptedSentenceIds,
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
