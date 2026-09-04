package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.dto.res.NotificationResDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryLog;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.repository.DeliveryLogRepository;
import com.example.be.domain.notifications.repository.NotificationSpecifications;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryLogQueryService {

    private final DeliveryLogRepository logRepository;
    private final EntityManager entityManager;

    public NotificationResDTO.DeliveryLogs getLogs(Long reportId,
                                                    Long runId,
                                                    String batchId,
                                                    String channelType,
                                                    String status,
                                                    Long recipientId,
                                                    String from,
                                                    String to,
                                                    int page,
                                                    int size) {
        validatePage(page, size);
        LocalDateTime parsedFrom = parseDateTime(from);
        LocalDateTime parsedTo = parseDateTime(to);
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            throw badRequest("from은 to보다 이전이어야 합니다.");
        }
        ChannelType parsedChannel = parseChannel(channelType);
        DeliveryStatus parsedStatus = parseStatus(status);
        Specification<DeliveryLog> specification = NotificationSpecifications.deliveryLogs(
                reportId, runId, batchId, parsedChannel, parsedStatus, recipientId, parsedFrom, parsedTo);
        var result = logRepository.findAll(specification,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("sentAt"), Sort.Order.desc("id"))));
        Map<DeliveryStatus, Long> statusCounts = countByStatus(specification);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) result.getTotalElements() / size);
        return NotificationResDTO.DeliveryLogs.builder()
                .content(result.getContent().stream().map(this::toLog).toList())
                .page(page).size(size).totalElements(result.getTotalElements()).totalPages(totalPages)
                .hasNext(page + 1 < totalPages)
                .summary(NotificationResDTO.DeliverySummary.builder()
                        .sentCount(statusCounts.getOrDefault(DeliveryStatus.SENT, 0L))
                        .failedCount(statusCounts.getOrDefault(DeliveryStatus.FAILED, 0L))
                        .skippedCount(statusCounts.getOrDefault(DeliveryStatus.SKIPPED, 0L))
                        .build())
                .build();
    }

    private Map<DeliveryStatus, Long> countByStatus(Specification<DeliveryLog> specification) {
        var criteriaBuilder = entityManager.getCriteriaBuilder();
        var query = criteriaBuilder.createTupleQuery();
        var root = query.from(DeliveryLog.class);
        var predicate = specification.toPredicate(root, query, criteriaBuilder);
        query.multiselect(root.get("status").alias("status"), criteriaBuilder.count(root).alias("count"))
                .where(predicate)
                .groupBy(root.get("status"));

        Map<DeliveryStatus, Long> counts = new EnumMap<>(DeliveryStatus.class);
        for (Tuple tuple : entityManager.createQuery(query).getResultList()) {
            counts.put(tuple.get("status", DeliveryStatus.class), tuple.get("count", Long.class));
        }
        return counts;
    }

    private NotificationResDTO.DeliveryLog toLog(DeliveryLog log) {
        return NotificationResDTO.DeliveryLog.builder()
                .id(log.getId()).deliveryBatchId(log.getBatch().getId())
                .reportId(log.getReport().getId()).runId(log.getReport().getRunId())
                .recipientId(log.getRecipient().getId()).recipientName(log.getRecipientName())
                .channelType(log.getChannelType().name()).address(log.getAddress())
                .status(log.getStatus().name()).externalMessageId(log.getExternalMessageId())
                .chunkSeq(log.getChunkSeq()).chunkCount(log.getChunkCount())
                .errorMessage(log.getErrorMessage())
                .sentAt(log.getSentAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime())
                .build();
    }

    private ChannelType parseChannel(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return ChannelType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw badRequest("지원하지 않는 channelType 값입니다.");
        }
    }

    private DeliveryStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw badRequest("지원하지 않는 status 값입니다.");
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ApiTimeZone.ZONE).toLocalDateTime();
        } catch (DateTimeException exception) {
            try {
                return LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeException ignored) {
                throw badRequest("from과 to는 ISO-8601 형식이어야 합니다.");
            }
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw badRequest("page는 0 이상이어야 합니다.");
        }
        if (size < PageResponse.MIN_SIZE || size > PageResponse.MAX_SIZE) {
            throw badRequest("size는 1 이상 100 이하여야 합니다.");
        }
    }

    private GeneralException badRequest(String message) {
        return new GeneralException(GeneralErrorCode.BAD_REQUEST, message);
    }
}
