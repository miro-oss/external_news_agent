package com.example.be.domain.analysis.agent.dto;

import java.util.List;

public record AgentReportInsightResponse(List<Insight> insights, AgentReportResponse.Meta meta) {
    public record Insight(String audience, String headline, List<Overview> overview,
            List<Assessment> assessments, List<Implication> implications, List<WatchItem> watchItems) { }
    public record Overview(String text, List<String> basisClaimIds, String assumption) { }
    public record Axes(Integer directness, Integer impact, Integer urgency, Integer novelty) { }
    public record Assessment(Long findingId, String reason, List<String> basisClaimIds, Axes axes) { }
    public record Implication(String text, String mechanism, List<String> basisClaimIds,
            String assumption, String falsifiedBy) { }
    public record WatchItem(String topic, String indicator, String trigger, List<String> basisClaimIds) { }
}
