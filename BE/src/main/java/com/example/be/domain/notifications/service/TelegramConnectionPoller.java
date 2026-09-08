package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.TelegramConnectionAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="news.notifications.telegram.connections-enabled",havingValue="true",matchIfMissing=true)
public class TelegramConnectionPoller {
    private final TelegramConnectionAdapter adapter;
    private final TelegramConnectionService connections;
    @Scheduled(fixedDelayString="${news.notifications.telegram.connection-poll-ms:3000}")
    public void poll() {
        if(!adapter.configured()) return;
        Long offset=connections.claimPolling();
        if(offset==null) return;
        try {
            for(var update:adapter.updates(offset)) {
                connections.accept(update);
                connections.advance(update.updateId()+1);
            }
        } catch(RuntimeException ignored) {
            // Retry reception on the next poll. Never log bot URLs, link tokens or Telegram messages.
        } finally { connections.releasePolling(); }
    }
}
