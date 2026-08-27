package com.example.be.domain.notifications.service;

import java.util.List;

public record RenderedNotification(String subject, String parseMode, List<String> chunks) {
    public RenderedNotification {
        chunks = List.copyOf(chunks);
    }
}
