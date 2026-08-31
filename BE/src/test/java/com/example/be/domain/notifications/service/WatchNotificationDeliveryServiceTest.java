package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.service.WatchAlertOutboxPersistenceService.WatchAlertSnapshot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchNotificationDeliveryServiceTest {

    private final NotificationDeliveryPlanService planService = mock(NotificationDeliveryPlanService.class);
    private final NotificationSenderRegistry senderRegistry = mock(NotificationSenderRegistry.class);
    private final NotificationDeliveryPersistenceService persistenceService =
            mock(NotificationDeliveryPersistenceService.class);
    private final WatchAlertOutboxPersistenceService outboxPersistenceService =
            mock(WatchAlertOutboxPersistenceService.class);
    private final WatchNotificationDeliveryService service = new WatchNotificationDeliveryService(
            planService, senderRegistry, persistenceService, outboxPersistenceService);

    @Test
    void sendsOneBreakingAlertThroughExistingEmailChannel() {
        NotificationChannel channel = NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일").maxLength(Integer.MAX_VALUE).active(true)
                .build();
        NotificationDeliveryPlanService.PreparedTarget target =
                new NotificationDeliveryPlanService.PreparedTarget(
                        3L, "김철수", channel, 4L, "user@example.com", true);
        RenderedNotification rendered = new RenderedNotification(
                "[속보 후속] HBM4", null, List.of("<p>후속 1건</p>"));
        WatchAlertSnapshot alert = alert();
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        when(planService.prepareWatchAlerts(any())).thenReturn(Map.of(
                alert.id(), new NotificationDeliveryPlanService.PreparedWatchDelivery(
                        List.of(target), Map.of(2L, rendered))));
        when(senderRegistry.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);

        when(outboxPersistenceService.claimPending()).thenReturn(List.of(alert));

        int delivered = service.deliverPending();

        assertEquals(1, delivered);
        verify(session).send("user@example.com", rendered.subject(), rendered.chunks().getFirst());
        verify(session).close();
        verify(outboxPersistenceService).markSent(60L);
    }

    @Test
    void keepsAlertPendingWhenNoDeliverySucceeds() {
        WatchAlertSnapshot alert = alert();
        when(outboxPersistenceService.claimPending()).thenReturn(List.of(alert));
        when(planService.prepareWatchAlerts(any())).thenReturn(Map.of(
                alert.id(), new NotificationDeliveryPlanService.PreparedWatchDelivery(List.of(), Map.of())));

        assertEquals(0, service.deliverPending());

        verify(outboxPersistenceService).retry(60L, "발송 가능한 대상 또는 성공한 전송이 없습니다.");
    }

    @Test
    void reusesOneChannelSessionForMultiplePendingAlerts() {
        NotificationChannel channel = NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일").maxLength(Integer.MAX_VALUE).active(true)
                .build();
        NotificationDeliveryPlanService.PreparedTarget target =
                new NotificationDeliveryPlanService.PreparedTarget(
                        3L, "김철수", channel, 4L, "user@example.com", true);
        WatchAlertSnapshot first = alert();
        WatchAlertSnapshot second = new WatchAlertSnapshot(
                61L, 51L, null, "SK하이닉스 HBM4 증설",
                first.firstSeenAt(), 2, 3, first.queuedAt(), 1);
        RenderedNotification firstRendered = new RenderedNotification(
                "[속보 후속] 삼성전자", null, List.of("첫 번째"));
        RenderedNotification secondRendered = new RenderedNotification(
                "[속보 후속] SK하이닉스", null, List.of("두 번째"));
        when(outboxPersistenceService.claimPending()).thenReturn(List.of(first, second));
        when(planService.prepareWatchAlerts(any())).thenReturn(Map.of(
                first.id(), new NotificationDeliveryPlanService.PreparedWatchDelivery(
                        List.of(target), Map.of(2L, firstRendered)),
                second.id(), new NotificationDeliveryPlanService.PreparedWatchDelivery(
                        List.of(target), Map.of(2L, secondRendered))));
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        when(senderRegistry.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);

        assertEquals(2, service.deliverPending());

        verify(sender, times(1)).openSession(channel);
        verify(session).send("user@example.com", firstRendered.subject(), "첫 번째");
        verify(session).send("user@example.com", secondRendered.subject(), "두 번째");
        verify(outboxPersistenceService).markSent(60L);
        verify(outboxPersistenceService).markSent(61L);
    }

    private WatchAlertSnapshot alert() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T12:00:00+09:00");
        return new WatchAlertSnapshot(60L, 50L, null, "삼성전자 HBM4 증설",
                now.minusHours(2), 1, 2, now, 1);
    }
}
