package com.example.be.domain.notifications.entity;

import java.util.Locale;

public enum ChannelType {
    TELEGRAM,
    EMAIL;

    public static ChannelType from(String value) {
        try {
            return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 알림 채널입니다.", exception);
        }
    }
}
