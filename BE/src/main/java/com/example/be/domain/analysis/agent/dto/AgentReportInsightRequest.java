package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import java.time.LocalDate;
import java.util.List;

public record AgentReportInsightRequest(String idempotencyKey, AgentPlan plan,
        List<String> audiences, ReportPayload report, List<FindingPayload> findings) {
    public record ReportPayload(Long id, String title, String reportScope,
            LocalDate reportDate, LocalDate reportEndDate) { }
    public record FindingPayload(Long id, Long articleId, String articleTitle,
            String canonicalUrl, String publishedAt, String topicName,
            List<ClaimPayload> claims, List<SentencePayload> sentences) { }
    public record ClaimPayload(String id, String text, String claimType, String attributedTo,
            List<Integer> evidenceSentenceIds) { }
    public record SentencePayload(int index, String text) { }
}
