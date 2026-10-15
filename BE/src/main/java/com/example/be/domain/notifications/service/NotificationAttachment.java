package com.example.be.domain.notifications.service;

import java.util.Objects;

/** An in-memory delivery artifact; never persisted in delivery logs or shared across recipients. */
public record NotificationAttachment(String filename, String contentType, byte[] content) {
    public NotificationAttachment {
        Objects.requireNonNull(filename);
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(content);
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || !"application/pdf".equals(contentType) || content.length == 0) {
            throw new IllegalArgumentException("Invalid report attachment");
        }
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
