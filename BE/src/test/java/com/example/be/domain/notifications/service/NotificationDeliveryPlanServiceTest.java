package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.config.NotificationProperties;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.repository.NotificationGroupRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryPlanServiceTest {

    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final NotificationChannelRepository channelRepository = mock(NotificationChannelRepository.class);
    private final NotificationGroupRepository groupRepository = mock(NotificationGroupRepository.class);
    private final NotificationManagementService managementService = mock(NotificationManagementService.class);
    private final NotificationRenderer renderer = mock(NotificationRenderer.class);
    private final PersonalizedNotificationRenderer personalizedRenderer = mock(PersonalizedNotificationRenderer.class);
    private final NotificationProperties properties = new NotificationProperties();
    private final NotificationDeliveryPlanService service = new NotificationDeliveryPlanService(
            reportRepository, channelRepository, groupRepository, managementService, renderer, properties, personalizedRenderer);

    @Test
    void pendingFirstRecipientStillPreparesEverySelectedRecipientBeforeRejectingThePlan() {
        ManualDelivery delivery = manualDelivery();
        GeneralException firstPending = new GeneralException(GeneralErrorCode.CONFLICT);
        when(personalizedRenderer.render(delivery.report(), delivery.channel(), 3L)).thenThrow(firstPending);
        when(personalizedRenderer.render(delivery.report(), delivery.channel(), 4L))
                .thenThrow(new GeneralException(GeneralErrorCode.CONFLICT));
        when(personalizedRenderer.render(delivery.report(), delivery.channel(), 5L))
                .thenReturn(new RenderedNotification("보고서", null, List.of("준비된 내용")));

        GeneralException failure = assertThrows(GeneralException.class,
                () -> service.prepare(17L, delivery.request()));

        assertSame(firstPending, failure);
        verify(personalizedRenderer).render(delivery.report(), delivery.channel(), 3L);
        verify(personalizedRenderer).render(delivery.report(), delivery.channel(), 4L);
        verify(personalizedRenderer).render(delivery.report(), delivery.channel(), 5L);
    }

    @Test
    void anotherGeneralErrorOverridesPendingAndStopsPreparingFurtherRecipients() {
        ManualDelivery delivery = manualDelivery();
        when(personalizedRenderer.render(delivery.report(), delivery.channel(), 3L))
                .thenThrow(new GeneralException(GeneralErrorCode.CONFLICT));
        GeneralException missing = new GeneralException(GeneralErrorCode.NOT_FOUND);
        when(personalizedRenderer.render(delivery.report(), delivery.channel(), 4L)).thenThrow(missing);

        assertSame(missing, assertThrows(GeneralException.class,
                () -> service.prepare(17L, delivery.request())));

        verify(personalizedRenderer, never()).render(delivery.report(), delivery.channel(), 5L);
    }

    @Test
    void unexpectedPreparationFailureIsNotTreatedAsPersonalizationPending() {
        ManualDelivery delivery = manualDelivery();
        IllegalStateException unavailable = new IllegalStateException("snapshot unavailable");
        when(personalizedRenderer.render(delivery.report(), delivery.channel(), 3L)).thenThrow(unavailable);

        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> service.prepare(17L, delivery.request())));

        verify(personalizedRenderer, never()).render(delivery.report(), delivery.channel(), 4L);
        verify(personalizedRenderer, never()).render(delivery.report(), delivery.channel(), 5L);
    }

    private ManualDelivery manualDelivery() {
        NewsReport report = NewsReport.builder().id(17L).build();
        NotificationChannel channel = NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일").maxLength(Integer.MAX_VALUE).active(true)
                .build();
        when(reportRepository.findByIdAndReportStatusNot(17L, ReportStatus.PENDING)).thenReturn(Optional.of(report));
        when(channelRepository.findAllByActiveOrderByIdAsc(true)).thenReturn(List.of(channel));
        when(renderer.render(report, channel)).thenReturn(new RenderedNotification("공용 보고서", null, List.of("공용 내용")));
        for (long id : List.of(3L, 4L, 5L)) {
            NotificationRecipient recipient = NotificationRecipient.builder()
                    .id(id).name("수신자 " + id).active(true).destinations(new ArrayList<>()).groups(new ArrayList<>())
                    .build();
            recipient.getDestinations().add(RecipientDestination.builder()
                    .id(id + 10).recipient(recipient).channel(channel).address("reader" + id + "@example.invalid")
                    .use(true).onboarded(true).build());
            when(managementService.findRecipient(id)).thenReturn(recipient);
        }
        NotificationReqDTO.Send request = new NotificationReqDTO.Send();
        request.setRecipientIds(List.of(3L, 4L, 5L));
        return new ManualDelivery(report, channel, request);
    }

    private record ManualDelivery(NewsReport report, NotificationChannel channel, NotificationReqDTO.Send request) { }

    @Test
    void watchWithoutSpecificGroupUsesConfiguredBreakingGroupOnly() {
        NotificationChannel channel = NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일").maxLength(Integer.MAX_VALUE).active(true)
                .build();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .id(3L).name("김철수").active(true).destinations(new ArrayList<>()).groups(new ArrayList<>())
                .build();
        RecipientDestination destination = RecipientDestination.builder()
                .id(4L).recipient(recipient).channel(channel).address("user@example.com")
                .use(true).onboarded(true).build();
        recipient.getDestinations().add(destination);
        NotificationGroup group = NotificationGroup.builder()
                .id(5L).name("전체").active(true).createdAt(LocalDateTime.now())
                .members(new ArrayList<>(List.of(recipient))).build();
        RenderedNotification rendered = new RenderedNotification(
                "[속보 후속] HBM4", null, List.of("<p>후속 1건</p>"));
        properties.setBreakingGroupId(5L);
        when(channelRepository.findAllByActiveOrderByIdAsc(true)).thenReturn(List.of(channel));
        when(groupRepository.findByIdAndActive(5L, true)).thenReturn(java.util.Optional.of(group));
        when(renderer.renderBreakingAlert("HBM4", "후속 1건", channel)).thenReturn(rendered);

        NotificationDeliveryPlanService.PreparedWatchDelivery plan =
                service.prepareWatchAlert(null, "HBM4", "후속 1건");

        assertEquals(1, plan.targets().size());
        assertEquals("user@example.com", plan.targets().getFirst().address());
        assertEquals(rendered, plan.renderedByChannel().get(2L));
        verify(groupRepository).findByIdAndActive(5L, true);
        verify(groupRepository, never()).findAllByActiveOrderByIdAsc(true);
    }

    @Test
    void watchWithoutConfiguredGroupHasNoRecipients() {
        NotificationChannel channel = NotificationChannel.builder()
                .id(2L).channelType(ChannelType.EMAIL).name("메일").maxLength(Integer.MAX_VALUE).active(true)
                .build();
        when(channelRepository.findAllByActiveOrderByIdAsc(true)).thenReturn(List.of(channel));

        NotificationDeliveryPlanService.PreparedWatchDelivery plan =
                service.prepareWatchAlert(null, "HBM4", "후속 1건");

        assertEquals(0, plan.targets().size());
        verify(groupRepository, never()).findAllByActiveOrderByIdAsc(true);
    }

    @Test
    void preparesMultipleAlertsWithOneLookupForTheSameGroup() {
        NotificationGroup group = NotificationGroup.builder()
                .id(5L).name("속보 구독").active(true).createdAt(LocalDateTime.now())
                .members(new ArrayList<>()).build();
        properties.setBreakingGroupId(5L);
        when(channelRepository.findAllByActiveOrderByIdAsc(true)).thenReturn(List.of());
        when(groupRepository.findByIdAndActive(5L, true)).thenReturn(java.util.Optional.of(group));

        var plans = service.prepareWatchAlerts(List.of(
                new NotificationDeliveryPlanService.WatchAlertRequest(60L, null, "첫 속보", "후속 1건"),
                new NotificationDeliveryPlanService.WatchAlertRequest(61L, null, "둘째 속보", "후속 2건")));

        assertEquals(java.util.Set.of(60L, 61L), plans.keySet());
        verify(groupRepository, times(1)).findByIdAndActive(5L, true);
    }
}
