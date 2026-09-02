package com.example.be.domain.insights.entity;

import java.util.List;

public record InsightImplication(
        String claimType,
        String id,
        String text,
        List<String> basisFactIds,
        String assumption,
        String falsifiedBy
) {

    public InsightImplication {
        basisFactIds = basisFactIds == null ? List.of() : List.copyOf(basisFactIds);
    }
}
