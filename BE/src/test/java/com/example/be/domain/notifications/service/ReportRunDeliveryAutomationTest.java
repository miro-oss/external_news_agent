package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportRunDeliveryAutomationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TopicRepository topics = mock(TopicRepository.class);
    private final NotificationManagementService management = mock(NotificationManagementService.class);
    private final NotificationDeliveryPlanService plans = mock(NotificationDeliveryPlanService.class);
    private final NotificationRenderer renderer = mock(NotificationRenderer.class);
    private final RunDeliverySnapshotStore snapshots = mock(RunDeliverySnapshotStore.class);
    private final ReportDeliveryOutboxStore outbox = mock(ReportDeliveryOutboxStore.class);
    private final ReportNotificationAutomationService automation = new ReportNotificationAutomationService(jdbc, topics, management, plans, renderer, snapshots, outbox);
    private final NotificationChannel email = NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL).active(true).build();

    @Test
    void completedRunUsesSavedAddressWithoutReresolvingEditedTopicPoliciesOrGroupMembers() {
        var report = runReport();
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(true, true, false)));
        stubRendering(report);

        automation.enqueueCompletedReport(report);

        verifyQueued(17L);
        verifyNoInteractions(topics, plans);
        verify(jdbc, never()).queryForList(anyString(), eq(Long.class), any());
    }

    @Test
    void explicitOffSuppressesTheRunsLegacyTopicPolicy() {
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(false, true, true)));
        automation.enqueueCompletedReport(runReport());
        verifyNoInteractions(jdbc, topics, management, plans, renderer);
    }

    @Test
    void dailyOnlySelectionDoesNotSendTheRunReport() {
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(true, false, true)));
        automation.enqueueCompletedReport(runReport());
        verifyNoInteractions(jdbc, topics, management, plans, renderer);
    }

    @Test
    void dailyReportDeduplicatesSelectedRecipientsAcrossSourceRuns() {
        var report = dailyReport(List.of(42L, 43L));
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(true, false, true)));
        when(snapshots.find(43L)).thenReturn(Optional.of(snapshot(true, true, true)));
        stubRendering(report);

        automation.enqueueCompletedReport(report);

        verifyQueued(117L);
        verify(renderer, times(1)).render(report, email);
        verifyNoInteractions(topics, plans);
    }

    @Test
    void dailyOffForOneRunDoesNotVetoAnotherRunsExplicitDelivery() {
        var report = dailyReport(List.of(42L, 43L));
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(false, true, false)));
        when(snapshots.find(43L)).thenReturn(Optional.of(snapshot(true, false, true)));
        stubRendering(report);
        automation.enqueueCompletedReport(report);
        verifyQueued(117L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void dailyUsesTheLatestEligibleSnapshotWhenAddressesChangedBetweenRequests(boolean reversed) {
        var report = dailyReport(reversed ? List.of(43L, 44L, 42L) : List.of(42L, 44L, 43L));
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(true, false, true)));
        when(snapshots.find(43L)).thenReturn(Optional.of(new RunDeliverySnapshotStore.Snapshot("ONCE", true, false, true,
                List.of(new RunDeliverySnapshotStore.Target(7L, "수정한 수신자", 2L, "current@example.invalid")))));
        when(snapshots.find(44L)).thenReturn(Optional.of(new RunDeliverySnapshotStore.Snapshot("ONCE", true, false, true,
                List.of(new RunDeliverySnapshotStore.Target(7L, "이후 해제한 주소", 2L, "unlinked@example.invalid")))));
        stubRendering(report);
        when(outbox.destinationStillActive(7L, 2L, "captured@example.invalid")).thenReturn(false);
        when(outbox.destinationStillActive(7L, 2L, "unlinked@example.invalid")).thenReturn(false);

        automation.enqueueCompletedReport(report);

        verify(jdbc).update(startsWith("INSERT INTO report_notification_outbox"), eq(117L), eq(7L), eq(2L), anyString(),
                eq("수정한 수신자"), eq("current@example.invalid"), eq("보고서"), eq("핵심 요약"), any(LocalDateTime.class));
        verify(renderer, times(1)).render(report, email);
    }

    @Test
    void dailyPrefersTheLatestNameForTheSameStillConnectedAddressRegardlessOfSourceListOrder() {
        var report = dailyReport(List.of(42L, 43L));
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(true, false, true)));
        when(snapshots.find(43L)).thenReturn(Optional.of(new RunDeliverySnapshotStore.Snapshot("ONCE", true, false, true,
                List.of(new RunDeliverySnapshotStore.Target(7L, "최근 수신자 이름", 2L, "captured@example.invalid")))));
        stubRendering(report);
        automation.enqueueCompletedReport(report);
        verify(jdbc).update(startsWith("INSERT INTO report_notification_outbox"), eq(117L), eq(7L), eq(2L), anyString(),
                eq("최근 수신자 이름"), eq("captured@example.invalid"), eq("보고서"), eq("핵심 요약"), any(LocalDateTime.class));
    }

    @Test
    void existingOutboxEntryPreventsReenqueuingTheSameReportTarget() {
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(true, true, false)));
        when(management.findChannel(2L, true)).thenReturn(email);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM report_notification_outbox"), eq(Integer.class), eq(17L), eq(7L), eq(2L))).thenReturn(1);

        automation.enqueueCompletedReport(runReport());

        verifyNoInteractions(renderer);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void absentSnapshotKeepsLegacyTopicPolicyForScheduledAndOlderRuns() {
        var report = runReport();
        when(snapshots.find(42L)).thenReturn(Optional.empty());
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(42L))).thenReturn(List.of(1L));
        when(topics.existsById(1L)).thenReturn(true);
        var policy = new ReportNotificationAutomationService.Policy(true, true, false, List.of(), List.of(7L), List.of(2L));
        doReturn(List.of(policy)).when(jdbc).query(startsWith("SELECT * FROM topic_delivery_policies"), any(RowMapper.class), eq(1L));
        when(management.findRecipient(7L)).thenReturn(com.example.be.domain.notifications.entity.NotificationRecipient.builder().id(7L).active(true).build());
        when(plans.resolveTargets(List.of(), List.of(email), List.of(7L))).thenReturn(List.of(
                new NotificationDeliveryPlanService.PreparedTarget(7L, "접수 당시 수신자", email, 9L, "captured@example.invalid", true)));
        stubRendering(report);
        automation.enqueueCompletedReport(report);
        verifyQueued(17L);
    }

    @Test
    void anInvalidSnapshotDoesNotBlockAValidLegacyPolicyFromAnotherSourceRun() {
        var report = dailyReport(List.of(42L, 43L));
        when(snapshots.find(42L)).thenReturn(Optional.of(snapshot(true, false, true)));
        when(snapshots.find(43L)).thenReturn(Optional.empty());
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(43L))).thenReturn(List.of(1L));
        when(topics.existsById(1L)).thenReturn(true);
        var policy = new ReportNotificationAutomationService.Policy(true, true, true, List.of(), List.of(7L), List.of(2L));
        doReturn(List.of(policy)).when(jdbc).query(startsWith("SELECT * FROM topic_delivery_policies"), any(RowMapper.class), eq(1L));
        when(management.findRecipient(7L)).thenReturn(com.example.be.domain.notifications.entity.NotificationRecipient.builder().id(7L).active(true).build());
        when(plans.resolveTargets(List.of(), List.of(email), List.of(7L))).thenReturn(List.of(
                new NotificationDeliveryPlanService.PreparedTarget(7L, "주제 정책 수신자", email, 9L, "policy@example.invalid", true)));
        stubRendering(report);
        when(outbox.destinationStillActive(7L, 2L, "captured@example.invalid")).thenReturn(false);

        automation.enqueueCompletedReport(report);

        verify(jdbc).update(startsWith("INSERT INTO report_notification_outbox"), eq(117L), eq(7L), eq(2L), anyString(),
                eq("주제 정책 수신자"), eq("policy@example.invalid"), eq("보고서"), eq("핵심 요약"), any(LocalDateTime.class));
        verify(renderer, times(1)).render(report, email);
    }

    private RunDeliverySnapshotStore.Snapshot snapshot(boolean enabled, boolean run, boolean daily) {
        return new RunDeliverySnapshotStore.Snapshot("ONCE", enabled, run, daily,
                enabled ? List.of(new RunDeliverySnapshotStore.Target(7L, "접수 당시 수신자", 2L, "captured@example.invalid")) : List.of());
    }

    private NewsReport runReport() { return NewsReport.builder().id(17L).run(CollectionRun.builder().id(42L).build()).build(); }
    private NewsReport dailyReport(List<Long> ids) { return NewsReport.builder().id(117L).reportScope(ReportScope.DAILY).sourceRunIds(ids).build(); }

    private void stubRendering(NewsReport report) {
        when(management.findChannel(2L, true)).thenReturn(email);
        when(renderer.render(report, email)).thenReturn(new RenderedNotification("보고서", "HTML", List.of("핵심 요약")));
        when(outbox.destinationStillActive(anyLong(), anyLong(), anyString())).thenReturn(true);
    }

    private void verifyQueued(Long reportId) {
        verify(jdbc).update(startsWith("INSERT INTO report_notification_outbox"), eq(reportId), eq(7L), eq(2L),
                anyString(), eq("접수 당시 수신자"), eq("captured@example.invalid"), eq("보고서"), eq("핵심 요약"), any(LocalDateTime.class));
    }
}
