package com.example.be.domain.analysis.entity;

public enum Relevance implements ApiValue {

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
        return ApiValue.parse(values(), value, "관련도");
    }
}
