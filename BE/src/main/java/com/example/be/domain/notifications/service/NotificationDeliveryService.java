package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.dto.res.NotificationResDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryBatch;
import com.example.be.domain.notifications.entity.DeliveryLog;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.example.be.domain.notifications.exception.code.NotificationErrorCode;
import com.example.be.domain.notifications.repository.DeliveryBatchRepository;
import com.example.be.domain.notifications.repository.DeliveryLogRepository;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    private final NewsReportRepository reportRepository;
    private final NotificationChannelRepository channelRepository;
    private final DeliveryBatchRepository batchRepository;
    private final DeliveryLogRepository logRepository;
    private final NotificationManagementService managementService;
    private final NotificationRenderer renderer;
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

    @Transactional(noRollbackFor = NotificationException.class)
    public NotificationResDTO.SendBatch send(Long reportId, NotificationReqDTO.Send request) {
        NewsReport report = findReport(reportId);
        if (request == null || request.getGroupIds() == null || request.getGroupIds().isEmpty()) {
            throw new NotificationException(NotificationErrorCode.DELIVERY_NO_TARGET,
                    Map.of("groupIds", List.of()));
        }
        String idempotencyKey = normalizeIdempotencyKey(request.getIdempotencyKey());
        if (idempotencyKey != null) {
            var existing = batchRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return summarizeLogs(existing.get(), logRepository.findAllByBatchIdOrderByIdAsc(existing.get().getId()));
            }
        }

        List<NotificationChannel> channels = resolveChannels(request.getChannelIds());
        List<NotificationGroup> groups = request.getGroupIds().stream().filter(Objects::nonNull).distinct()
                .map(id -> managementService.findGroup(id, true)).toList();
        List<Target> targets = resolveTargets(groups, channels);
        if (targets.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.DELIVERY_NO_TARGET,
                    Map.of("groupIds", request.getGroupIds()));
        }

        LocalDateTime requestedAt = LocalDateTime.now(ApiTimeZone.ZONE);
        DeliveryBatch batch = batchRepository.save(DeliveryBatch.builder()
                .id(UUID.randomUUID().toString())
                .report(report)
                .idempotencyKey(idempotencyKey)
                .requestedAt(requestedAt)
                .build());
        Map<Long, RenderedNotification> renderedByChannel = new LinkedHashMap<>();
        List<NotificationResDTO.SendResult> results = new ArrayList<>();
        for (Target target : targets) {
            RenderedNotification rendered = renderedByChannel.computeIfAbsent(target.channel().getId(),
                    ignored -> renderer.render(report, target.channel()));
            results.add(deliver(batch, report, target, rendered));
        }

        NotificationResDTO.SendBatch response = summarize(batch, results);
        if (response.getFailedCount() == response.getTargetCount()) {
            throw new NotificationException(NotificationErrorCode.DELIVERY_FAILED,
                    Map.of("deliveryBatchId", batch.getId(), "targetCount", response.getTargetCount(),
                            "failedCount", response.getFailedCount()));
        }
        return response;
    }

    private NotificationResDTO.SendResult deliver(DeliveryBatch batch,
                                                   NewsReport report,
                                                   Target target,
                                                   RenderedNotification rendered) {
        RecipientDestination destination = target.destination();
        NotificationSender sender = senderRegistry.get(target.channel().getChannelType());
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);

        if (!sender.isConfigured(target.channel())) {
            String reason = target.channel().getChannelType() == ChannelType.TELEGRAM
                    ? "텔레그램 봇 토큰이 설정되지 않았습니다." : "메일 채널 설정이 완료되지 않았습니다.";
            logRepository.save(log(batch, report, target, DeliveryStatus.FAILED, null,
                    1, rendered.chunks().size(), reason, now));
            return result(target, DeliveryStatus.FAILED, null, rendered.chunks().size(), now,
                    null, reason);
        }

        if (target.channel().getChannelType() == ChannelType.TELEGRAM && !destination.isOnboarded()) {
            if (!sender.isOnboarded(target.channel(), destination.getAddress())) {
                logRepository.save(log(batch, report, target, DeliveryStatus.SKIPPED, null,
                        null, null, "NOT_ONBOARDED", now));
                return result(target, DeliveryStatus.SKIPPED, null, null, now,
                        "NOT_ONBOARDED", "수신자가 봇에게 /start를 보내지 않았습니다.");
            }
            destination.markOnboarded();
        }

        List<String> externalIds = new ArrayList<>();
        for (int index = 0; index < rendered.chunks().size(); index++) {
            try {
                String externalId = sender.send(target.channel(), destination.getAddress(),
                        rendered.subject(), rendered.chunks().get(index));
                externalIds.add(externalId);
                now = LocalDateTime.now(ApiTimeZone.ZONE);
                logRepository.save(log(batch, report, target, DeliveryStatus.SENT, externalId,
                        index + 1, rendered.chunks().size(), null, now));
            } catch (NotificationTransportException exception) {
                now = LocalDateTime.now(ApiTimeZone.ZONE);
                logRepository.save(log(batch, report, target, DeliveryStatus.FAILED, null,
                        index + 1, rendered.chunks().size(), exception.getMessage(), now));
                return result(target, DeliveryStatus.FAILED,
                        externalIds.isEmpty() ? null : String.join(",", externalIds),
                        rendered.chunks().size(), now, null, exception.getMessage());
            }
        }
        return result(target, DeliveryStatus.SENT,
                externalIds.isEmpty() ? null : String.join(",", externalIds),
                rendered.chunks().size(), now, null, null);
    }

    private DeliveryLog log(DeliveryBatch batch,
                            NewsReport report,
                            Target target,
                            DeliveryStatus status,
                            String externalMessageId,
                            Integer chunkSeq,
                            Integer chunkCount,
                            String error,
                            LocalDateTime sentAt) {
        return DeliveryLog.builder()
                .batch(batch).report(report).recipient(target.recipient())
                .recipientName(target.recipient().getName())
                .channel(target.channel()).channelType(target.channel().getChannelType())
                .address(target.destination().getAddress()).status(status)
                .externalMessageId(externalMessageId).chunkSeq(chunkSeq).chunkCount(chunkCount)
                .errorMessage(error).sentAt(sentAt).build();
    }

    private NotificationResDTO.SendResult result(Target target,
                                                  DeliveryStatus status,
                                                  String externalId,
                                                  Integer chunkCount,
                                                  LocalDateTime sentAt,
                                                  String reason,
                                                  String message) {
        return NotificationResDTO.SendResult.builder()
                .recipientId(target.recipient().getId()).recipientName(target.recipient().getName())
                .channelType(target.channel().getChannelType().name())
                .address(target.destination().getAddress()).status(status.name())
                .externalMessageId(externalId).chunkCount(chunkCount)
                .sentAt(sentAt.atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                .reason(reason).message(message).build();
    }

    private List<Target> resolveTargets(List<NotificationGroup> groups, List<NotificationChannel> channels) {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (NotificationGroup group : groups) {
            for (NotificationRecipient recipient : group.getMembers()) {
                if (!recipient.isActive()) {
                    continue;
                }
                Map<Long, RecipientDestination> destinations = recipient.getDestinations().stream()
                        .filter(RecipientDestination::isUse)
                        .filter(destination -> StringUtils.hasText(destination.getAddress()))
                        .collect(java.util.stream.Collectors.toMap(
                                destination -> destination.getChannel().getId(), value -> value));
                for (NotificationChannel channel : channels) {
                    RecipientDestination destination = destinations.get(channel.getId());
                    if (destination != null) {
                        targets.putIfAbsent(recipient.getId() + ":" + channel.getId(),
                                new Target(recipient, channel, destination));
                    }
                }
            }
        }
        return List.copyOf(targets.values());
    }

    private List<NotificationChannel> resolveChannels(List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return channelRepository.findAllByActiveOrderByIdAsc(true);
        }
        return channelIds.stream().filter(Objects::nonNull).distinct()
                .map(id -> managementService.findChannel(id, true)).toList();
    }

    private NewsReport findReport(Long reportId) {
        return reportRepository.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
    }

    private NotificationResDTO.SendBatch summarize(DeliveryBatch batch, List<NotificationResDTO.SendResult> results) {
        return NotificationResDTO.SendBatch.builder()
                .deliveryBatchId(batch.getId())
                .reportId(batch.getReport().getId())
                .requestedAt(batch.getRequestedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                .targetCount(results.size())
                .sentCount((int) results.stream().filter(result -> "SENT".equals(result.getStatus())).count())
                .failedCount((int) results.stream().filter(result -> "FAILED".equals(result.getStatus())).count())
                .skippedCount((int) results.stream().filter(result -> "SKIPPED".equals(result.getStatus())).count())
                .results(results)
                .build();
    }

    private NotificationResDTO.SendBatch summarizeLogs(DeliveryBatch batch, List<DeliveryLog> logs) {
        Map<String, List<DeliveryLog>> grouped = logs.stream().collect(java.util.stream.Collectors.groupingBy(
                log -> log.getRecipient().getId() + ":" + log.getChannelType() + ":" + log.getAddress(),
                LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<NotificationResDTO.SendResult> results = grouped.values().stream().map(items -> {
            DeliveryLog first = items.getFirst();
            DeliveryStatus status = items.stream().anyMatch(item -> item.getStatus() == DeliveryStatus.FAILED)
                    ? DeliveryStatus.FAILED
                    : items.stream().anyMatch(item -> item.getStatus() == DeliveryStatus.SENT)
                    ? DeliveryStatus.SENT : DeliveryStatus.SKIPPED;
            String ids = items.stream().map(DeliveryLog::getExternalMessageId).filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.joining(","));
            String error = items.stream().map(DeliveryLog::getErrorMessage).filter(Objects::nonNull).findFirst().orElse(null);
            return NotificationResDTO.SendResult.builder()
                    .recipientId(first.getRecipient().getId()).recipientName(first.getRecipientName())
                    .channelType(first.getChannelType().name()).address(first.getAddress()).status(status.name())
                    .externalMessageId(ids.isBlank() ? null : ids).chunkCount(first.getChunkCount())
                    .sentAt(first.getSentAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                    .reason(status == DeliveryStatus.SKIPPED ? error : null).message(error).build();
        }).toList();
        return summarize(batch, results);
    }

    private String normalizeIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new NotificationException(NotificationErrorCode.DELIVERY_NO_TARGET,
                    "idempotencyKey는 100자 이하여야 합니다.");
        }
        return normalized;
    }

    private record Target(NotificationRecipient recipient,
                          NotificationChannel channel,
                          RecipientDestination destination) {
    }
}
