package com.example.be.domain.analysis.entity;

import java.util.List;

public record FindingKeyPoint(String text, List<Integer> evidence, String groundedness) {

    public FindingKeyPoint {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
