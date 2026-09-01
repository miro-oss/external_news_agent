package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record AgentAnalyzeRequest(
        String idempotencyKey,
        AgentPlan plan,
        ArticlePayload article,
        List<IssueMemberPayload> issueMembers,
        TopicPayload topic,
        PreviousFindingPayload previousFinding,
        boolean selfCritique
) {

    public AgentAnalyzeRequest(String idempotencyKey,
                               AgentPlan plan,
                               ArticlePayload article,
                               List<IssueMemberPayload> issueMembers,
                               TopicPayload topic,
                               PreviousFindingPayload previousFinding) {
        this(idempotencyKey, plan, article, issueMembers, topic, previousFinding, false);
    }

    public AgentAnalyzeRequest(String idempotencyKey,
                               AgentPlan plan,
                               ArticlePayload article,
                               TopicPayload topic,
                               PreviousFindingPayload previousFinding) {
        this(idempotencyKey, plan, article, List.of(), topic, previousFinding, false);
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

    public record PreviousFindingPayload(
            String summaryKo,
            String riskLevel,
            List<PreviousSectionPayload> sections,
            AgentAnalyzeResponse.CrossSource crossSource
    ) {
    }

    public record PreviousSectionPayload(
            String heading,
            List<PreviousBulletPayload> bullets
    ) {
    }

    public record PreviousBulletPayload(
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
