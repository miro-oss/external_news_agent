package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentAnalyzeRequest(
        String idempotencyKey,
        AgentPlan plan,
        ArticlePayload article,
        TopicPayload topic,
        Object previousFinding
) {

    public record ArticlePayload(
            Long id,
            String title,
            String canonicalUrl,
            String language,
            OffsetDateTime publishedAt,
            String bodyText
    ) {
    }

    public record TopicPayload(
            String name,
            String queryText,
            List<String> requiredKeywords,
            List<String> optionalKeywords,
            List<String> excludedKeywords
    ) {
    }
}
