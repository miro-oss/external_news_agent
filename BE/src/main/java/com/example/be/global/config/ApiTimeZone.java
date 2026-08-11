package com.example.be.global.config;

import java.time.ZoneId;

/**
 * API 응답의 날짜는 ISO-8601 오프셋 표기를 쓴다(공통 규칙). 배포 호스트의 기본 시간대에 따라
 * 같은 DB 값이 다른 오프셋으로 나가지 않도록 응답 변환은 이 시간대 하나만 사용한다.
 */
public class ApiTimeZone {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ApiTimeZone() {
    }
}
