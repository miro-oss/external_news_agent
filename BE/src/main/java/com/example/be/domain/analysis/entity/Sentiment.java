package com.example.be.domain.analysis.entity;

public enum Sentiment {

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
}
