package com.example.be.domain.analysis.entity;

import java.util.Locale;

public enum Audience {
    CHIP_MAKER,
    EQUIPMENT_MAKER,
    MARKET_INVESTOR,
    IT_INFRA;

    public static Audience fromApiValue(String value) {
        try {
            return Audience.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 audience입니다.", exception);
        }
    }
}
