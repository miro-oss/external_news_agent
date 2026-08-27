package com.example.be.domain.notifications.exception.code;

import com.example.be.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements BaseErrorCode {
    CHANNEL_INVALID(HttpStatus.BAD_REQUEST, "CHANNEL400", "알림 채널 설정이 올바르지 않습니다."),
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "CHANNEL404", "알림 채널을 찾을 수 없습니다."),
    RECIPIENT_INVALID(HttpStatus.BAD_REQUEST, "RECIPIENT400", "수신자 정보가 올바르지 않습니다."),
    RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "RECIPIENT404", "수신자를 찾을 수 없습니다."),
    RECIPIENT_DUPLICATE(HttpStatus.CONFLICT, "RECIPIENT409", "이미 등록된 수신 주소입니다."),
    GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "GROUP404", "수신 그룹을 찾을 수 없습니다."),
    GROUP_DUPLICATE(HttpStatus.CONFLICT, "GROUP409", "이미 존재하는 그룹명입니다."),
    DELIVERY_NO_TARGET(HttpStatus.BAD_REQUEST, "DELIVERY400", "발송할 수신 대상이 없습니다."),
    DELIVERY_FAILED(HttpStatus.BAD_GATEWAY, "DELIVERY502", "발송에 모두 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
