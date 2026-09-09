package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.channel.*;
import com.example.be.domain.notifications.entity.*;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.util.Optional;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ReportDeliveryWorkerTest {
    private final ReportDeliveryOutboxStore store=mock(ReportDeliveryOutboxStore.class);
    private final NotificationChannelRepository channels=mock(NotificationChannelRepository.class);
    private final NotificationSenderRegistry senders=mock(NotificationSenderRegistry.class);
    private final NotificationSender sender=mock(NotificationSender.class);
    private final NotificationSender.DeliverySession session=mock(NotificationSender.DeliverySession.class);
    private final ReportDeliveryWorker worker=new ReportDeliveryWorker(store,channels,senders);
    private final ReportDeliveryOutboxStore.Work work=new ReportDeliveryOutboxStore.Work(1L,2L,3L,4L,"batch","수신자","test@invalid.test","제목","요약",1);

    private void ready() {
        var channel=NotificationChannel.builder().id(4L).channelType(ChannelType.EMAIL).active(true).build();
        when(channels.findById(4L)).thenReturn(Optional.of(channel));
        when(store.destinationStillActive(work)).thenReturn(true);
        when(senders.get(ChannelType.EMAIL)).thenReturn(sender);
        when(sender.isConfigured(channel)).thenReturn(true);
        when(sender.openSession(channel)).thenReturn(session);
    }
    @Test void successfulDeliveryPersistsExternalIdOnce() {
        ready(); when(session.send(any(),any(),any())).thenReturn("message-id");
        worker.deliver(work);
        verify(store).finish(work,"EMAIL","SENT","message-id",null,false);
        verify(session).close();
    }
    @Test void changedDestinationDoesNotSendToOldAddress() {
        ready();when(store.destinationStillActive(work)).thenReturn(false);
        worker.deliver(work);
        verifyNoInteractions(session);
        verify(store).finish(eq(work),eq("EMAIL"),eq("SKIPPED"),isNull(),anyString(),eq(false));
    }
    @Test void unknownSendResultDoesNotAutomaticallyRetry() {
        ready();when(session.send(any(),any(),any())).thenThrow(new NotificationTransportException("응답 유실"));
        worker.deliver(work);
        verify(store).finish(eq(work),eq("EMAIL"),eq("UNKNOWN"),isNull(),anyString(),eq(false));
    }
    @Test void explicitlyRejectedRecipientCanBeRetried() {
        ready();when(session.send(any(),any(),any())).thenThrow(new NotificationTransportException("일시 거절",true));
        worker.deliver(work);
        verify(store).finish(work,"EMAIL","FAILED",null,"일시 거절",true);
    }
    @Test void authenticationFailureIsLoggedWithoutRecipientOrTransportDetails(CapturedOutput output) {
        ready();
        String message="메일 서버 인증에 실패했습니다. 관리자에게 메일 연결 설정 확인을 요청해 주세요.";
        when(sender.openSession(any())).thenThrow(new NotificationTransportException(message,true));
        worker.deliver(work);
        verify(store).finish(work,"EMAIL","FAILED",null,message,true);
        assertTrue(output.getOut().contains("reportId=2 deliveryId=1 batchId=batch"));
        assertTrue(output.getOut().contains("channelType=EMAIL status=FAILED"));
        assertTrue(output.getOut().contains(message));
        assertFalse(output.getAll().contains(work.address()));
        assertFalse(output.getAll().contains(work.name()));
        verifyNoInteractions(session);
    }
    @Test void unexpectedTransportFailureLogsSafeFallbackAndPreservesUnknownStatus(CapturedOutput output) {
        ready();
        when(session.send(any(),any(),any())).thenThrow(new IllegalStateException("synthetic-provider-secret"));
        worker.deliver(work);
        verify(store).finish(eq(work),eq("EMAIL"),eq("UNKNOWN"),isNull(),anyString(),eq(false));
        assertTrue(output.getOut().contains("status=UNKNOWN"));
        assertFalse(output.getAll().contains("synthetic-provider-secret"));
        assertFalse(output.getAll().contains(work.address()));
    }
    @Test void persistenceFailureAfterSendDoesNotTurnSuccessIntoResend() {
        ready();when(session.send(any(),any(),any())).thenReturn("message-id");
        doThrow(new IllegalStateException("database unavailable")).when(store).finish(work,"EMAIL","SENT","message-id",null,false);
        assertThrows(IllegalStateException.class,()->worker.deliver(work));
        verify(store,times(1)).finish(any(),anyString(),anyString(),any(),any(),anyBoolean());
    }
    @Test void nextRecipientIsClaimedOnlyAfterCurrentDeliveryIsRecorded() {
        ready();
        when(store.claim()).thenReturn(List.of(work)).thenReturn(List.of());
        when(session.send(any(),any(),any())).thenReturn("message-id");
        worker.deliverPending();
        var order=inOrder(store,session);
        order.verify(store).claim();
        order.verify(session).send(work.address(),work.subject(),work.body());
        order.verify(store).finish(work,"EMAIL","SENT","message-id",null,false);
        order.verify(store).claim();
        verify(store,times(2)).claim();
    }
    @Test void failedPersistenceDoesNotClaimAnotherRecipient() {
        ready();
        when(store.claim()).thenReturn(List.of(work));
        when(session.send(any(),any(),any())).thenReturn("message-id");
        doThrow(new IllegalStateException("database unavailable")).when(store).finish(work,"EMAIL","SENT","message-id",null,false);
        assertThrows(IllegalStateException.class,worker::deliverPending);
        verify(store,times(1)).claim();
    }
}
