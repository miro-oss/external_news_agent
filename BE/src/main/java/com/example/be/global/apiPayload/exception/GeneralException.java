package com.example.be.global.apiPayload.exception;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.Getter;

import java.util.Map;

@Getter
public class GeneralException extends RuntimeException {

    private final BaseErrorCode code;

    /**
     * 실패 응답의 result에 실을 추가 정보. 명세가 원인을 구체적으로 요구하는 경우에만 채운다.
     * 예를 들어 SOURCE409는 어떤 주제에 연결돼 있는지(linkedTopicIds)를 같이 내려준다.
     * null이면 기존처럼 빈 객체가 나간다.
     */
    private final Map<String, Object> result;

    public GeneralException(BaseErrorCode code) {
        this(code, code.getMessage(), null);
    }

    public GeneralException(BaseErrorCode code, String message) {
        this(code, message, null);
    }

    public GeneralException(BaseErrorCode code, Map<String, Object> result) {
        this(code, code.getMessage(), result);
    }

    public GeneralException(BaseErrorCode code, String message, Map<String, Object> result) {
        super(message);
        this.code = code;
        this.result = result;
    }
}
