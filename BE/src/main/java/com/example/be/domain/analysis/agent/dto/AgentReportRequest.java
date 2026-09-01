package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record AgentReportRequest(
        String idempotencyKey,
        AgentPlan plan,
        RunPayload run,
        List<FindingPayload> findings,
        List<EventPayload> events,
        SourceStatsPayload sourceStats,
        List<String> sourceNotes
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
            List<KeyPointPayload> keyPoints,
            String intent,
            String sentiment,
            SensitivityPayload sensitivity,
            String relevance,
            String category,
            String fetchStatus
    ) {
    }

    public record SensitivityPayload(
            BigDecimal score,
            String level,
            SensitivityAxesPayload axes
    ) {
    }

    public record SensitivityAxesPayload(
            SensitivityAxisPayload customerMove,
            SensitivityAxisPayload dealSignal,
            SensitivityAxisPayload competitorThreat,
            SensitivityAxisPayload industryShift
    ) {
    }

    public record SensitivityAxisPayload(Integer score, List<Integer> evidenceSentenceIds) {
    }

    public record KeyPointPayload(
            String text,
            List<Integer> evidence,
            String groundedness,
            String groundingReason,
            String claimType,
            String attributedTo
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
