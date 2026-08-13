package com.example.be.domain.collection.exception;

import com.example.be.domain.collection.exception.code.RunErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

import java.util.Map;

public class RunException extends GeneralException {

    public RunException(RunErrorCode code) {
        super(code);
    }

    public RunException(RunErrorCode code, Map<String, Object> result) {
        super(code, result);
    }
}
