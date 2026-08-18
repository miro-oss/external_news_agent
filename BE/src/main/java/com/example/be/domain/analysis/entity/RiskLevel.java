package com.example.be.domain.analysis.entity;

import java.util.Arrays;

public enum RiskLevel {

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String apiValue;

    RiskLevel(String apiValue) {
        this.apiValue = apiValue;
    }

    public String toApiValue() {
        return apiValue;
    }

    public static RiskLevel fromApiValue(String value) {
        return Arrays.stream(values())
                .filter(level -> level.apiValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 위험도입니다."));
    }
}
