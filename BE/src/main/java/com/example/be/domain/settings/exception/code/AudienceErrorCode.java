package com.example.be.domain.settings.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AudienceErrorCode implements BaseErrorCode {

    INVALID_AUDIENCE(HttpStatus.BAD_REQUEST, "AUDIENCE400", "지원하지 않는 관점입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
