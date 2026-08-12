package com.example.be.domain.topics.exception;

import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

import java.util.Map;

public class TopicException extends GeneralException {

    public TopicException(TopicErrorCode code) {
        super(code);
    }

    public TopicException(TopicErrorCode code, String message) {
        super(code, message);
    }

    public TopicException(TopicErrorCode code, Map<String, Object> result) {
        super(code, result);
    }
}
