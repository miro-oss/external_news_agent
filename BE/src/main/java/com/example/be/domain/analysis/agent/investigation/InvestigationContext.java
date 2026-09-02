package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.dto.AgentExploreRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record InvestigationContext(
        Long issueId,
        Long topicId,
        String title,
        String summary,
        String status,
        BigDecimal importanceScore,
        BigDecimal sensitivityScore,
        List<String> entities,
        List<String> missingStakeholders,
        int evidenceSentenceCount,
        int availableSentenceCount,
        List<Long> articleIds,
        List<Long> metadataOnlyArticleIds,
        List<AgentExploreRequest.AllowedSource> allowedSources,
        Map<String, Long> sourceIdsByKey,
        boolean breakingSoloAfter24Hours,
        String triggerReason
) {

    public InvestigationContext {
        entities = List.copyOf(entities);
        missingStakeholders = List.copyOf(missingStakeholders);
        articleIds = List.copyOf(articleIds);
        metadataOnlyArticleIds = List.copyOf(metadataOnlyArticleIds);
        allowedSources = List.copyOf(allowedSources);
        sourceIdsByKey = Map.copyOf(sourceIdsByKey);
    }
}
