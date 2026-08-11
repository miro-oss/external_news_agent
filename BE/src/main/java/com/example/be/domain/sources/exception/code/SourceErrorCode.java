package com.example.be.domain.sources.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum SourceErrorCode implements BaseErrorCode {

    INVALID_SEARCH_URL_TEMPLATE(HttpStatus.BAD_REQUEST, "SOURCE400",
            "SEARCH 소스의 URL 템플릿에는 질의 자리표시자가 필요합니다."),
    INVALID_FEED_URL_TEMPLATE(HttpStatus.BAD_REQUEST, "SOURCE400",
            "FEED 소스의 URL 템플릿은 http 또는 https URL이어야 합니다."),
    INVALID_SOURCE_KIND(HttpStatus.BAD_REQUEST, "SOURCE400",
            "sourceKind는 FEED 또는 SEARCH여야 합니다."),
    INVALID_RELIABILITY_SCORE(HttpStatus.BAD_REQUEST, "SOURCE400",
            "reliabilityScore는 0.00 이상 1.00 이하여야 합니다."),
    SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "SOURCE404",
            "수집 소스를 찾을 수 없습니다."),
    DUPLICATED_SOURCE(HttpStatus.CONFLICT, "SOURCE409",
            "이미 등록된 수집 소스입니다."),
    SOURCE_LINKED_TO_TOPIC(HttpStatus.CONFLICT, "SOURCE409",
            "주제에 연결된 소스는 삭제할 수 없습니다. 연결을 먼저 해제해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
