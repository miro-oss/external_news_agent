package com.example.be.domain.feedback;

import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

final class FeedbackErrors {
    private FeedbackErrors() { }
    static GeneralException bad() { return new GeneralException(GeneralErrorCode.BAD_REQUEST); }
    static GeneralException missing() { return new GeneralException(GeneralErrorCode.NOT_FOUND); }
    static GeneralException conflict() { return new GeneralException(GeneralErrorCode.CONFLICT); }
}
