package com.example.be.domain.analysis.entity;

/** finding을 만든 분석 경로. */
public enum AnalysisSource {
    STUB,
    LLM,
    REUSED;

    /** 실제 LLM 검증을 통과했거나 그 결과를 동일 입력에서 재사용한 finding인지 판정한다. */
    public boolean isLlmDerived() {
        return this == LLM || this == REUSED;
    }

    /** 호출자가 만든 finding처럼 source가 null일 수 있는 경계에서 쓰는 null-safe 판정이다. */
    public static boolean isLlmDerived(AnalysisSource source) {
        return source != null && source.isLlmDerived();
    }
}
