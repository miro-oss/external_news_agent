package com.example.be.domain.collection.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum RunErrorCode implements BaseErrorCode {

    NO_TARGET_COMBINATION(HttpStatus.BAD_REQUEST, "RUN400",
            "실행할 수집 조합이 없습니다. 주제에 소스를 연결해 주세요."),
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "RUN404",
            "수집 실행 이력을 찾을 수 없습니다."),
    RUN_IN_PROGRESS(HttpStatus.CONFLICT, "RUN409",
            "이미 실행 중인 수집이 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
