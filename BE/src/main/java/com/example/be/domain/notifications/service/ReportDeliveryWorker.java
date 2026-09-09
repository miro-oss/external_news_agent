package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="news.notifications.automation-enabled", havingValue="true", matchIfMissing=true)
public class ReportDeliveryWorker {
    private final ReportDeliveryOutboxStore store;
    private final NotificationChannelRepository channels;
    private final NotificationSenderRegistry senders;

    @Scheduled(fixedDelayString="${news.notifications.delivery-poll-ms:5000}", scheduler="reportDeliveryScheduler")
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
            finish(work, type, "SKIPPED", null, "수신 설정이 변경되었거나 텔레그램 연결이 완료되지 않았습니다.", false);
            return;
        }
        NotificationSender sender = senders.get(channel.getChannelType());
        if (!sender.isConfigured(channel)) {
            finish(work, type, "FAILED", null, "전달 채널 연결을 확인해 주세요.", true);
            return;
        }
        NotificationSender.DeliverySession session;
        try { session = sender.openSession(channel); }
        catch (RuntimeException failure) {
            finish(work, type, "FAILED", null, safeMessage(failure, "전달 서버에 연결하지 못했습니다."), true);
            return;
        }
        String externalId;
        try { externalId = session.send(work.address(),work.subject(),work.body()); }
        catch (RuntimeException failure) {
            // A transport timeout may occur after the provider accepted a message.
            boolean knownFailure = failure instanceof NotificationTransportException transport && transport.isDefinitelyRejected();
            finish(work, type, knownFailure ? "FAILED" : "UNKNOWN", null,
                    knownFailure ? safeMessage(failure,"전송이 거절되었습니다.") : "전송 결과를 확인하지 못했습니다. 중복 전달을 피하려고 자동 재시도를 멈췄습니다.", knownFailure);
            return;
        } finally { try { session.close(); } catch (RuntimeException ignored) { } }
        // Persist exceptions deliberately propagate: leaving PROCESSING prevents an uncertain resend.
        finish(work,type,"SENT",externalId,null,false);
    }

    private void finish(ReportDeliveryOutboxStore.Work work, String type, String status,
                        String externalId, String message, boolean retryable) {
        if (!"SENT".equals(status)) {
            log.warn("보고서 자동 전달 결과. reportId={} deliveryId={} batchId={} channelType={} status={} attempt={} retryable={} reason={}",
                    work.reportId(), work.id(), work.batchId(), type, status, work.attempts(), retryable, message);
        }
        store.finish(work, type, status, externalId, message, retryable);
    }

    private String safeMessage(RuntimeException error,String fallback) {
        return error instanceof NotificationTransportException ? error.getMessage() : fallback;
    }
}
