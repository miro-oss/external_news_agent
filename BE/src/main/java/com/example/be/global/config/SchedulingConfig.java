package com.example.be.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 테스트에서는 실행 이력과 외부 수집을 건드리지 않도록 스케줄러를 명시적으로 끈다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "news.collection.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
