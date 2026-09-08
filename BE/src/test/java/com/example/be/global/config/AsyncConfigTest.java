package com.example.be.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {
    @Test
    void startsFourClaimsImmediatelyAndRejectsExcessWorkForDurableRequeue() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().collectionTaskExecutor();
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(2, TimeUnit.SECONDS), "RUNNING으로 선점한 네 요청이 메모리 큐에서 기다리면 안 됩니다.");
            assertThrows(TaskRejectedException.class, () -> executor.execute(() -> {}));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
