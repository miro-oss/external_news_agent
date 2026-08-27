package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.entity.ChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class NotificationSenderRegistry {

    private final Map<ChannelType, NotificationSender> senders = new EnumMap<>(ChannelType.class);

    public NotificationSenderRegistry(List<NotificationSender> senderList) {
        senderList.forEach(sender -> senders.put(sender.channelType(), sender));
    }

    public NotificationSender get(ChannelType type) {
        NotificationSender sender = senders.get(type);
        if (sender == null) {
            throw new IllegalStateException("알림 채널 어댑터가 없습니다: " + type);
        }
        return sender;
    }
}
