package com.example.be.domain.sources.exception;

import com.example.be.domain.sources.exception.code.SourceErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

import java.util.Map;

public class SourceException extends GeneralException {

    public SourceException(SourceErrorCode code) {
        super(code);
    }

    public SourceException(SourceErrorCode code, String message) {
        super(code, message);
    }

    public SourceException(SourceErrorCode code, Map<String, Object> result) {
        super(code, result);
    }
}
