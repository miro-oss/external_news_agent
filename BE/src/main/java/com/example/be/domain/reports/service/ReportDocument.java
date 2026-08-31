package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.ReportStatus;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record ReportDocument(
        String title,
        String markdownBody,
        String modelName,
        String promptVersion,
        String llmProvider,
        Long inputTokens,
        Long outputTokens,
        BigDecimal costUsd,
        BigDecimal credits,
        ReportStatus status,
        List<Long> reflectedFindingIds,
        List<Long> excludedFindingIds
) {

    public ReportDocument {
        reflectedFindingIds = normalizedIds(reflectedFindingIds);
        excludedFindingIds = normalizedIds(excludedFindingIds);
    }

    public ReportDocument(String title,
                          String markdownBody,
                          String modelName,
                          String promptVersion,
                          String llmProvider,
                          Long inputTokens,
                          Long outputTokens,
                          BigDecimal costUsd,
                          BigDecimal credits,
                          ReportStatus status) {
        this(title, markdownBody, modelName, promptVersion, llmProvider, inputTokens, outputTokens,
                costUsd, credits, status, List.of(), List.of());
    }

    public ReportDocument(String title, String markdownBody, String modelName) {
        this(title, markdownBody, modelName, null, null, null, null, null, null,
                ReportStatus.FALLBACK, List.of(), List.of());
    }

    private static List<Long> normalizedIds(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        values.stream().filter(Objects::nonNull).forEach(unique::add);
        return List.copyOf(unique);
    }
}
