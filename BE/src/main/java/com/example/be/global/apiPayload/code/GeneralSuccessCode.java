package com.example.be.global.apiPayload.code;


import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GeneralSuccessCode implements BaseSuccessCode {

    OK(HttpStatus.OK, "COMMON200", "성공입니다."),
    CREATED(HttpStatus.CREATED, "COMMON201", "등록되었습니다."),
    UPDATED(HttpStatus.OK, "COMMON200", "수정되었습니다."),
    DELETED(HttpStatus.OK, "COMMON200", "삭제되었습니다."),
    LINKED(HttpStatus.OK, "COMMON200", "연결되었습니다."),
    COLLECTION_STARTED(HttpStatus.CREATED, "COMMON201", "수집을 시작했습니다."),
    COLLECTION_ALREADY_RUNNING(HttpStatus.OK, "COMMON200", "이미 진행 중인 수집입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
