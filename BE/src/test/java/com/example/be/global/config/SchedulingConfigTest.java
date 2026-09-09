package com.example.be.global.config;

import com.example.be.domain.collection.service.command.CollectionRunAsyncService;
import com.example.be.domain.collection.service.command.CollectionRunQueueClaimer;
import com.example.be.domain.collection.service.command.CollectionRunQueueDispatcher;
import com.example.be.domain.collection.service.schedule.CollectionScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulingConfigTest {
    @Test
    void disablingAutomaticCollectionStillDispatchesManualQueue() {
        var claimer = mock(CollectionRunQueueClaimer.class);
        var worker = mock(CollectionRunAsyncService.class);
        var executed = new CountDownLatch(1);
        when(claimer.claimAvailable()).thenReturn(List.of(42L), List.of());
        doAnswer(invocation -> { executed.countDown(); return null; }).when(worker).execute(42L);

        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, CollectionScheduler.class, CollectionRunQueueDispatcher.class)
                .withBean(CollectionRunQueueClaimer.class, () -> claimer)
                .withBean(CollectionRunAsyncService.class, () -> worker)
                .withPropertyValues("news.scheduling.enabled=true", "news.collection.scheduler.enabled=false",
                        "news.collection.queue.poll-interval-ms=10")
                .run(context -> {
                    assertTrue(context.getBeansOfType(CollectionScheduler.class).isEmpty());
                    assertTrue(executed.await(2, TimeUnit.SECONDS), "자동 수집이 꺼져도 수동 대기열은 실행되어야 합니다.");
                });
    }

    @Test
    void testMasterSwitchDisablesEveryScheduledBackgroundTask() {
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class)
                .withPropertyValues("news.scheduling.enabled=false")
                .run(context -> {
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                });
    }
}
