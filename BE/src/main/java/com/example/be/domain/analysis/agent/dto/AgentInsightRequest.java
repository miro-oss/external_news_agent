package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.util.List;

public record AgentInsightRequest(
        String idempotencyKey,
        AgentPlan plan,
        List<String> audiences,
        TargetPayload target,
        TopicPayload topic,
        List<FindingPayload> findings
) {

    public record TargetPayload(String type, Long id) {
    }

    public record TopicPayload(String name,
                               String queryText,
                               List<String> requiredKeywords,
                               List<String> optionalKeywords,
                               List<String> excludedKeywords) {
    }

    public record FindingPayload(Long id,
                                 String articleTitle,
                                 String canonicalUrl,
                                 String summaryKo,
                                 List<SentencePayload> sentences) {
    }

    public record SentencePayload(Integer id, String text) {
    }
}
