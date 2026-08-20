package com.example.be.domain.analysis.entity;

/** finding을 만든 분석 경로. REUSED는 이후 동일 입력 재사용 경로를 위한 계약 값이다. */
public enum AnalysisSource {
    STUB,
    LLM,
    REUSED
}
