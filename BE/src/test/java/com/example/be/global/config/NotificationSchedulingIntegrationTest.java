package com.example.be.global.config;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.channel.TelegramConnectionAdapter;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.service.ReportDeliveryOutboxStore;
import com.example.be.domain.notifications.service.ReportDeliveryWorker;
import com.example.be.domain.notifications.service.TelegramConnectionPoller;
import com.example.be.domain.notifications.service.TelegramConnectionService;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.service.DailyReportCreationService;
import com.example.be.domain.reports.service.DailyReportScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class NotificationSchedulingIntegrationTest {

    @Test
    void telegramReceptionContinuesWhileDailyGenerationAndEmailDeliveryAreBlocked() {
        var dailyStarted = new CountDownLatch(1);
        var deliveryStarted = new CountDownLatch(1);
        var telegramAccepted = new CountDownLatch(1);
        var releaseDaily = new CountDownLatch(1);
        var releaseDelivery = new CountDownLatch(1);
        var dailyRepository = mock(DailyReportJdbcRepository.class);
        var creation = mock(DailyReportCreationService.class);
        when(dailyRepository.findDueDates(any(), any())).thenReturn(List.of(LocalDate.of(2026, 9, 1)));
        when(creation.generate(any())).thenAnswer(invocation -> {
            dailyStarted.countDown();
            releaseDaily.await(10, TimeUnit.SECONDS);
            return 42L;
        });

        var store = mock(ReportDeliveryOutboxStore.class);
        var channels = mock(NotificationChannelRepository.class);
        var senders = mock(NotificationSenderRegistry.class);
        var sender = mock(NotificationSender.class);
        var deliverySession = mock(NotificationSender.DeliverySession.class);
        var claimed = new AtomicBoolean();
        var work = new ReportDeliveryOutboxStore.Work(1L, 42L, 3L, 2L, "batch",
                "테스트 수신자", "recipient@example.test", "테스트 제목", "테스트 본문", 1);
        when(store.claim()).thenAnswer(invocation -> dailyStarted.getCount() == 0 && claimed.compareAndSet(false, true)
                ? List.of(work) : List.of());
        var channel = NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL).build();
        when(channels.findById(2L)).thenReturn(Optional.of(channel));
        when(store.destinationStillActive(work)).thenReturn(true);
        when(senders.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(deliverySession);
        when(deliverySession.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            deliveryStarted.countDown();
            releaseDelivery.await(10, TimeUnit.SECONDS);
            return "test-message-id";
        });

        var telegram = mock(TelegramConnectionAdapter.class);
        var connections = mock(TelegramConnectionService.class);
        var update = new TelegramConnectionAdapter.Update(10L, null);
        var received = new AtomicBoolean();
        when(telegram.configured()).thenReturn(true);
        when(connections.claimPolling()).thenReturn(10L);
        when(telegram.updates(10L)).thenAnswer(invocation -> deliveryStarted.getCount() == 0
                && received.compareAndSet(false, true) ? List.of(update) : List.of());
        doAnswer(invocation -> { telegramAccepted.countDown(); return null; }).when(connections).accept(update);

        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, DailyReportScheduler.class,
                        ReportDeliveryWorker.class, TelegramConnectionPoller.class)
                .withBean(DailyReportJdbcRepository.class, () -> dailyRepository)
                .withBean(DailyReportCreationService.class, () -> creation)
                .withBean(ReportDeliveryOutboxStore.class, () -> store)
                .withBean(NotificationChannelRepository.class, () -> channels)
                .withBean(NotificationSenderRegistry.class, () -> senders)
                .withBean(TelegramConnectionAdapter.class, () -> telegram)
                .withBean(TelegramConnectionService.class, () -> connections)
                .withPropertyValues("news.scheduling.enabled=true", "news.reports.daily.enabled=true",
                        "news.reports.daily.poll-interval-ms=600000", "news.notifications.delivery-poll-ms=10",
                        "news.notifications.telegram.connection-poll-ms=10")
                .run(context -> {
                    try {
                        assertTrue(dailyStarted.await(2, TimeUnit.SECONDS), "일일 보고서 생성이 시작되어야 합니다.");
                        assertTrue(deliveryStarted.await(2, TimeUnit.SECONDS),
                                "일일 보고서 생성이 대기해도 기존 보고서 전달은 시작되어야 합니다.");
                        assertTrue(telegramAccepted.await(2, TimeUnit.SECONDS),
                                "보고서 생성과 메일 전달이 대기해도 텔레그램 연결 업데이트를 처리해야 합니다.");
                        assertEquals(1, releaseDaily.getCount());
                        assertEquals(1, releaseDelivery.getCount());
                    } finally {
                        releaseDaily.countDown();
                        releaseDelivery.countDown();
                    }
                });
    }
}
