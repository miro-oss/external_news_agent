package com.example.be.domain.feedback.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class FeedbackSchedulingConfig {
    @Bean public ThreadPoolTaskScheduler feedbackScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("feedback-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
    }
}
