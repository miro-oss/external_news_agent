package com.example.be.domain.analysis.entity;

public enum SensitivityLevel implements ApiValue {

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String apiValue;

    SensitivityLevel(String apiValue) {
        this.apiValue = apiValue;
    }

    @Override
    public String toApiValue() {
        return apiValue;
    }

    public static SensitivityLevel fromApiValue(String value) {
        return ApiValue.parse(values(), value, "민감도");
    }
}
