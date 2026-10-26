package com.example.be.domain.notifications.channel;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.service.NotificationAttachment;

import java.util.List;

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

        /**
         * Sends one rendered message with its recipient-specific attachments.
         * Channels must explicitly support attachments; the default rejects them before sending,
         * preventing a successful text-only delivery from silently dropping a required PDF.
         */
        default String send(String address, String subject, String body, List<NotificationAttachment> attachments) {
            if (!attachments.isEmpty()) {
                throw new IllegalArgumentException("This delivery channel does not support attachments");
            }
            return send(address, subject, body);
        }

        @Override
        default void close() {
        }
    }
}
