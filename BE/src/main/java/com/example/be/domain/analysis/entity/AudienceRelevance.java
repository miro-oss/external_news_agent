package com.example.be.domain.analysis.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum AudienceRelevance {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    private final int rank;

    AudienceRelevance(int rank) {
        this.rank = rank;
    }

    public boolean isAtLeast(AudienceRelevance minimum) {
        return rank >= minimum.rank;
    }

    @JsonValue
    public String toApiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AudienceRelevance fromApiValue(String value) {
        try {
            return AudienceRelevance.valueOf(
                    value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 audience relevance입니다.", exception);
        }
    }
}
