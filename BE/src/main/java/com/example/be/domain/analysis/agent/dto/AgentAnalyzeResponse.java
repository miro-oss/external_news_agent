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
        CrossSource crossSource,
        List<Long> promoteCandidates,
        List<MemberStance> memberStances,
        Meta meta
) {

    public AgentAnalyzeResponse(List<String> sentences,
                                List<Section> sections,
                                String summaryKo,
                                Classification classification,
                                Entities entities,
                                List<PerspectiveTag> perspectiveTags,
                                Meta meta) {
        this(
                sentences,
                sections,
                summaryKo,
                classification,
                entities,
                perspectiveTags,
                CrossSource.empty(),
                List.of(),
                List.of(),
                meta);
    }

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

    public record CrossSource(
            List<String> consensus,
            List<SoleSourceObservation> soleSource,
            List<ConflictObservation> conflicts,
            List<String> missingStakeholders
    ) {

        public CrossSource {
            consensus = consensus == null ? List.of() : List.copyOf(consensus);
            soleSource = soleSource == null ? List.of() : List.copyOf(soleSource);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            missingStakeholders = missingStakeholders == null
                    ? List.of()
                    : List.copyOf(missingStakeholders);
        }

        public static CrossSource empty() {
            return new CrossSource(List.of(), List.of(), List.of(), List.of());
        }
    }

    public record SoleSourceObservation(Long articleId, String text) {
    }

    public record ConflictObservation(List<Long> articleIds, String text) {

        public ConflictObservation {
            articleIds = articleIds == null ? List.of() : List.copyOf(articleIds);
        }
    }

    public record MemberStance(
            Long articleId,
            String stance,
            BigDecimal confidence
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
