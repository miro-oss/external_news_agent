package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import java.time.LocalDate;
import java.util.List;

public record AgentReportInsightRequest(String idempotencyKey, AgentPlan plan,
        List<String> audiences, ReportPayload report, List<FindingPayload> findings,
        List<AgentFeedbackExample> feedbackExamples) {
    public AgentReportInsightRequest {
        feedbackExamples = feedbackExamples == null ? List.of() : List.copyOf(feedbackExamples);
    }
    public AgentReportInsightRequest(String idempotencyKey, AgentPlan plan, List<String> audiences,
                                     ReportPayload report, List<FindingPayload> findings) {
        this(idempotencyKey, plan, audiences, report, findings, List.of());
    }
    public record ReportPayload(Long id, String title, String reportScope,
            LocalDate reportDate, LocalDate reportEndDate) { }
    public record FindingPayload(Long id, Long articleId, String articleTitle,
            String canonicalUrl, String publishedAt, String topicName,
            List<ClaimPayload> claims, List<SentencePayload> sentences, List<Long> topicIds) {
        public FindingPayload(Long id, Long articleId, String articleTitle, String canonicalUrl,
                               String publishedAt, String topicName, List<ClaimPayload> claims,
                               List<SentencePayload> sentences) {
            this(id, articleId, articleTitle, canonicalUrl, publishedAt, topicName, claims, sentences, List.of());
        }
    }
    public record ClaimPayload(String id, String text, String claimType, String attributedTo,
            List<Integer> evidenceSentenceIds) { }
    public record SentencePayload(int index, String text) { }
}
