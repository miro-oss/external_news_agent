package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.util.List;

public record AgentInsightRequest(
        String idempotencyKey,
        AgentPlan plan,
        List<String> audiences,
        TargetPayload target,
        TopicPayload topic,
        List<FindingPayload> findings,
        List<AgentFeedbackExample> feedbackExamples
) {

    public AgentInsightRequest {
        feedbackExamples = feedbackExamples == null ? List.of() : List.copyOf(feedbackExamples);
    }
    public AgentInsightRequest(String idempotencyKey, AgentPlan plan, List<String> audiences,
                               TargetPayload target, TopicPayload topic, List<FindingPayload> findings) {
        this(idempotencyKey, plan, audiences, target, topic, findings, List.of());
    }

    public record TargetPayload(String type, Long id) {
    }

    public record TopicPayload(String name,
                               String queryText,
                               List<String> requiredKeywords,
                               List<String> optionalKeywords,
                               List<String> excludedKeywords, Long topicId) {
        public TopicPayload(String name, String queryText, List<String> requiredKeywords,
                            List<String> optionalKeywords, List<String> excludedKeywords) {
            this(name, queryText, requiredKeywords, optionalKeywords, excludedKeywords, null);
        }
    }

    public enum FindingRole {
        CURRENT,
        HISTORY
    }

    public record FindingPayload(Long id,
                                 String articleTitle,
                                 String canonicalUrl,
                                 String summaryKo,
                                 FindingRole role,
                                 String publishedAt,
                                 List<SentencePayload> sentences) {
    }

    public record SentencePayload(Integer id, String text) {
    }
}
