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
        // 대기는 DB의 PENDING 상태가 담당한다. 수락된 작업은 즉시 worker에서 시작하며,
        // 네 worker가 모두 바쁘면 dispatcher가 거절된 요청을 durable queue로 돌려놓는다.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("collection-run-");
        // 종료 중에 수집이 잘리면 실행이 RUNNING으로 남는다. 끝날 때까지 기다렸다 내린다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
