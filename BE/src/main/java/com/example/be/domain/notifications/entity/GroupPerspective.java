package com.example.be.domain.notifications.entity;

import java.util.Locale;

public enum GroupPerspective {
    EXECUTIVE,
    PURCHASING,
    TECHNOLOGY,
    SALES;

    public static GroupPerspective from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 perspective 값입니다.", exception);
        }
    }
}
