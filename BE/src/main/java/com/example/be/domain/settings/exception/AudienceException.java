package com.example.be.domain.settings.exception;

import com.example.be.domain.settings.exception.code.AudienceErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

public class AudienceException extends GeneralException {

    public AudienceException() {
        super(AudienceErrorCode.INVALID_AUDIENCE);
    }
}
