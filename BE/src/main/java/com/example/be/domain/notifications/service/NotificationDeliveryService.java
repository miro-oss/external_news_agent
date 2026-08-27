package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.dto.res.NotificationResDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.example.be.domain.notifications.exception.code.NotificationErrorCode;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    private final NewsReportRepository reportRepository;
    private final NotificationManagementService managementService;
    private final NotificationRenderer renderer;
    private final NotificationDeliveryPlanService planService;
    private final NotificationDeliveryPersistenceService persistenceService;
    private final NotificationSenderRegistry senderRegistry;

    @Transactional(readOnly = true)
    public NotificationResDTO.Preview preview(Long reportId, NotificationReqDTO.Preview request) {
        NewsReport report = findReport(reportId);
        if (request == null || request.getChannelId() == null) {
            throw new NotificationException(NotificationErrorCode.CHANNEL_NOT_FOUND);
        }
        NotificationChannel channel = managementService.findChannel(request.getChannelId(), true);
        RenderedNotification rendered = renderer.render(report, channel);
        List<NotificationResDTO.PreviewChunk> chunks = new ArrayList<>();
        for (int index = 0; index < rendered.chunks().size(); index++) {
            String body = rendered.chunks().get(index);
            chunks.add(NotificationResDTO.PreviewChunk.builder()
                    .seq(index + 1).length(body.length()).body(body).build());
        }
        return NotificationResDTO.Preview.builder()
                .reportId(reportId)
                .channelId(channel.getId())
                .channelType(channel.getChannelType().name())
                .parseMode(rendered.parseMode())
                .maxLength(channel.getMaxLength())
                .subject(rendered.subject())
                .chunks(chunks)
                .chunkCount(chunks.size())
                .build();
    }

    /** 배치를 먼저 커밋한 뒤 외부 I/O와 결과 기록을 각각 수행해 중복 발송과 장기 DB 점유를 막는다. */
    public NotificationResDTO.SendBatch send(Long reportId, NotificationReqDTO.Send request) {
        validateSendRequest(request);
        planService.requireReport(reportId);
        String idempotencyKey = normalizeIdempotencyKey(request.getIdempotencyKey());
        if (idempotencyKey != null) {
            var existing = persistenceService.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replay(reportId, existing.get());
            }
        }

        NotificationDeliveryPlanService.PreparedDelivery plan = planService.prepare(reportId, request);
        NotificationDeliveryPersistenceService.BatchInfo batch;
        try {
            batch = persistenceService.reserve(reportId, idempotencyKey);
        } catch (DataIntegrityViolationException exception) {
            if (idempotencyKey == null) {
                throw exception;
            }
            NotificationDeliveryPersistenceService.BatchSnapshot existing = persistenceService
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> exception);
            return replay(reportId, existing);
        }

        List<NotificationResDTO.SendResult> results = deliver(batch, plan);
        persistenceService.complete(batch.id());
        NotificationResDTO.SendBatch response = summarize(batch, results);
        if (response.getFailedCount() == response.getTargetCount()) {
            throw new NotificationException(NotificationErrorCode.DELIVERY_FAILED,
                    Map.of("deliveryBatchId", batch.id(), "targetCount", response.getTargetCount(),
                            "failedCount", response.getFailedCount()));
        }
        return response;
    }

    private List<NotificationResDTO.SendResult> deliver(
            NotificationDeliveryPersistenceService.BatchInfo batch,
            NotificationDeliveryPlanService.PreparedDelivery plan) {
        Map<Long, List<NotificationDeliveryPlanService.PreparedTarget>> byChannel = plan.targets().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        target -> target.channel().getId(), LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        List<NotificationResDTO.SendResult> results = new ArrayList<>();
        for (List<NotificationDeliveryPlanService.PreparedTarget> targets : byChannel.values()) {
            NotificationChannel channel = targets.getFirst().channel();
            RenderedNotification rendered = plan.renderedByChannel().get(channel.getId());
            NotificationSender sender;
            try {
                sender = senderRegistry.get(channel.getChannelType());
            } catch (RuntimeException exception) {
                results.addAll(failTargets(batch, plan.reportId(), targets, rendered,
                        unexpectedFailureMessage()));
                continue;
            }

            final boolean configured;
            try {
                configured = sender.isConfigured(channel);
            } catch (RuntimeException exception) {
                results.addAll(failTargets(batch, plan.reportId(), targets, rendered,
                        unexpectedFailureMessage()));
                continue;
            }
            if (!configured) {
                String message = channel.getChannelType() == ChannelType.TELEGRAM
                        ? "텔레그램 봇 토큰이 설정되지 않았습니다." : "메일 채널 설정이 완료되지 않았습니다.";
                results.addAll(failTargets(batch, plan.reportId(), targets, rendered, message));
                continue;
            }

            NotificationSender.DeliverySession session;
            try {
                session = sender.openSession(channel);
            } catch (RuntimeException exception) {
                results.addAll(failTargets(batch, plan.reportId(), targets, rendered,
                        transportFailureMessage(exception)));
                continue;
            }
            try {
                for (NotificationDeliveryPlanService.PreparedTarget target : targets) {
                    results.add(deliverTarget(batch, plan.reportId(), target, rendered, sender, session));
                }
            } finally {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                    // 개별 발송 결과는 이미 영속화됐으므로 연결 종료 실패로 배치를 미완료 상태로 두지 않는다.
                }
            }
        }
        return results;
    }

    private NotificationResDTO.SendResult deliverTarget(
            NotificationDeliveryPersistenceService.BatchInfo batch,
            Long reportId,
            NotificationDeliveryPlanService.PreparedTarget target,
            RenderedNotification rendered,
            NotificationSender sender,
            NotificationSender.DeliverySession session) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        if (target.channelType() == ChannelType.TELEGRAM && !target.onboarded()) {
            final boolean onboarded;
            try {
                onboarded = sender.isOnboarded(target.channel(), target.address());
            } catch (RuntimeException exception) {
                return recordFailure(batch, reportId, target, rendered, 1,
                        transportFailureMessage(exception), now);
            }
            if (!onboarded) {
                record(batch, reportId, target, DeliveryStatus.SKIPPED, null,
                        null, null, "NOT_ONBOARDED", now);
                return result(target, DeliveryStatus.SKIPPED, null, null, now,
                        "NOT_ONBOARDED", "수신자가 봇에게 /start를 보내지 않았습니다.");
            }
            persistenceService.markOnboarded(target.destinationId());
        }

        List<String> externalIds = new ArrayList<>();
        for (int index = 0; index < rendered.chunks().size(); index++) {
            String externalId;
            try {
                externalId = session.send(target.address(), rendered.subject(), rendered.chunks().get(index));
            } catch (RuntimeException exception) {
                now = LocalDateTime.now(ApiTimeZone.ZONE);
                String message = transportFailureMessage(exception);
                record(batch, reportId, target, DeliveryStatus.FAILED, null,
                        index + 1, rendered.chunks().size(), message, now);
                return result(target, DeliveryStatus.FAILED,
                        externalIds.isEmpty() ? null : String.join(",", externalIds),
                        rendered.chunks().size(), now, null, message);
            }
            externalIds.add(externalId);
            now = LocalDateTime.now(ApiTimeZone.ZONE);
            record(batch, reportId, target, DeliveryStatus.SENT, externalId,
                    index + 1, rendered.chunks().size(), null, now);
        }
        return result(target, DeliveryStatus.SENT,
                externalIds.isEmpty() ? null : String.join(",", externalIds),
                rendered.chunks().size(), now, null, null);
    }

    private List<NotificationResDTO.SendResult> failTargets(
            NotificationDeliveryPersistenceService.BatchInfo batch,
            Long reportId,
            List<NotificationDeliveryPlanService.PreparedTarget> targets,
            RenderedNotification rendered,
            String message) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        return targets.stream().map(target -> recordFailure(
                batch, reportId, target, rendered, 1, message, now)).toList();
    }

    private NotificationResDTO.SendResult recordFailure(
            NotificationDeliveryPersistenceService.BatchInfo batch,
            Long reportId,
            NotificationDeliveryPlanService.PreparedTarget target,
            RenderedNotification rendered,
            int chunkSeq,
            String message,
            LocalDateTime sentAt) {
        record(batch, reportId, target, DeliveryStatus.FAILED, null,
                chunkSeq, rendered.chunks().size(), message, sentAt);
        return result(target, DeliveryStatus.FAILED, null,
                rendered.chunks().size(), sentAt, null, message);
    }

    private void record(NotificationDeliveryPersistenceService.BatchInfo batch,
                        Long reportId,
                        NotificationDeliveryPlanService.PreparedTarget target,
                        DeliveryStatus status,
                        String externalMessageId,
                        Integer chunkSeq,
                        Integer chunkCount,
                        String error,
                        LocalDateTime sentAt) {
        persistenceService.record(new NotificationDeliveryPersistenceService.LogRecord(
                batch.id(), reportId, target.recipientId(), target.recipientName(), target.channel().getId(),
                target.channelType(), target.address(), status, externalMessageId, chunkSeq, chunkCount, error, sentAt));
    }

    private NotificationResDTO.SendResult result(NotificationDeliveryPlanService.PreparedTarget target,
                                                  DeliveryStatus status,
                                                  String externalId,
                                                  Integer chunkCount,
                                                  LocalDateTime sentAt,
                                                  String reason,
                                                  String message) {
        return NotificationResDTO.SendResult.builder()
                .recipientId(target.recipientId()).recipientName(target.recipientName())
                .channelType(target.channelType().name()).address(target.address()).status(status.name())
                .externalMessageId(externalId).chunkCount(chunkCount)
                .sentAt(sentAt.atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                .reason(reason).message(message).build();
    }

    private NotificationResDTO.SendBatch replay(
            Long reportId,
            NotificationDeliveryPersistenceService.BatchSnapshot batch) {
        if (!Objects.equals(reportId, batch.reportId())) {
            throw new GeneralException(GeneralErrorCode.CONFLICT,
                    "다른 보고서에서 이미 사용된 idempotencyKey입니다.");
        }
        if (batch.completedAt() == null) {
            throw new GeneralException(GeneralErrorCode.CONFLICT, "동일한 발송 요청이 진행 중입니다.");
        }
        Map<String, List<NotificationDeliveryPersistenceService.LogSnapshot>> grouped = batch.logs().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        log -> log.recipientId() + ":" + log.channelType() + ":" + log.address(),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<NotificationResDTO.SendResult> results = grouped.values().stream().map(items -> {
            var first = items.getFirst();
            DeliveryStatus status = items.stream().anyMatch(item -> item.status() == DeliveryStatus.FAILED)
                    ? DeliveryStatus.FAILED
                    : items.stream().anyMatch(item -> item.status() == DeliveryStatus.SENT)
                    ? DeliveryStatus.SENT : DeliveryStatus.SKIPPED;
            String ids = items.stream().map(NotificationDeliveryPersistenceService.LogSnapshot::externalMessageId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(","));
            String error = items.stream().map(NotificationDeliveryPersistenceService.LogSnapshot::errorMessage)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            return NotificationResDTO.SendResult.builder()
                    .recipientId(first.recipientId()).recipientName(first.recipientName())
                    .channelType(first.channelType().name()).address(first.address()).status(status.name())
                    .externalMessageId(ids.isBlank() ? null : ids).chunkCount(first.chunkCount())
                    .sentAt(first.sentAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                    .reason(status == DeliveryStatus.SKIPPED ? error : null).message(error).build();
        }).toList();
        return summarize(new NotificationDeliveryPersistenceService.BatchInfo(
                batch.id(), batch.reportId(), batch.requestedAt()), results);
    }

    private NotificationResDTO.SendBatch summarize(
            NotificationDeliveryPersistenceService.BatchInfo batch,
            List<NotificationResDTO.SendResult> results) {
        return NotificationResDTO.SendBatch.builder()
                .deliveryBatchId(batch.id()).reportId(batch.reportId())
                .requestedAt(batch.requestedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                .targetCount(results.size())
                .sentCount((int) results.stream().filter(result -> "SENT".equals(result.getStatus())).count())
                .failedCount((int) results.stream().filter(result -> "FAILED".equals(result.getStatus())).count())
                .skippedCount((int) results.stream().filter(result -> "SKIPPED".equals(result.getStatus())).count())
                .results(results)
                .build();
    }

    private void validateSendRequest(NotificationReqDTO.Send request) {
        if (request == null || request.getGroupIds() == null || request.getGroupIds().isEmpty()) {
            throw new NotificationException(NotificationErrorCode.DELIVERY_NO_TARGET,
                    Map.of("groupIds", List.of()));
        }
    }

    private NewsReport findReport(Long reportId) {
        return reportRepository.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
    }

    private String normalizeIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "idempotencyKey는 100자 이하여야 합니다.");
        }
        return normalized;
    }

    private String transportFailureMessage(RuntimeException exception) {
        if (exception instanceof NotificationTransportException && StringUtils.hasText(exception.getMessage())) {
            return exception.getMessage();
        }
        return unexpectedFailureMessage();
    }

    private String unexpectedFailureMessage() {
        return "알림 전송 중 예상하지 못한 오류가 발생했습니다.";
    }
}
