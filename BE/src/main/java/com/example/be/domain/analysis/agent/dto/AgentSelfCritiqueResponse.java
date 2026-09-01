package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentSelfCritiqueResponse(
        List<Section> sections,
        String summaryKo,
        Integer targetClaimCount,
        Integer revisedClaimCount,
        List<String> unsupportedExpressions,
        AgentAnalyzeResponse.Meta meta
) {

    public record Section(String heading, List<Bullet> bullets) {
    }

    public record Bullet(
            String text,
            List<Integer> evidenceSentenceIds,
            String groundedness,
            BigDecimal confidence,
            String groundingReason,
            String claimType,
            String attributedTo
    ) {
    }
}
