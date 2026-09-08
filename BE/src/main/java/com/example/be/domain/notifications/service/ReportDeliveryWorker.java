package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="news.notifications.automation-enabled", havingValue="true", matchIfMissing=true)
public class ReportDeliveryWorker {
    private final ReportDeliveryOutboxStore store;
    private final NotificationChannelRepository channels;
    private final NotificationSenderRegistry senders;

    @Scheduled(fixedDelayString="${news.notifications.delivery-poll-ms:5000}")
    public void deliverPending() {
        for (int delivered = 0; delivered < 30; delivered++) {
            var claimed = store.claim();
            if (claimed.isEmpty()) return;
            for (var work : claimed) deliver(work);
        }
    }

    void deliver(ReportDeliveryOutboxStore.Work work) {
        var channel = channels.findById(work.channelId()).orElse(null);
        if (channel == null) return; // FK prevents removal while a delivery exists.
        String type = channel.getChannelType().name();
        if (!store.destinationStillActive(work)) {
            store.finish(work, type, "SKIPPED", null, "수신 설정이 변경되었거나 텔레그램 연결이 완료되지 않았습니다.", false);
            return;
        }
        NotificationSender sender = senders.get(channel.getChannelType());
        if (!sender.isConfigured(channel)) {
            store.finish(work, type, "FAILED", null, "전달 채널 연결을 확인해 주세요.", true);
            return;
        }
        NotificationSender.DeliverySession session;
        try { session = sender.openSession(channel); }
        catch (RuntimeException failure) {
            store.finish(work, type, "FAILED", null, safeMessage(failure, "전달 서버에 연결하지 못했습니다."), true);
            return;
        }
        String externalId;
        try { externalId = session.send(work.address(),work.subject(),work.body()); }
        catch (RuntimeException failure) {
            // A transport timeout may occur after the provider accepted a message.
            boolean knownFailure = failure instanceof NotificationTransportException transport && transport.isDefinitelyRejected();
            store.finish(work, type, knownFailure ? "FAILED" : "UNKNOWN", null,
                    knownFailure ? safeMessage(failure,"전송이 거절되었습니다.") : "전송 결과를 확인하지 못했습니다. 중복 전달을 피하려고 자동 재시도를 멈췄습니다.", knownFailure);
            return;
        } finally { try { session.close(); } catch (RuntimeException ignored) { } }
        // Persist exceptions deliberately propagate: leaving PROCESSING prevents an uncertain resend.
        store.finish(work,type,"SENT",externalId,null,false);
    }

    private String safeMessage(RuntimeException error,String fallback) {
        return error instanceof NotificationTransportException ? error.getMessage() : fallback;
    }
}
