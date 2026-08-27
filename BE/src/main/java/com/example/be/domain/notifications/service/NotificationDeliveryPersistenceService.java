package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryBatch;
import com.example.be.domain.notifications.entity.DeliveryLog;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.repository.DeliveryBatchRepository;
import com.example.be.domain.notifications.repository.DeliveryLogRepository;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.repository.NotificationRecipientRepository;
import com.example.be.domain.notifications.repository.RecipientDestinationRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 외부 발송과 분리된 짧은 트랜잭션으로 배치 예약과 개별 결과를 영속화한다. */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryPersistenceService {

    private final DeliveryBatchRepository batchRepository;
    private final DeliveryLogRepository logRepository;
    private final NewsReportRepository reportRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final NotificationChannelRepository channelRepository;
    private final RecipientDestinationRepository destinationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchInfo reserve(Long reportId, String idempotencyKey) {
        DeliveryBatch batch = batchRepository.saveAndFlush(DeliveryBatch.builder()
                .id(UUID.randomUUID().toString())
                .report(reportRepository.getReferenceById(reportId))
                .idempotencyKey(idempotencyKey)
                .requestedAt(LocalDateTime.now(ApiTimeZone.ZONE))
                .build());
        return toInfo(batch);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<BatchSnapshot> findByIdempotencyKey(String idempotencyKey) {
        return batchRepository.findByIdempotencyKey(idempotencyKey).map(batch -> new BatchSnapshot(
                batch.getId(), batch.getReport().getId(), batch.getRequestedAt(), batch.getCompletedAt(),
                logRepository.findAllByBatchIdOrderByIdAsc(batch.getId()).stream().map(this::toLog).toList()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LogRecord record) {
        logRepository.saveAndFlush(DeliveryLog.builder()
                .batch(batchRepository.getReferenceById(record.batchId()))
                .report(reportRepository.getReferenceById(record.reportId()))
                .recipient(recipientRepository.getReferenceById(record.recipientId()))
                .recipientName(record.recipientName())
                .channel(channelRepository.getReferenceById(record.channelId()))
                .channelType(record.channelType())
                .address(record.address())
                .status(record.status())
                .externalMessageId(record.externalMessageId())
                .chunkSeq(record.chunkSeq())
                .chunkCount(record.chunkCount())
                .errorMessage(record.errorMessage())
                .sentAt(record.sentAt())
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOnboarded(Long destinationId) {
        destinationRepository.findById(destinationId).ifPresent(destination -> {
            if (!destination.isOnboarded()) {
                destination.markOnboarded();
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchInfo complete(String batchId) {
        DeliveryBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("알림 발송 배치를 찾을 수 없습니다: " + batchId));
        batch.complete(LocalDateTime.now(ApiTimeZone.ZONE));
        batchRepository.flush();
        return toInfo(batch);
    }

    private BatchInfo toInfo(DeliveryBatch batch) {
        return new BatchInfo(batch.getId(), batch.getReport().getId(), batch.getRequestedAt());
    }

    private LogSnapshot toLog(DeliveryLog log) {
        return new LogSnapshot(log.getRecipient().getId(), log.getRecipientName(), log.getChannelType(),
                log.getAddress(), log.getStatus(), log.getExternalMessageId(), log.getChunkCount(),
                log.getErrorMessage(), log.getSentAt());
    }

    public record BatchInfo(String id, Long reportId, LocalDateTime requestedAt) {
    }

    public record BatchSnapshot(String id,
                                Long reportId,
                                LocalDateTime requestedAt,
                                LocalDateTime completedAt,
                                List<LogSnapshot> logs) {
    }

    public record LogSnapshot(Long recipientId,
                              String recipientName,
                              ChannelType channelType,
                              String address,
                              DeliveryStatus status,
                              String externalMessageId,
                              Integer chunkCount,
                              String errorMessage,
                              LocalDateTime sentAt) {
    }

    public record LogRecord(String batchId,
                            Long reportId,
                            Long recipientId,
                            String recipientName,
                            Long channelId,
                            ChannelType channelType,
                            String address,
                            DeliveryStatus status,
                            String externalMessageId,
                            Integer chunkSeq,
                            Integer chunkCount,
                            String errorMessage,
                            LocalDateTime sentAt) {
    }
}
