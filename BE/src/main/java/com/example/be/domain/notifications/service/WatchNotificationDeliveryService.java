package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.service.WatchAlertOutboxPersistenceService.WatchAlertSnapshot;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 보고서 발송과 같은 텔레그램·이메일 어댑터를 사용해 속보 후속 문구만 전달한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchNotificationDeliveryService {

    private static final int CLAIM_LIMIT = 100;
    private static final int MAX_SCAN_BATCHES = 10;

    private final NotificationDeliveryPlanService planService;
    private final NotificationSenderRegistry senderRegistry;
    private final NotificationDeliveryPersistenceService persistenceService;
    private final WatchAlertOutboxPersistenceService outboxPersistenceService;
    private final AtomicBoolean delivering = new AtomicBoolean();
    private long scanAfterId;
    private long scanUpToId;

    public int deliverPending() {
        if (!delivering.compareAndSet(false, true)) {
            return 0;
        }
        try {
            return scanAndDeliver();
        } finally {
            delivering.set(false);
        }
    }

    private int scanAndDeliver() {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Map<Long, Set<String>> deliveredRecipientsByChannel = new LinkedHashMap<>();
        if (scanUpToId == 0) {
            // Freeze this finite sweep so continuous arrivals cannot prevent revisiting recovered low IDs.
            scanUpToId = outboxPersistenceService.scanUpperBound();
            if (scanUpToId == 0) {
                return 0;
            }
        }
        int claimed = 0;
        int delivered = 0;
        // Each repository batch reads at most 100 candidates, including hidden ones.
        for (int batchCount = 0; batchCount < MAX_SCAN_BATCHES && claimed < CLAIM_LIMIT; batchCount++) {
            var batch = outboxPersistenceService.claimNextBatch(scanAfterId, scanUpToId, now, CLAIM_LIMIT - claimed);
            scanAfterId = batch.afterId();
            claimed += batch.snapshots().size();
            boolean sweepComplete = batch.exhausted() || scanAfterId >= scanUpToId;
            if (sweepComplete) {
                scanAfterId = 0;
                scanUpToId = 0;
            }
            // Deliver each committed batch before scanning more hidden candidates and ageing its lease.
            delivered += deliverClaimed(batch.snapshots(), deliveredRecipientsByChannel);
            if (sweepComplete) {
                break;
            }
        }
        return delivered;
    }

    private int deliverClaimed(List<WatchAlertSnapshot> alerts,
                               Map<Long, Set<String>> deliveredRecipientsByChannel) {
        if (alerts.isEmpty()) {
            return 0;
        }

        final Map<Long, NotificationDeliveryPlanService.PreparedWatchDelivery> plans;
        try {
            plans = planService.prepareWatchAlerts(alerts.stream()
                    .map(alert -> new NotificationDeliveryPlanService.WatchAlertRequest(
                            alert.id(), alert.notifyGroupId(), alert.issueTitle(), alert.message()))
                    .toList());
        } catch (RuntimeException exception) {
            alerts.forEach(alert -> {
                log.warn("속보 후속 알림 대상을 준비하지 못했다. watchId={} errorType={}",
                        alert.watchId(), exception.getClass().getSimpleName());
                outboxPersistenceService.retry(alert.id(), "알림 대상 준비 실패: "
                        + exception.getClass().getSimpleName());
            });
            return 0;
        }

        Map<Long, Integer> deliveredByAlert = deliverBatch(alerts, plans, deliveredRecipientsByChannel);
        int delivered = 0;
        for (WatchAlertSnapshot alert : alerts) {
            Integer alertDeliveries = deliveredByAlert.get(alert.id());
            if (alertDeliveries != null) {
                outboxPersistenceService.markSent(alert.id());
                delivered += alertDeliveries;
            } else {
                outboxPersistenceService.retry(alert.id(), "발송 가능한 대상 또는 성공한 전송이 없습니다.");
            }
        }
        return delivered;
    }

    private Map<Long, Integer> deliverBatch(
            List<WatchAlertSnapshot> alerts,
            Map<Long, NotificationDeliveryPlanService.PreparedWatchDelivery> plans,
            Map<Long, Set<String>> deliveredRecipientsByChannel) {
        Map<Long, List<ChannelWork>> byChannel = new LinkedHashMap<>();
        for (WatchAlertSnapshot alert : alerts) {
            NotificationDeliveryPlanService.PreparedWatchDelivery plan = plans.get(alert.id());
            if (plan == null) {
                continue;
            }
            plan.targets().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            target -> target.channel().getId(), LinkedHashMap::new,
                            java.util.stream.Collectors.toList()))
                    .forEach((channelId, targets) -> byChannel
                            .computeIfAbsent(channelId, ignored -> new java.util.ArrayList<>())
                            .add(new ChannelWork(alert, targets,
                                    plan.renderedByChannel().get(channelId))));
        }
        Map<Long, Integer> deliveredByAlert = new LinkedHashMap<>();
        byChannel.forEach((channelId, works) -> deliverChannel(works, deliveredByAlert,
                deliveredRecipientsByChannel.computeIfAbsent(channelId, ignored -> new HashSet<>())));
        return deliveredByAlert;
    }

    private void deliverChannel(List<ChannelWork> works,
                                Map<Long, Integer> deliveredByAlert,
                                Set<String> deliveredRecipients) {
        boolean hasUndeliveredTarget = false;
        for (ChannelWork work : works) {
            for (NotificationDeliveryPlanService.PreparedTarget target : work.targets()) {
                if (deliveredRecipients.contains(recipientKey(work.alert(), target))) {
                    deliveredByAlert.putIfAbsent(work.alert().id(), 0);
                } else {
                    hasUndeliveredTarget = true;
                }
            }
        }
        if (!hasUndeliveredTarget) {
            return;
        }
        NotificationChannel channel = works.getFirst().targets().getFirst().channel();
        NotificationSender sender;
        try {
            sender = senderRegistry.get(channel.getChannelType());
            if (!sender.isConfigured(channel)) {
                return;
            }
        } catch (RuntimeException exception) {
            logFailure(works.getFirst().alert(), channel, exception);
            return;
        }

        Map<Long, Boolean> readyByDestination = new LinkedHashMap<>();
        Map<String, Set<Long>> alertIdsByRecipient = new LinkedHashMap<>();
        for (ChannelWork work : works) {
            for (NotificationDeliveryPlanService.PreparedTarget target : work.targets()) {
                String recipientKey = recipientKey(work.alert(), target);
                alertIdsByRecipient.computeIfAbsent(recipientKey, ignored -> new HashSet<>())
                        .add(work.alert().id());
            }
        }
        try (NotificationSender.DeliverySession session = sender.openSession(channel)) {
            for (ChannelWork work : works) {
                for (NotificationDeliveryPlanService.PreparedTarget target : work.targets()) {
                    String recipientKey = recipientKey(work.alert(), target);
                    if (deliveredRecipients.contains(recipientKey)) {
                        // A prior batch already delivered this issue to this recipient on this channel.
                        alertIdsByRecipient.get(recipientKey)
                                .forEach(alertId -> deliveredByAlert.putIfAbsent(alertId, 0));
                        continue;
                    }
                    boolean targetReady = readyByDestination.computeIfAbsent(
                            target.destinationId(), ignored -> ready(target, sender));
                    if (!targetReady) {
                        continue;
                    }
                    try {
                        for (String chunk : work.rendered().chunks()) {
                            session.send(target.address(), work.rendered().subject(), chunk);
                        }
                        deliveredRecipients.add(recipientKey);
                        alertIdsByRecipient.get(recipientKey)
                                .forEach(alertId -> deliveredByAlert.putIfAbsent(alertId, 0));
                        deliveredByAlert.merge(work.alert().id(), 1, Integer::sum);
                    } catch (RuntimeException exception) {
                        logFailure(work.alert(), channel, exception);
                    }
                }
            }
        } catch (RuntimeException exception) {
            logFailure(works.getFirst().alert(), channel, exception);
        }
    }

    private String recipientKey(WatchAlertSnapshot alert,
                                NotificationDeliveryPlanService.PreparedTarget target) {
        return alert.issueId() + ":" + target.recipientId();
    }

    private boolean ready(NotificationDeliveryPlanService.PreparedTarget target,
                          NotificationSender sender) {
        if (target.channelType() != ChannelType.TELEGRAM || target.onboarded()) {
            return true;
        }
        try {
            if (!sender.isOnboarded(target.channel(), target.address())) {
                return false;
            }
            persistenceService.markOnboarded(target.destinationId());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void logFailure(WatchAlertSnapshot alert,
                            NotificationChannel channel,
                            RuntimeException exception) {
        log.warn("속보 후속 알림을 보내지 못했다. watchId={} channel={} errorType={}",
                alert.watchId(), channel.getChannelType(), exception.getClass().getSimpleName());
    }

    private record ChannelWork(
            WatchAlertSnapshot alert,
            List<NotificationDeliveryPlanService.PreparedTarget> targets,
            RenderedNotification rendered) {
    }
}
