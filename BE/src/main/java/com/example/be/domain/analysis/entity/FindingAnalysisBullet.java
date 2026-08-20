package com.example.be.domain.analysis.entity;

import java.math.BigDecimal;
import java.util.List;

/** Agent 구조화 분석의 bullet과 원문 sentence evidence 연결. */
public record FindingAnalysisBullet(
        String text,
        List<Integer> evidence,
        String groundedness,
        BigDecimal confidence
) {

    public FindingAnalysisBullet {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
