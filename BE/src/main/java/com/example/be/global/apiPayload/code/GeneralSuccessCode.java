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
    DELETED(HttpStatus.OK, "COMMON200", "삭제되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
