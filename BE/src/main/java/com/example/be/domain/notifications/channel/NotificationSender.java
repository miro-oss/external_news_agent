package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;

public interface NotificationSender {
    ChannelType channelType();
    boolean isConfigured(NotificationChannel channel);
    boolean isOnboarded(NotificationChannel channel, String address);
    String send(NotificationChannel channel, String address, String subject, String body);
}
