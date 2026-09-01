package com.example.be.domain.analysis.entity;

import java.math.BigDecimal;
import java.util.List;

/** Agent 구조화 분석의 bullet과 원문 sentence evidence 연결. */
public record FindingAnalysisBullet(
        String text,
        List<Integer> evidence,
        String groundedness,
        BigDecimal confidence,
        String groundingReason,
        String claimType,
        String attributedTo
) {

    public FindingAnalysisBullet {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        claimType = claimType == null || claimType.isBlank() ? "FACT" : claimType;
    }

    public FindingAnalysisBullet(String text,
                                 List<Integer> evidence,
                                 String groundedness,
                                 BigDecimal confidence) {
        this(text, evidence, groundedness, confidence, null, "FACT", null);
    }
}
