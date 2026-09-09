package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.TelegramConnectionAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="news.notifications.telegram.connections-enabled",havingValue="true",matchIfMissing=true)
public class TelegramConnectionPoller {
    private final TelegramConnectionAdapter adapter;
    private final TelegramConnectionService connections;
    private boolean receiving;
    private ReceptionState lastReceptionState;

    private enum ReceptionState { EMPTY, RECEIVED }

    @Scheduled(fixedDelayString="${news.notifications.telegram.connection-poll-ms:3000}", scheduler="telegramConnectionScheduler")
    public void poll() {
        if(!adapter.configured()) {
            resetReceptionState();
            return;
        }
        Long offset=connections.claimPolling();
        if(offset==null) {
            resetReceptionState();
            return;
        }
        if(!receiving) {
            log.info("텔레그램 연결 업데이트 수신을 시작합니다.");
            receiving=true;
        }
        try {
            var updates=adapter.updates(offset);
            var state=updates.isEmpty()?ReceptionState.EMPTY:ReceptionState.RECEIVED;
            if(state!=lastReceptionState) {
                log.info("텔레그램 연결 업데이트 수신 결과. state={}",state);
                lastReceptionState=state;
            }
            for(var update:updates) {
                connections.accept(update);
                connections.advance(update.updateId()+1);
            }
        } catch(RuntimeException failure) {
            lastReceptionState=null;
            // Retry reception on the next poll. Never log bot URLs, link tokens or Telegram messages.
            log.warn("텔레그램 연결 업데이트 처리 실패. type={}", failure.getClass().getSimpleName());
        } finally { connections.releasePolling(); }
    }

    private void resetReceptionState() {
        receiving=false;
        lastReceptionState=null;
    }
}
