package com.example.be.domain.analysis.service;

/** 실행 문맥을 포함해 실제 분석 어댑터와 fallback을 조율한다. */
public interface ArticleAnalysisOrchestrator {

    AnalysisResult analyze(AnalysisContext context);
}
