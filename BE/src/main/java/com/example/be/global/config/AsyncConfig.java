package com.example.be.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "collectionTaskExecutor")
    public Executor collectionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 큐가 길면 거절이 한참 뒤에야 나고, 그때까지 실행은 RUNNING으로 떠 있다. 짧게 잡아 일찍 거절한다.
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("collection-run-");
        // 종료 중에 수집이 잘리면 실행이 RUNNING으로 남는다. 끝날 때까지 기다렸다 내린다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
