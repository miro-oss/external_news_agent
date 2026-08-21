package com.example.be.domain.settings.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum LlmErrorCode implements BaseErrorCode {

    INVALID_PLAN(HttpStatus.BAD_REQUEST, "PLAN400", "LLM 플랜 설정이 올바르지 않습니다."),
    QUOTA_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS, "QUOTA429", "LLM 사용 한도가 소진되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
