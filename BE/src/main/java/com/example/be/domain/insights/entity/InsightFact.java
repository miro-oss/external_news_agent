package com.example.be.domain.insights.entity;

import java.util.List;

public record InsightFact(
        String claimType,
        String id,
        String text,
        Long findingId,
        List<Integer> evidenceSentenceIds,
        String groundedness,
        String groundingReason
) {

    public InsightFact {
        evidenceSentenceIds = evidenceSentenceIds == null
                ? List.of() : List.copyOf(evidenceSentenceIds);
    }
}
