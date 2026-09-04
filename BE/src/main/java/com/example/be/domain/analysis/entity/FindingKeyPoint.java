package com.example.be.domain.analysis.entity;

import java.util.List;
import java.util.Objects;

public record FindingKeyPoint(
        String text,
        List<Integer> evidence,
        String groundedness,
        String groundingReason,
        String claimType,
        String attributedTo
) {

    public FindingKeyPoint {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        claimType = claimType == null || claimType.isBlank() ? "FACT" : claimType;
    }

    public FindingKeyPoint(String text, List<Integer> evidence, String groundedness) {
        this(text, evidence, groundedness, null, "FACT", null);
    }

    /** 엔티티와 좁은 조회 projection이 동일한 구조화 분석 우선 규칙을 사용한다. */
    public static List<FindingKeyPoint> effectivePoints(
            List<FindingKeyPoint> keyPoints, List<FindingAnalysisSection> analysisSections) {
        if (analysisSections == null || analysisSections.isEmpty()) {
            return keyPoints == null ? List.of() : keyPoints;
        }
        return analysisSections.stream()
                .filter(Objects::nonNull)
                .flatMap(section -> section.bullets().stream())
                .map(bullet -> new FindingKeyPoint(
                        bullet.text(), bullet.evidence(), bullet.groundedness(),
                        bullet.groundingReason(), bullet.claimType(), bullet.attributedTo()))
                .toList();
    }
}
