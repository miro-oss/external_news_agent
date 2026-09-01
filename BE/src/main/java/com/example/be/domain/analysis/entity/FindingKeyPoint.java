package com.example.be.domain.analysis.entity;

import java.util.List;

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
}
