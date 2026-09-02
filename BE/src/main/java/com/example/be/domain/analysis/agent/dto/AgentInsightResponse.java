package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentInsightResponse(
        List<Insight> insights,
        Meta meta
) {

    public record Insight(String audience,
                          String headline,
                          List<Fact> facts,
                          List<Implication> implications,
                          List<String> watchNext,
                          BigDecimal confidence) {
    }

    public record Fact(String claimType,
                       String id,
                       String text,
                       Long findingId,
                       List<Integer> evidenceSentenceIds,
                       String groundedness,
                       String groundingReason) {
    }

    public record Implication(String claimType,
                              String id,
                              String text,
                              List<String> basisFactIds,
                              String assumption,
                              String falsifiedBy) {
    }

    public record Meta(String provider,
                       String model,
                       String promptVersion,
                       Long inputTokens,
                       Long outputTokens,
                       BigDecimal costUsd,
                       BigDecimal credits,
                       boolean mock,
                       boolean truncated) {
    }
}
