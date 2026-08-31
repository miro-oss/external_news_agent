package com.example.be.domain.issues.exception;

import com.example.be.domain.issues.exception.code.IssueErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

public class IssueException extends GeneralException {

    public IssueException(IssueErrorCode code) {
        super(code);
    }
}
