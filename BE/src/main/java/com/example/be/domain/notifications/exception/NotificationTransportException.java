package com.example.be.domain.notifications.exception;

public class NotificationTransportException extends RuntimeException {
    public NotificationTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificationTransportException(String message) {
        super(message);
    }
}
