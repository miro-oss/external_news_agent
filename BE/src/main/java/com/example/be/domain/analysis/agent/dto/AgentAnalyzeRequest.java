package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentAnalyzeRequest(
        String idempotencyKey,
        AgentPlan plan,
        ArticlePayload article,
        List<IssueMemberPayload> issueMembers,
        TopicPayload topic,
        Object previousFinding
) {

    public AgentAnalyzeRequest(String idempotencyKey,
                               AgentPlan plan,
                               ArticlePayload article,
                               TopicPayload topic,
                               Object previousFinding) {
        this(idempotencyKey, plan, article, List.of(), topic, previousFinding);
    }

    public record ArticlePayload(
            Long id,
            String title,
            String summary,
            String canonicalUrl,
            String language,
            OffsetDateTime publishedAt,
            String bodyText
    ) {

        public ArticlePayload(Long id,
                              String title,
                              String canonicalUrl,
                              String language,
                              OffsetDateTime publishedAt,
                              String bodyText) {
            this(id, title, null, canonicalUrl, language, publishedAt, bodyText);
        }
    }

    public record IssueMemberPayload(
            Long id,
            String title,
            String summary,
            String publisher
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
