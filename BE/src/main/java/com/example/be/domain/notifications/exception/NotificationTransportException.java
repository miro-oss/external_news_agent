package com.example.be.domain.notifications.exception;

public class NotificationTransportException extends RuntimeException {
    private boolean definitelyRejected;
    public NotificationTransportException(String message, boolean definitelyRejected) {
        super(message); this.definitelyRejected = definitelyRejected;
    }
    public boolean isDefinitelyRejected() { return definitelyRejected; }
    public NotificationTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificationTransportException(String message) {
        super(message);
    }
}
