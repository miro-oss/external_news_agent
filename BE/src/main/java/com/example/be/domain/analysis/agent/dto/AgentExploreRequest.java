package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.math.BigDecimal;
import java.util.List;

public record AgentExploreRequest(
        String idempotencyKey,
        AgentPlan plan,
        Target target,
        int step,
        Issue issue,
        List<AllowedSource> allowedSources,
        List<PreviousStep> previousSteps
) {

    public AgentExploreRequest {
        allowedSources = allowedSources == null ? List.of() : List.copyOf(allowedSources);
        previousSteps = previousSteps == null ? List.of() : List.copyOf(previousSteps);
    }

    public record Target(String type, Long id) {
    }

    public record Issue(
            String title,
            String summary,
            String status,
            BigDecimal importanceScore,
            BigDecimal sensitivityScore,
            List<String> entities,
            List<String> missingStakeholders,
            int evidenceSentenceCount,
            List<Long> metadataOnlyArticleIds
    ) {

        public Issue {
            entities = entities == null ? List.of() : List.copyOf(entities);
            missingStakeholders = missingStakeholders == null
                    ? List.of() : List.copyOf(missingStakeholders);
            metadataOnlyArticleIds = metadataOnlyArticleIds == null
                    ? List.of() : List.copyOf(metadataOnlyArticleIds);
        }
    }

    public record AllowedSource(String key, String name, String kind) {
    }

    public record PreviousStep(
            int step,
            String action,
            boolean accepted,
            String summary,
            int evidenceSentenceCount
    ) {
    }
}
