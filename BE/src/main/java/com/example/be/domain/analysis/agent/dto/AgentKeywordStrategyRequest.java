package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record AgentKeywordStrategyRequest(
        String idempotencyKey,
        AgentPlan plan,
        Target target,
        Topic topic,
        Run run,
        List<KeywordStat> currentKeywordStats,
        List<ArticleObservation> articles
) {

    public AgentKeywordStrategyRequest {
        currentKeywordStats = currentKeywordStats == null ? List.of() : List.copyOf(currentKeywordStats);
        articles = articles == null ? List.of() : List.copyOf(articles);
    }

    public record Target(String type, Long id) {
    }

    public record Topic(
            String name,
            String queryText,
            List<String> requiredKeywords,
            List<String> optionalKeywords,
            List<String> excludedKeywords
    ) {

        public Topic {
            requiredKeywords = requiredKeywords == null ? List.of() : List.copyOf(requiredKeywords);
            optionalKeywords = optionalKeywords == null ? List.of() : List.copyOf(optionalKeywords);
            excludedKeywords = excludedKeywords == null ? List.of() : List.copyOf(excludedKeywords);
        }
    }

    public record Run(
            Long id,
            String triggerType,
            Integer scannedCount,
            Integer newCount,
            Integer updatedCount
    ) {
    }

    public record KeywordStat(
            String bucket,
            String keyword,
            int articleMatchCount
    ) {
    }

    public record ArticleObservation(
            Long articleId,
            String title,
            String summary,
            String publisher,
            String changeType,
            OffsetDateTime publishedAt,
            BigDecimal topicFit
    ) {
    }
}
