package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import java.util.List;

/** Internal judgment contract; collection settings are inputs, never mutations. */
public record AgentTopicRelevanceRequest(String idempotencyKey, AgentPlan plan,
                                         TopicInput topic, List<ArticleInput> articles,
                                         List<AgentFeedbackExample> feedbackExamples) {
    public AgentTopicRelevanceRequest {
        feedbackExamples = feedbackExamples == null ? List.of() : List.copyOf(feedbackExamples);
    }
    public AgentTopicRelevanceRequest(String idempotencyKey, AgentPlan plan,
                                     TopicInput topic, List<ArticleInput> articles) {
        this(idempotencyKey, plan, topic, articles, List.of());
    }
    public record TopicInput(Long id, String name, String queryText,
                             List<String> requiredKeywords, List<String> optionalKeywords,
                             List<String> excludedKeywords) {}
    public record ArticleInput(Long articleId, String title, String summary, String bodyText) {}
}
