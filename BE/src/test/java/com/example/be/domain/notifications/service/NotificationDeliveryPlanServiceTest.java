package com.example.be.domain.notifications.service;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationGroup;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientDestination;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.repository.NotificationGroupRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryPlanServiceTest {

    private final NewsReportRepository reportRepository = mock(NewsReportRepository.class);
    private final NotificationChannelRepository channelRepository = mock(NotificationChannelRepository.class);
    private final NotificationGroupRepository groupRepository = mock(NotificationGroupRepository.class);
    private final NotificationManagementService managementService = mock(NotificationManagementService.class);
    private final NotificationRenderer renderer = mock(NotificationRenderer.class);
    private final NotificationDeliveryPlanService service = new NotificationDeliveryPlanService(
            reportRepository, channelRepository, groupRepository, managementService, renderer);

    @Test
    void watchWithoutSpecificGroupBroadcastsToActiveGroups() {
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
        when(channelRepository.findAllByActiveOrderByIdAsc(true)).thenReturn(List.of(channel));
        when(groupRepository.findAllByActiveOrderByIdAsc(true)).thenReturn(List.of(group));
        when(renderer.renderBreakingAlert("HBM4", "후속 1건", channel)).thenReturn(rendered);

        NotificationDeliveryPlanService.PreparedWatchDelivery plan =
                service.prepareWatchAlert(null, "HBM4", "후속 1건");

        assertEquals(1, plan.targets().size());
        assertEquals("user@example.com", plan.targets().getFirst().address());
        assertEquals(rendered, plan.renderedByChannel().get(2L));
        verify(groupRepository).findAllByActiveOrderByIdAsc(true);
    }
}
