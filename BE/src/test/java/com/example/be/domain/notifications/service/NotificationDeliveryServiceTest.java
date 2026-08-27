package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryServiceTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 8, 27, 10, 0);

    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final NotificationManagementService managementService = mock(NotificationManagementService.class);
    private final NotificationRenderer renderer = mock(NotificationRenderer.class);
    private final NotificationDeliveryPlanService planService = mock(NotificationDeliveryPlanService.class);
    private final NotificationDeliveryPersistenceService persistenceService =
            mock(NotificationDeliveryPersistenceService.class);
    private final NotificationSenderRegistry senderRegistry = mock(NotificationSenderRegistry.class);
    private final NotificationDeliveryService service = new NotificationDeliveryService(
            reportRepository, managementService, renderer, planService, persistenceService, senderRegistry);

    @Test
    void telegramRecipientWithoutStartIsSkippedAndLogged() {
        NotificationChannel channel = channel(1L, ChannelType.TELEGRAM);
        NotificationDeliveryPlanService.PreparedTarget target = target(channel, false);
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        stubNewDelivery(channel, target);
        when(senderRegistry.get(ChannelType.TELEGRAM)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);
        when(sender.isOnboarded(channel, "987654321")).thenReturn(false);

        var response = service.send(17L, request(null));

        assertEquals(1, response.getTargetCount());
        assertEquals(1, response.getSkippedCount());
        assertEquals("NOT_ONBOARDED", response.getResults().getFirst().getReason());
        verify(persistenceService).record(argThat(record -> record.status() == DeliveryStatus.SKIPPED));
        verify(session, never()).send(any(), any(), any());
        verify(persistenceService).complete("batch-id");
    }

    @Test
    void repeatedIdempotencyKeyReturnsCompletedBatchWithoutSending() {
        when(persistenceService.findByIdempotencyKey("same-key"))
                .thenReturn(Optional.of(snapshot(17L, REQUESTED_AT)));

        var response = service.send(17L, request("same-key"));

        assertEquals("batch-id", response.getDeliveryBatchId());
        assertEquals(1, response.getSentCount());
        verify(planService, never()).prepare(any(), any());
        verify(persistenceService, never()).reserve(any(), any());
    }

    @Test
    void sameIdempotencyKeyCannotBeReusedForAnotherReport() {
        when(persistenceService.findByIdempotencyKey("same-key"))
                .thenReturn(Optional.of(snapshot(18L, REQUESTED_AT)));

        GeneralException exception = assertThrows(GeneralException.class,
                () -> service.send(17L, request("same-key")));

        assertEquals("COMMON409", exception.getCode().getCode());
        verify(planService, never()).prepare(any(), any());
    }

    @Test
    void concurrentReservationReplaysTheWinningCompletedBatch() {
        NotificationChannel channel = channel(1L, ChannelType.EMAIL);
        NotificationDeliveryPlanService.PreparedTarget target = target(channel, true);
        when(persistenceService.findByIdempotencyKey("same-key"))
                .thenReturn(Optional.empty(), Optional.of(snapshot(17L, REQUESTED_AT)));
        when(planService.prepare(any(), any())).thenReturn(plan(channel, target));
        when(persistenceService.reserve(17L, "same-key"))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        var response = service.send(17L, request("same-key"));

        assertEquals("batch-id", response.getDeliveryBatchId());
        verify(senderRegistry, never()).get(any());
    }

    @Test
    void unexpectedSenderFailureIsPersistedBeforeAllFailedResponse() {
        NotificationChannel channel = channel(1L, ChannelType.EMAIL);
        NotificationDeliveryPlanService.PreparedTarget target = target(channel, true);
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        stubNewDelivery(channel, target);
        when(senderRegistry.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);
        when(session.send(any(), any(), any())).thenThrow(new RuntimeException("adapter crashed"));

        NotificationException exception = assertThrows(NotificationException.class,
                () -> service.send(17L, request(null)));

        assertEquals("DELIVERY502", exception.getCode().getCode());
        verify(persistenceService).record(argThat(record -> record.status() == DeliveryStatus.FAILED
                && "알림 전송 중 예상하지 못한 오류가 발생했습니다.".equals(record.errorMessage())));
        verify(persistenceService).complete("batch-id");
    }

    @Test
    void recipientsOnTheSameChannelReuseOneDeliverySession() {
        NotificationChannel channel = channel(1L, ChannelType.EMAIL);
        var first = target(channel, true);
        var second = new NotificationDeliveryPlanService.PreparedTarget(
                5L, "이영희", channel, 6L, "second@example.com", true);
        NotificationSender sender = mock(NotificationSender.class);
        NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
        when(planService.prepare(any(), any())).thenReturn(new NotificationDeliveryPlanService.PreparedDelivery(
                17L, List.of(first, second),
                Map.of(channel.getId(), new RenderedNotification("제목", "HTML", List.of("메시지")))));
        when(persistenceService.reserve(17L, null)).thenReturn(
                new NotificationDeliveryPersistenceService.BatchInfo("batch-id", 17L, REQUESTED_AT));
        when(senderRegistry.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);
        when(session.send(any(), any(), any())).thenReturn("first-id", "second-id");

        var response = service.send(17L, request(null));

        assertEquals(2, response.getSentCount());
        verify(sender).openSession(channel);
        verify(session, times(2)).send(any(), any(), any());
        verify(session).close();
    }

    private void stubNewDelivery(NotificationChannel channel,
                                 NotificationDeliveryPlanService.PreparedTarget target) {
        when(planService.prepare(any(), any())).thenReturn(plan(channel, target));
        when(persistenceService.reserve(17L, null)).thenReturn(
                new NotificationDeliveryPersistenceService.BatchInfo("batch-id", 17L, REQUESTED_AT));
    }

    private NotificationDeliveryPlanService.PreparedDelivery plan(
            NotificationChannel channel,
            NotificationDeliveryPlanService.PreparedTarget target) {
        return new NotificationDeliveryPlanService.PreparedDelivery(17L, List.of(target),
                Map.of(channel.getId(), new RenderedNotification("제목", "HTML", List.of("메시지"))));
    }

    private NotificationDeliveryPersistenceService.BatchSnapshot snapshot(Long reportId,
                                                                            LocalDateTime completedAt) {
        return new NotificationDeliveryPersistenceService.BatchSnapshot(
                "batch-id", reportId, REQUESTED_AT, completedAt,
                List.of(new NotificationDeliveryPersistenceService.LogSnapshot(
                        3L, "김철수", ChannelType.EMAIL, "user@example.com", DeliveryStatus.SENT,
                        "message-id", 1, null, REQUESTED_AT)));
    }

    private NotificationDeliveryPlanService.PreparedTarget target(NotificationChannel channel,
                                                                   boolean onboarded) {
        return new NotificationDeliveryPlanService.PreparedTarget(
                3L, "김철수", channel, 4L,
                channel.getChannelType() == ChannelType.EMAIL ? "user@example.com" : "987654321", onboarded);
    }

    private NotificationChannel channel(Long id, ChannelType type) {
        return NotificationChannel.builder().id(id).channelType(type).name(type.name())
                .maxLength(3500).active(true).build();
    }

    private NotificationReqDTO.Send request(String idempotencyKey) {
        NotificationReqDTO.Send request = new NotificationReqDTO.Send();
        request.setGroupIds(List.of(2L));
        request.setChannelIds(List.of(1L));
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }
}
