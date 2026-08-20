package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.ReportStatus;

import java.math.BigDecimal;

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
        ReportStatus status
) {

    public ReportDocument(String title, String markdownBody, String modelName) {
        this(title, markdownBody, modelName, null, null, null, null, null, null,
                ReportStatus.FALLBACK);
    }
}
