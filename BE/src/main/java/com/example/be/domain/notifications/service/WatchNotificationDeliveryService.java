package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.cluster.BreakingWatchAlert;
import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 보고서 발송과 같은 텔레그램·이메일 어댑터를 사용해 속보 후속 문구만 전달한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchNotificationDeliveryService {

    private final NotificationDeliveryPlanService planService;
    private final NotificationSenderRegistry senderRegistry;
    private final NotificationDeliveryPersistenceService persistenceService;

    public int deliver(BreakingWatchAlert alert) {
        final NotificationDeliveryPlanService.PreparedWatchDelivery plan;
        try {
            plan = planService.prepareWatchAlert(
                    alert.notifyGroupId(), alert.issueTitle(), alert.message());
        } catch (RuntimeException exception) {
            log.warn("속보 후속 알림 대상을 준비하지 못했다. watchId={} errorType={}",
                    alert.watchId(), exception.getClass().getSimpleName());
            return 0;
        }

        Map<Long, List<NotificationDeliveryPlanService.PreparedTarget>> byChannel = plan.targets().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        target -> target.channel().getId(), LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        int delivered = 0;
        for (List<NotificationDeliveryPlanService.PreparedTarget> targets : byChannel.values()) {
            delivered += deliverChannel(alert, targets,
                    plan.renderedByChannel().get(targets.getFirst().channel().getId()));
        }
        return delivered;
    }

    private int deliverChannel(BreakingWatchAlert alert,
                               List<NotificationDeliveryPlanService.PreparedTarget> targets,
                               RenderedNotification rendered) {
        NotificationChannel channel = targets.getFirst().channel();
        NotificationSender sender;
        try {
            sender = senderRegistry.get(channel.getChannelType());
            if (!sender.isConfigured(channel)) {
                return 0;
            }
        } catch (RuntimeException exception) {
            logFailure(alert, channel, exception);
            return 0;
        }

        int delivered = 0;
        try (NotificationSender.DeliverySession session = sender.openSession(channel)) {
            for (NotificationDeliveryPlanService.PreparedTarget target : targets) {
                if (!ready(target, sender)) {
                    continue;
                }
                try {
                    for (String chunk : rendered.chunks()) {
                        session.send(target.address(), rendered.subject(), chunk);
                    }
                    delivered++;
                } catch (RuntimeException exception) {
                    logFailure(alert, channel, exception);
                }
            }
        } catch (RuntimeException exception) {
            logFailure(alert, channel, exception);
        }
        return delivered;
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

    private void logFailure(BreakingWatchAlert alert,
                            NotificationChannel channel,
                            RuntimeException exception) {
        log.warn("속보 후속 알림을 보내지 못했다. watchId={} channel={} errorType={}",
                alert.watchId(), channel.getChannelType(), exception.getClass().getSimpleName());
    }
}
