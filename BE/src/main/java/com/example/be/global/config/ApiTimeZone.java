package com.example.be.global.config;

import java.time.ZoneId;

/**
 * API 응답의 날짜는 ISO-8601 오프셋 표기를 쓴다(공통 규칙). 배포 호스트의 기본 시간대에 따라
 * 같은 DB 값이 다른 오프셋으로 나가지 않도록 응답 변환은 이 시간대 하나만 사용한다.
 *
 * <p><b>저장할 때도 같은 시간대로 읽어야 한다.</b> {@code LocalDateTime.now()}는 JVM 기본 시간대를 쓰는데,
 * 서버가 UTC면 16:00 KST에 일어난 일이 07:00으로 저장되고 응답에서 다시 {@code 07:00+09:00}으로 나간다.
 * 시각을 만들 때는 {@code LocalDateTime.now(ApiTimeZone.ZONE)}을 쓴다.
 */
public class ApiTimeZone {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ApiTimeZone() {
    }
}
