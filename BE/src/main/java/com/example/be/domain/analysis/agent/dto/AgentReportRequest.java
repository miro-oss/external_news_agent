package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentReportRequest(
        String idempotencyKey,
        AgentPlan plan,
        RunPayload run,
        List<FindingPayload> findings,
        List<EventPayload> events,
        SourceStatsPayload sourceStats,
        List<String> sourceNotes,
        String perspective
) {

    public record RunPayload(
            Long id,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            List<String> topics
    ) {
    }

    public record FindingPayload(
            Long id,
            Long articleId,
            String articleTitle,
            String canonicalUrl,
            String sourceName,
            String changeType,
            String summaryKo,
            List<String> keyPoints,
            String intent,
            String sentiment,
            String riskLevel,
            String relevance,
            String category,
            String fetchStatus
    ) {
    }

    public record EventPayload(
            String id,
            String title,
            String summaryKo,
            List<Long> findingIds
    ) {
    }

    public record SourceStatsPayload(
            int collected,
            int blocked,
            int failed,
            int paywalled,
            int stubExcluded
    ) {
    }
}
