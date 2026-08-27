package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;

public interface NotificationSender {
    ChannelType channelType();
    boolean isConfigured(NotificationChannel channel);
    boolean isOnboarded(NotificationChannel channel, String address);
    String send(NotificationChannel channel, String address, String subject, String body);

    default DeliverySession openSession(NotificationChannel channel) {
        return new DeliverySession() {
            @Override
            public String send(String address, String subject, String body) {
                return NotificationSender.this.send(channel, address, subject, body);
            }
        };
    }

    interface DeliverySession extends AutoCloseable {
        String send(String address, String subject, String body);

        @Override
        default void close() {
        }
    }
}
