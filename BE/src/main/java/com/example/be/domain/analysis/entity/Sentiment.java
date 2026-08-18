package com.example.be.domain.analysis.entity;

public enum Sentiment implements ApiValue {

    POSITIVE("positive"),
    NEUTRAL("neutral"),
    NEGATIVE("negative");

    private final String apiValue;

    Sentiment(String apiValue) {
        this.apiValue = apiValue;
    }

    public String toApiValue() {
        return apiValue;
    }

    public static Sentiment fromApiValue(String value) {
        return ApiValue.parse(values(), value, "감정");
    }
}
