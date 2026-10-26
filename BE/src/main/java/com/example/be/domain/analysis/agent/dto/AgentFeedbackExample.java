package com.example.be.domain.analysis.agent.dto;

import java.util.List;

/** Historical, evidence-checked error context; never evidence for the current article. */
public record AgentFeedbackExample(long feedbackId, long topicId, String category,
                                   String eventTitle, String eventSummary, String diagnosis,
                                   List<Evidence> evidence) {
    public AgentFeedbackExample {
        evidence = List.copyOf(evidence);
    }

    public record Evidence(long articleId, String quote) { }
}
