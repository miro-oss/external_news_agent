package com.example.be.domain.analysis.service;

import java.math.BigDecimal;

/** 실제 분석 호출을 finding과 함께 추적하기 위한 최소 메타데이터. */
public record AnalysisMetadata(
        String promptVersion,
        String provider,
        String model,
        Long inputTokens,
        Long outputTokens,
        BigDecimal costUsd,
        BigDecimal credits,
        boolean truncated
) {

    public static AnalysisMetadata empty() {
        return new AnalysisMetadata(null, null, null, null, null, null, null, false);
    }
}
