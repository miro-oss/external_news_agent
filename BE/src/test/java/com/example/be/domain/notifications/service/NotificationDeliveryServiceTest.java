package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryBatch;
import com.example.be.domain.notifications.entity.DeliveryLog;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.notifications.repository.DeliveryBatchRepository;
import com.example.be.domain.notifications.repository.DeliveryLogRepository;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryServiceTest {

    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final NotificationChannelRepository channelRepository = mock(NotificationChannelRepository.class);
    private final DeliveryBatchRepository batchRepository = mock(DeliveryBatchRepository.class);
    private final DeliveryLogRepository logRepository = mock(DeliveryLogRepository.class);
    private final NotificationManagementService managementService = mock(NotificationManagementService.class);
    private final NotificationRenderer renderer = mock(NotificationRenderer.class);
    private final NotificationSenderRegistry senderRegistry = mock(NotificationSenderRegistry.class);
    private final NotificationDeliveryService service = new NotificationDeliveryService(reportRepository,
            channelRepository, batchRepository, logRepository, managementService, renderer, senderRegistry);

    @Test
    void telegramRecipientWithoutStartIsSkippedAndLogged() {
        NewsReport report = NewsReport.builder().id(17L).build();
        NotificationChannel channel = NotificationChannel.builder()
                .id(1L).channelType(ChannelType.TELEGRAM).name("텔레그램")
                .maxLength(3500).active(true).build();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .id(3L).name("김철수").active(true).destinations(new ArrayList<>()).groups(new ArrayList<>()).build();
        RecipientDestination destination = RecipientDestination.builder()
                .recipient(recipient).channel(channel).address("987654321").use(true).onboarded(false).build();
        recipient.getDestinations().add(destination);
        NotificationGroup group = NotificationGroup.builder()
                .id(2L).name("기술").active(true).members(new ArrayList<>(List.of(recipient)))
                .createdAt(LocalDateTime.now()).build();
        NotificationSender sender = mock(NotificationSender.class);
        when(reportRepository.findByIdAndReportStatusNot(any(), any())).thenReturn(Optional.of(report));
        when(managementService.findGroup(2L, true)).thenReturn(group);
        when(managementService.findChannel(1L, true)).thenReturn(channel);
        when(renderer.render(report, channel)).thenReturn(new RenderedNotification(null, "HTML", List.of("메시지")));
        when(senderRegistry.get(ChannelType.TELEGRAM)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.isOnboarded(channel, "987654321")).thenReturn(false);
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(logRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        NotificationReqDTO.Send request = new NotificationReqDTO.Send();
        request.setGroupIds(List.of(2L));
        request.setChannelIds(List.of(1L));

        var response = service.send(17L, request);

        assertEquals(1, response.getTargetCount());
        assertEquals(1, response.getSkippedCount());
        assertEquals("NOT_ONBOARDED", response.getResults().getFirst().getReason());
        verify(logRepository).save(any(DeliveryLog.class));
        verify(sender, never()).send(any(), any(), any(), any());
    }

    @Test
    void repeatedIdempotencyKeyReturnsStoredBatchWithoutSending() {
        NewsReport report = NewsReport.builder().id(17L).build();
        DeliveryBatch batch = DeliveryBatch.builder().id("batch-id").report(report)
                .idempotencyKey("same-key").requestedAt(LocalDateTime.of(2026, 8, 27, 10, 0)).build();
        when(reportRepository.findByIdAndReportStatusNot(any(), any())).thenReturn(Optional.of(report));
        when(batchRepository.findByIdempotencyKey("same-key")).thenReturn(Optional.of(batch));
        when(logRepository.findAllByBatchIdOrderByIdAsc("batch-id")).thenReturn(List.of());
        NotificationReqDTO.Send request = new NotificationReqDTO.Send();
        request.setGroupIds(List.of(1L));
        request.setIdempotencyKey("same-key");

        var response = service.send(17L, request);

        assertEquals("batch-id", response.getDeliveryBatchId());
        verify(batchRepository, never()).save(any());
    }
}
