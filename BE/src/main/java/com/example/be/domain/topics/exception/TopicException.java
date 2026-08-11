package com.example.be.domain.topics.exception;

import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

public class TopicException extends GeneralException {

    public TopicException(TopicErrorCode code) {
        super(code);
    }

    public TopicException(TopicErrorCode code, String message) {
        super(code, message);
    }
}
