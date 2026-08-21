package com.example.be.domain.settings.exception;

import com.example.be.domain.settings.exception.code.LlmErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

import java.util.Map;

public class LlmException extends GeneralException {

    public LlmException(LlmErrorCode code, String message) {
        super(code, message);
    }

    public LlmException(LlmErrorCode code, Map<String, Object> result) {
        super(code, result);
    }
}
