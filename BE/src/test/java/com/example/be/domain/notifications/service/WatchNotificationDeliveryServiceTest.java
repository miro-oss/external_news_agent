package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.cluster.BreakingWatchAlert;
import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchNotificationDeliveryServiceTest {

    private final NotificationDeliveryPlanService planService = mock(NotificationDeliveryPlanService.class);
    private final NotificationSenderRegistry senderRegistry = mock(NotificationSenderRegistry.class);
    private final NotificationDeliveryPersistenceService persistenceService =
            mock(NotificationDeliveryPersistenceService.class);
    private final WatchNotificationDeliveryService service = new WatchNotificationDeliveryService(
            planService, senderRegistry, persistenceService);

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
        BreakingWatchAlert alert = alert();
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        when(planService.prepareWatchAlert(null, alert.issueTitle(), alert.message()))
                .thenReturn(new NotificationDeliveryPlanService.PreparedWatchDelivery(
                        List.of(target), Map.of(2L, rendered)));
        when(senderRegistry.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);

        int delivered = service.deliver(alert);

        assertEquals(1, delivered);
        verify(session).send("user@example.com", rendered.subject(), rendered.chunks().getFirst());
        verify(session).close();
    }

    private BreakingWatchAlert alert() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T12:00:00+09:00");
        return new BreakingWatchAlert(50L, null, "삼성전자 HBM4 증설",
                now.minusHours(2), 1, 2, now);
    }
}
