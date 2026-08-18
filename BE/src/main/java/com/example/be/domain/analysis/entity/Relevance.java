package com.example.be.domain.analysis.entity;

import java.util.Arrays;

public enum Relevance {

    IMPORTANT("important"),
    WATCH("watch"),
    REFERENCE("reference");

    private final String apiValue;

    Relevance(String apiValue) {
        this.apiValue = apiValue;
    }

    public String toApiValue() {
        return apiValue;
    }

    public static Relevance fromApiValue(String value) {
        return Arrays.stream(values())
                .filter(relevance -> relevance.apiValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 관련도입니다."));
    }
}
