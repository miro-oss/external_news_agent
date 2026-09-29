package com.example.be.domain.feedback.exception;

import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

public final class FeedbackErrors {
    private FeedbackErrors() { }
    public static GeneralException bad() { return new GeneralException(GeneralErrorCode.BAD_REQUEST); }
    public static GeneralException missing() { return new GeneralException(GeneralErrorCode.NOT_FOUND); }
    public static GeneralException conflict() { return new GeneralException(GeneralErrorCode.CONFLICT); }
}
