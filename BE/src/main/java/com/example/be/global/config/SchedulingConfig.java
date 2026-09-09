package com.example.be.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 정기 수집을 꺼도 수동 요청 대기열은 처리한다. 테스트는 전체 background 작업을 별도로 끈다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "news.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("news-scheduled-");
    }

    @Bean
    public ThreadPoolTaskScheduler telegramConnectionScheduler() {
        return scheduler("telegram-connection-");
    }

    @Bean
    public ThreadPoolTaskScheduler reportDeliveryScheduler() {
        return scheduler("report-delivery-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        // 보고서 생성·SMTP 전송의 긴 대기가 텔레그램 수신을 막지 않도록 실행 경로를 분리한다.
        // 각 경로는 한 스레드만 사용해 동일한 폴러의 실행이 겹치지 않게 한다.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
