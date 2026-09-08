package com.example.be.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정기 수집을 꺼도 수동 요청 대기열은 처리한다. 테스트는 전체 background 작업을 별도로 끈다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "news.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
