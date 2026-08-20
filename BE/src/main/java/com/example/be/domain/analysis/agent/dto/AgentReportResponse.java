package com.example.be.domain.analysis.agent.dto;

import java.math.BigDecimal;
import java.util.List;

public record AgentReportResponse(
        String title,
        List<String> executiveSummary,
        List<ImportantEvent> importantEvents,
        List<WatchItem> watchItems,
        List<String> sourceNotes,
        String markdownBody,
        Meta meta
) {

    public record ImportantEvent(
            String title,
            String summaryKo,
            String significance,
            List<Long> sourceFindingIds
    ) {
    }

    public record WatchItem(
            String topic,
            String reason,
            List<Long> sourceFindingIds
    ) {
    }

    public record Meta(
            String provider,
            String model,
            String promptVersion,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costUsd,
            BigDecimal credits,
            boolean mock
    ) {
    }
}
