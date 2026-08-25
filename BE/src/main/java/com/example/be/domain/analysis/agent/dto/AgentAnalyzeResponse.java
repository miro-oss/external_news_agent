package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentAnalyzeResponse(
        List<String> sentences,
        List<Section> sections,
        String summaryKo,
        Classification classification,
        Entities entities,
        List<PerspectiveTag> perspectiveTags,
        Meta meta
) {

    public record Section(String heading, List<Bullet> bullets) {
    }

    public record Bullet(
            String text,
            List<Integer> evidenceSentenceIds,
            String groundedness,
            BigDecimal confidence
    ) {
    }

    public record Classification(
            String intent,
            String sentiment,
            String riskLevel,
            String relevance,
            String category
    ) {
    }

    public record Entities(
            List<String> companies,
            List<String> products,
            List<String> technologies
    ) {
    }

    public record PerspectiveTag(
            String audience,
            String relevance,
            String hook,
            List<Integer> evidenceSentenceIds
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
