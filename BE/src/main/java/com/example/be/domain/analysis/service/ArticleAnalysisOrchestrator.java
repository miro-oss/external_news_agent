package com.example.be.domain.analysis.service;

/**
 * 실행 문맥을 포함해 실제 분석 어댑터와 fallback을 조율한다.
 *
 * <p>반환값은 요청 기사의 1차 분석이다. 구현체는 이슈 교차 비교가 승격한 보조 기사의 finding과
 * stance를 별도로 저장할 수 있으며, 그 실패가 1차 분석 반환을 무효화해서는 안 된다.
 */
public interface ArticleAnalysisOrchestrator {

    AnalysisResult analyze(AnalysisContext context);
}
