package com.example.be.domain.reports.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ReportErrorCode implements BaseErrorCode {

    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "REPORT404", "보고서를 찾을 수 없습니다."),
    TOPIC_INPUT_PENDING(HttpStatus.CONFLICT, "REPORT409", "선택한 주제의 수집·분석이 진행 중입니다. 완료 후 다시 시도해 주세요."),
    TOPIC_INPUT_EMPTY(HttpStatus.CONFLICT, "REPORT409", "선택한 주제와 기간에 보고서를 만들 수 있는 분석 자료가 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
