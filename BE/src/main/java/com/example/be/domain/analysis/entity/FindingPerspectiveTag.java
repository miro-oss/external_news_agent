package com.example.be.domain.analysis.entity;

import java.util.List;

public record FindingPerspectiveTag(
        Audience audience,
        AudienceRelevance relevance,
        String hook,
        List<Integer> evidenceSentenceIds
) {
    public FindingPerspectiveTag {
        evidenceSentenceIds = evidenceSentenceIds == null
                ? List.of()
                : List.copyOf(evidenceSentenceIds);
    }
}
