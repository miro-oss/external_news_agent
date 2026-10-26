package com.example.be.domain.notifications.service;

import java.util.List;

public record RenderedNotification(String subject, String parseMode, List<String> chunks,
                                   List<NotificationAttachment> attachments) {
    public RenderedNotification(String subject, String parseMode, List<String> chunks) {
        this(subject, parseMode, chunks, List.of());
    }

    public RenderedNotification {
        chunks = List.copyOf(chunks);
        attachments = List.copyOf(attachments);
    }
}
