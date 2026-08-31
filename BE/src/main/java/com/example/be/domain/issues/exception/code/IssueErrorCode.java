package com.example.be.domain.issues.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum IssueErrorCode implements BaseErrorCode {

    ISSUE_NOT_FOUND(HttpStatus.NOT_FOUND, "ISSUE404", "이슈를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
