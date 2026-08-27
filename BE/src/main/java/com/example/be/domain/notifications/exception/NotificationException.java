package com.example.be.domain.notifications.exception;

import com.example.be.domain.notifications.exception.code.NotificationErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

import java.util.Map;

public class NotificationException extends GeneralException {
    public NotificationException(NotificationErrorCode code) {
        super(code);
    }

    public NotificationException(NotificationErrorCode code, String message) {
        super(code, message);
    }

    public NotificationException(NotificationErrorCode code, Map<String, Object> result) {
        super(code, result);
    }

    public NotificationException(NotificationErrorCode code, String message, Map<String, Object> result) {
        super(code, message, result);
    }
}
