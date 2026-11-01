package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.NotificationSender;
import com.example.be.domain.notifications.channel.NotificationSenderRegistry;
import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.repository.NotificationGroupRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises target resolution, preparation, dispatch and replay together, without an external transport. */
class NotificationDeliveryFlowTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final NotificationChannelRepository channels = mock(NotificationChannelRepository.class);
    private final NotificationGroupRepository groups = mock(NotificationGroupRepository.class);
    private final NotificationManagementService management = mock(NotificationManagementService.class);
    private final NotificationRenderer publicRenderer = mock(NotificationRenderer.class);
    private final PersonalizedNotificationRenderer personalRenderer = mock(PersonalizedNotificationRenderer.class);
    private final ReportNotificationInsightService insights = mock(ReportNotificationInsightService.class);
    private final NotificationDeliveryPersistenceService persistence = mock(NotificationDeliveryPersistenceService.class);
    private final NotificationSenderRegistry senders = mock(NotificationSenderRegistry.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final NotificationSender.DeliverySession session = mock(NotificationSender.DeliverySession.class);
    private final NotificationDeliveryPlanService plans = new NotificationDeliveryPlanService(
            reports, channels, groups, management, publicRenderer, new NotificationProperties(), personalRenderer, insights);
    private final NotificationDeliveryService service = new NotificationDeliveryService(
            reports, management, publicRenderer, plans, persistence, senders, insights);
    private final NewsReport report = NewsReport.builder().id(17L).build();
    private final NotificationChannel channel = NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL)
            .name("이메일").active(true).maxLength(Integer.MAX_VALUE).build();
    private final List<NotificationDeliveryPersistenceService.LogRecord> records = new ArrayList<>();
    private boolean reserved;
    private LocalDateTime completedAt;

    @BeforeEach
    void prepareRecipientsAndBatchStore() {
        when(reports.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(channels.findAllByActiveOrderByIdAsc(true)).thenReturn(List.of(channel));
        when(publicRenderer.render(report, channel)).thenReturn(new RenderedNotification("공용 제목", null, List.of("공용 미리보기")));
        var first = recipient(3L);
        var second = recipient(4L);
        var group = NotificationGroup.builder().id(9L).active(true).name("보고서 수신 그룹")
                .members(new ArrayList<>(List.of(first, second))).build();
        when(management.findGroup(9L, true)).thenReturn(group);
        when(personalRenderer.render(report, channel, 3L)).thenReturn(prepared(3));
        when(personalRenderer.render(report, channel, 4L)).thenReturn(prepared(4));
        when(senders.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);
        when(session.send(any(), any(), any(), any())).thenReturn("accepted-message");

        when(persistence.findByIdempotencyKey("delivery-key")).thenAnswer(ignored -> reserved
                ? Optional.of(snapshot()) : Optional.empty());
        when(persistence.reserve(17L, "delivery-key")).thenAnswer(ignored -> {
            reserved = true;
            return new NotificationDeliveryPersistenceService.BatchInfo("batch", 17L, NOW);
        });
        doAnswer(call -> { records.add(call.getArgument(0)); return null; }).when(persistence).record(any());
        when(persistence.complete("batch")).thenAnswer(ignored -> {
            completedAt = NOW.plusSeconds(1);
            return new NotificationDeliveryPersistenceService.BatchInfo("batch", 17L, NOW);
        });
    }

    @Test
    void oneRejectedRecipientDoesNotBlockTheNextPdfAndReplayDoesNotReprepareEither() {
        when(session.send(eq("reader3@example.invalid"), any(), any(), any()))
                .thenThrow(new NotificationTransportException("메일 주소가 거절되었습니다.", true));

        var first = service.send(17L, request());
        assertEquals(2, first.getTargetCount(), "Group and explicit recipient selections must deduplicate");
        assertEquals(1, first.getFailedCount());
        assertEquals(1, first.getSentCount());
        assertEquals(List.of(DeliveryStatus.FAILED, DeliveryStatus.SENT), records.stream().map(r -> r.status()).toList());
        assertNotNull(completedAt);
        verify(session).send("reader3@example.invalid", prepared(3).subject(), prepared(3).chunks().getFirst(), prepared(3).attachments());
        verify(session).send("reader4@example.invalid", prepared(4).subject(), prepared(4).chunks().getFirst(), prepared(4).attachments());
        verify(session, never()).send(any(), any(), any());
        verify(sender).openSession(channel);
        verify(session).close();

        // Even changed perspective input cannot turn a status retry into a new send.
        var retry = request();
        retry.setInsightAudience("EQUIPMENT_MAKER");
        var replay = service.send(17L, retry);
        assertEquals(first.getDeliveryBatchId(), replay.getDeliveryBatchId());
        assertEquals(1, replay.getFailedCount());
        assertEquals(1, replay.getSentCount());
        verify(persistence).reserve(17L, "delivery-key");
        verify(personalRenderer).render(report, channel, 3L);
        verify(personalRenderer).render(report, channel, 4L);
        verifyNoInteractions(insights);
        verify(session, times(2)).send(any(), any(), any(), any());
    }

    @Test
    void pdfFailureForLaterRecipientAbortsBeforeAnyBatchOrSmtpSession() {
        var failure = new IllegalStateException("synthetic PDF failure");
        when(personalRenderer.render(report, channel, 4L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.send(17L, request())));

        verify(personalRenderer).render(report, channel, 3L);
        verify(personalRenderer).render(report, channel, 4L);
        verify(persistence, never()).reserve(any(), any());
        verify(persistence, never()).record(any());
        verifyNoInteractions(senders, sender, session);
        assertFalse(reserved);
    }

    @Test
    void pendingPersonalPolicyPreparesEveryRecipientButStartsNoDelivery() {
        when(personalRenderer.render(report, channel, 3L)).thenThrow(new GeneralException(GeneralErrorCode.CONFLICT));

        var error = assertThrows(GeneralException.class, () -> service.send(17L, request()));

        assertEquals(GeneralErrorCode.CONFLICT, error.getCode());
        verify(personalRenderer).render(report, channel, 4L);
        verify(persistence, never()).reserve(any(), any());
        verifyNoInteractions(senders, sender, session);
    }

    @Test
    void allFailedBatchReturnsItsStoredResultForSameKeyWithoutRetryingTransport() {
        when(session.send(any(), any(), any(), any()))
                .thenThrow(new NotificationTransportException("메일 전송 결과를 확인하지 못했습니다."));

        var error = assertThrows(NotificationException.class, () -> service.send(17L, request()));
        assertEquals("DELIVERY502", error.getCode().getCode());
        assertNotNull(completedAt);
        var replay = service.send(17L, request());

        assertEquals(2, replay.getFailedCount());
        assertEquals(0, replay.getSentCount());
        verify(sender).openSession(channel);
        verify(session, times(2)).send(any(), any(), any(), any());
        verify(personalRenderer).render(report, channel, 3L);
        verify(personalRenderer).render(report, channel, 4L);
    }

    private NotificationRecipient recipient(long id) {
        var recipient = NotificationRecipient.builder().id(id).name("수신자 " + id).active(true)
                .destinations(new ArrayList<>()).groups(new ArrayList<>()).build();
        recipient.getDestinations().add(RecipientDestination.builder().id(20L + id).recipient(recipient)
                .channel(channel).address("reader" + id + "@example.invalid").use(true).onboarded(true).build());
        when(management.findRecipient(id)).thenReturn(recipient);
        return recipient;
    }

    private final RenderedNotification firstPrepared = new RenderedNotification("수신자 3 전용", null,
            List.of("수신자 3에게 허용된 이슈 · PDF 2쪽"),
            List.of(new NotificationAttachment("news-report-17.pdf", "application/pdf", new byte[]{3})));
    private final RenderedNotification secondPrepared = new RenderedNotification("수신자 4 전용", null,
            List.of("수신자 4에게 허용된 이슈 · PDF 3쪽"),
            List.of(new NotificationAttachment("news-report-17.pdf", "application/pdf", new byte[]{4})));

    private RenderedNotification prepared(int recipient) {
        return recipient == 3 ? firstPrepared : secondPrepared;
    }

    private NotificationReqDTO.Send request() {
        var request = new NotificationReqDTO.Send();
        request.setIdempotencyKey("delivery-key");
        request.setGroupIds(List.of(9L));
        request.setRecipientIds(List.of(3L, 4L));
        return request;
    }

    private NotificationDeliveryPersistenceService.BatchSnapshot snapshot() {
        return new NotificationDeliveryPersistenceService.BatchSnapshot("batch", 17L, NOW, completedAt,
                records.stream().map(r -> new NotificationDeliveryPersistenceService.LogSnapshot(
                        r.recipientId(), r.recipientName(), r.channelType(), r.address(), r.status(),
                        r.externalMessageId(), r.chunkCount(), r.errorMessage(), r.sentAt())).toList());
    }
}
