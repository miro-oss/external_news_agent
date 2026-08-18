package com.example.be.domain.analysis.entity;

public enum RiskLevel implements ApiValue {

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
        return ApiValue.parse(values(), value, "위험도");
    }
}
