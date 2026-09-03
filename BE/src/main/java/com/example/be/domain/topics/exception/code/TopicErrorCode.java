package com.example.be.domain.topics.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum TopicErrorCode implements BaseErrorCode {

    INVALID_SCHEDULE(HttpStatus.BAD_REQUEST, "TOPIC400",
            "batchSize는 1 이상 300 이하, intervalMinutes는 60, 720, 1440 중 하나여야 합니다."),
    QUERY_TEXT_REQUIRED(HttpStatus.BAD_REQUEST, "TOPIC400",
            "SEARCH 소스를 연결하려면 검색어가 필요합니다."),
    TOPIC_NOT_FOUND(HttpStatus.NOT_FOUND, "TOPIC404",
            "수집 주제를 찾을 수 없습니다."),
    SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "SOURCE404",
            "수집 소스를 찾을 수 없습니다."),
    DUPLICATED_TOPIC_NAME(HttpStatus.CONFLICT, "TOPIC409",
            "이미 존재하는 주제명입니다."),
    TOPIC_COLLECTING(HttpStatus.CONFLICT, "TOPIC409",
            "수집이 진행 중인 주제는 삭제할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
