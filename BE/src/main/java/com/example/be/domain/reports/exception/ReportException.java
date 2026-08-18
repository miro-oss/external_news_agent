package com.example.be.domain.reports.exception;

import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

public class ReportException extends GeneralException {

    public ReportException(ReportErrorCode code) {
        super(code);
    }
}
