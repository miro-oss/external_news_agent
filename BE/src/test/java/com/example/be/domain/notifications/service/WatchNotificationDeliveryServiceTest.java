package com.example.be.domain.notifications.service;

import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.service.WatchAlertOutboxPersistenceService.WatchAlertSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import com.example.be.domain.notifications.service.WatchAlertOutboxBatchClaimer.BatchClaim;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

        stubSingleBatch(List.of(alert));

        int delivered = service.deliverPending();

        assertEquals(1, delivered);
        verify(session).send("user@example.com", rendered.subject(), rendered.chunks().getFirst());
        verify(session).close();
        verify(outboxPersistenceService).markSent(60L);
    }

    @Test
    void keepsAlertPendingWhenNoDeliverySucceeds() {
        WatchAlertSnapshot alert = alert();
        stubSingleBatch(List.of(alert));
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
                61L, 51L, WatchType.HIGH_SENSITIVITY, 71L, null, "SK하이닉스 HBM4 증설",
                first.firstSeenAt(), 2, 3, first.queuedAt(), 1);
        RenderedNotification firstRendered = new RenderedNotification(
                "[속보 후속] 삼성전자", null, List.of("첫 번째"));
        RenderedNotification secondRendered = new RenderedNotification(
                "[속보 후속] SK하이닉스", null, List.of("두 번째"));
        stubSingleBatch(List.of(first, second));
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

    @Test
    void deduplicatesWatchTypesForSameIssueAndRecipient() {
        NotificationChannel channel = NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일").maxLength(Integer.MAX_VALUE).active(true)
                .build();
        NotificationDeliveryPlanService.PreparedTarget target =
                new NotificationDeliveryPlanService.PreparedTarget(
                        3L, "김철수", channel, 4L, "user@example.com", true);
        WatchAlertSnapshot first = alert();
        WatchAlertSnapshot second = new WatchAlertSnapshot(
                61L, 51L, WatchType.HIGH_SENSITIVITY, first.issueId(), null, first.issueTitle(),
                first.firstSeenAt(), 2, 3, first.queuedAt(), 1);
        RenderedNotification rendered = new RenderedNotification(
                "[속보 후속] 삼성전자", null, List.of("후속 내용"));
        stubSingleBatch(List.of(first, second));
        when(planService.prepareWatchAlerts(any())).thenReturn(Map.of(
                first.id(), new NotificationDeliveryPlanService.PreparedWatchDelivery(
                        List.of(target), Map.of(2L, rendered)),
                second.id(), new NotificationDeliveryPlanService.PreparedWatchDelivery(
                        List.of(target), Map.of(2L, rendered))));
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        when(senderRegistry.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);

        assertEquals(1, service.deliverPending());

        verify(session, times(1)).send("user@example.com", rendered.subject(), "후속 내용");
        verify(outboxPersistenceService).markSent(60L);
        verify(outboxPersistenceService).markSent(61L);
    }

    @Test
    void sendsAndCompletesEachBatchBeforeScanningLaterHiddenCandidates() {
        WatchAlertSnapshot first = alert();
        WatchAlertSnapshot second = otherAlert(61L, 71L);
        DeliveryFixture fixture = prepareDeliveries(List.of(first, second));
        when(outboxPersistenceService.claimNextBatch(eq(0L), any(), eq(100)))
                .thenReturn(new BatchClaim(List.of(first), 100L, false));
        when(outboxPersistenceService.claimNextBatch(eq(100L), any(), eq(99)))
                .thenReturn(new BatchClaim(List.of(), 200L, false));
        when(outboxPersistenceService.claimNextBatch(eq(200L), any(), eq(99)))
                .thenReturn(new BatchClaim(List.of(second), 201L, true));

        assertEquals(2, service.deliverPending());

        InOrder order = inOrder(outboxPersistenceService, fixture.session());
        order.verify(outboxPersistenceService).claimNextBatch(eq(0L), any(), eq(100));
        order.verify(fixture.session()).send("user@example.com", "알림", "본문 60");
        order.verify(outboxPersistenceService).markSent(60L);
        order.verify(outboxPersistenceService).claimNextBatch(eq(100L), any(), eq(99));
        order.verify(outboxPersistenceService).claimNextBatch(eq(200L), any(), eq(99));
        order.verify(fixture.session()).send("user@example.com", "알림", "본문 61");
        order.verify(outboxPersistenceService).markSent(61L);
    }

    @Test
    void deduplicatesAcrossBatchesAndCompletesDuplicateOutboxWithoutRetrying() {
        WatchAlertSnapshot first = alert();
        WatchAlertSnapshot second = otherAlert(61L, first.issueId());
        DeliveryFixture fixture = prepareDeliveries(List.of(first, second));
        when(outboxPersistenceService.claimNextBatch(eq(0L), any(), eq(100)))
                .thenReturn(new BatchClaim(List.of(first), 100L, false));
        when(outboxPersistenceService.claimNextBatch(eq(100L), any(), eq(99)))
                .thenReturn(new BatchClaim(List.of(second), 101L, true));

        assertEquals(1, service.deliverPending());

        verify(fixture.session(), times(1)).send(any(), any(), any());
        verify(fixture.sender(), times(1)).openSession(fixture.channel());
        verify(outboxPersistenceService).markSent(60L);
        verify(outboxPersistenceService).markSent(61L);
        verify(outboxPersistenceService, never()).retry(anyLong(), any());
    }

    @Test
    void stopsAtOneHundredClaimedAlertsEvenWhenDeliveriesFail() {
        List<WatchAlertSnapshot> alerts = new ArrayList<>();
        for (long id = 1; id <= 100; id++) {
            alerts.add(otherAlert(id, id));
        }
        when(outboxPersistenceService.claimNextBatch(eq(0L), any(), eq(100)))
                .thenReturn(new BatchClaim(alerts.subList(0, 60), 100L, false));
        when(outboxPersistenceService.claimNextBatch(eq(100L), any(), eq(40)))
                .thenReturn(new BatchClaim(alerts.subList(60, 100), 200L, false));
        when(planService.prepareWatchAlerts(any())).thenReturn(Map.of());

        assertEquals(0, service.deliverPending());

        verify(outboxPersistenceService, times(2)).claimNextBatch(anyLong(), any(), anyInt());
        verify(outboxPersistenceService, times(100)).retry(anyLong(), any());
    }

    @Test
    void preparationFailureRetriesItsBatchBeforeContinuingTheCursor() {
        WatchAlertSnapshot first = alert();
        WatchAlertSnapshot second = otherAlert(61L, 71L);
        when(outboxPersistenceService.claimNextBatch(eq(0L), any(), eq(100)))
                .thenReturn(new BatchClaim(List.of(first), 100L, false));
        when(outboxPersistenceService.claimNextBatch(eq(100L), any(), eq(99)))
                .thenReturn(new BatchClaim(List.of(second), 101L, true));
        when(planService.prepareWatchAlerts(any())).thenThrow(new IllegalStateException("synthetic"))
                .thenReturn(Map.of());

        assertEquals(0, service.deliverPending());

        InOrder order = inOrder(outboxPersistenceService);
        order.verify(outboxPersistenceService).claimNextBatch(eq(0L), any(), eq(100));
        order.verify(outboxPersistenceService).retry(60L, "알림 대상 준비 실패: IllegalStateException");
        order.verify(outboxPersistenceService).claimNextBatch(eq(100L), any(), eq(99));
        order.verify(outboxPersistenceService).retry(61L, "발송 가능한 대상 또는 성공한 전송이 없습니다.");
    }

    private DeliveryFixture prepareDeliveries(List<WatchAlertSnapshot> alerts) {
        NotificationChannel channel = NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL)
                .name("메일").maxLength(Integer.MAX_VALUE).active(true).build();
        var target = new NotificationDeliveryPlanService.PreparedTarget(
                3L, "수신자", channel, 4L, "user@example.com", true);
        Map<Long, NotificationDeliveryPlanService.PreparedWatchDelivery> plans = new java.util.LinkedHashMap<>();
        for (WatchAlertSnapshot alert : alerts) {
            plans.put(alert.id(), new NotificationDeliveryPlanService.PreparedWatchDelivery(List.of(target),
                    Map.of(2L, new RenderedNotification("알림", null, List.of("본문 " + alert.id())))));
        }
        when(planService.prepareWatchAlerts(any())).thenReturn(plans);
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        when(senderRegistry.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);
        return new DeliveryFixture(sender, session, channel);
    }

    private record DeliveryFixture(NotificationSender sender, NotificationSender.DeliverySession session,
                                   NotificationChannel channel) { }

    private WatchAlertSnapshot otherAlert(Long id, Long issueId) {
        WatchAlertSnapshot first = alert();
        return new WatchAlertSnapshot(id, id, WatchType.HIGH_SENSITIVITY, issueId, null, "후속 기사 " + id,
                first.firstSeenAt(), 2, 3, first.queuedAt(), 1);
    }

    private void stubSingleBatch(List<WatchAlertSnapshot> alerts) {
        when(outboxPersistenceService.claimNextBatch(anyLong(), any(), anyInt()))
                .thenReturn(new BatchClaim(alerts, alerts.getLast().id(), true));
    }

    private WatchAlertSnapshot alert() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-31T12:00:00+09:00");
        return new WatchAlertSnapshot(60L, 50L, WatchType.BREAKING, 70L, null, "삼성전자 HBM4 증설",
                now.minusHours(2), 1, 2, now, 1);
    }
}
