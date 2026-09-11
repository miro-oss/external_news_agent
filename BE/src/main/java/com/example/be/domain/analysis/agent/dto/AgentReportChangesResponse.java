package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.reports.comparison.ComparisonAssessment;
import java.math.BigDecimal;
import java.util.List;

public record AgentReportChangesResponse(List<ComparisonAssessment> items, Meta meta) {
    public record Meta(String provider, String model, String promptVersion, Long inputTokens,
                       Long outputTokens, BigDecimal costUsd, BigDecimal credits,
                       Boolean mock, Boolean truncated) { }
}
