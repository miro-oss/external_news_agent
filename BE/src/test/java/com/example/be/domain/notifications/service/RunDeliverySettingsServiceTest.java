package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.entity.*;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunDeliverySettingsServiceTest {
    private final CollectionRunRepository runs = mock(CollectionRunRepository.class);
    private final NewsReportRepository reports = mock(NewsReportRepository.class);
    private final RunDeliverySnapshotStore snapshots = mock(RunDeliverySnapshotStore.class);
    private final NotificationManagementService management = mock(NotificationManagementService.class);
    private final NotificationDeliveryPlanService plans = mock(NotificationDeliveryPlanService.class);
    private final ReportNotificationAutomationService automation = mock(ReportNotificationAutomationService.class);
    private final CollectionRunDeliveryService delivery = new CollectionRunDeliveryService(management, plans, automation, snapshots);
    private final RunDeliverySettingsService service = new RunDeliverySettingsService(runs, reports, snapshots, delivery, automation);

    @BeforeEach
    void pendingRun() {
        when(runs.findByIdForUpdate(42L)).thenReturn(Optional.of(CollectionRun.builder().id(42L).status(RunStatus.RUNNING).build()));
    }

    @Test
    void inheritedPoliciesRemainSeparateInsteadOfCombiningTargetsAndReportKinds() {
        var run = CollectionRun.builder().id(42L).status(RunStatus.PENDING).build();
        run.addItem(CollectionRunItem.builder().topic(Topic.builder().id(1L).name("실행 보고서 주제").build()).build());
        run.addItem(CollectionRunItem.builder().topic(Topic.builder().id(2L).name("일일 보고서 주제").build()).build());
        when(runs.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(automation.policy(1L)).thenReturn(new ReportNotificationAutomationService.Policy(true, true, false, List.of(), List.of(7L), List.of(1L)));
        when(automation.policy(2L)).thenReturn(new ReportNotificationAutomationService.Policy(true, false, true, List.of(), List.of(8L), List.of(2L)));

        var result = service.get(42L);

        assertThat(result.source()).isEqualTo("TOPIC");
        assertThat(result.enabled()).isFalse();
        assertThat(result.recipientIds()).isEmpty();
        assertThat(result.topicPolicies()).containsExactly(
                new RunDeliverySettingsService.TopicPolicy(1L, "실행 보고서 주제", true, true, false, List.of(), List.of(7L), List.of(1L)),
                new RunDeliverySettingsService.TopicPolicy(2L, "일일 보고서 주제", true, false, true, List.of(), List.of(8L), List.of(2L)));
        verifyNoInteractions(plans, management);
    }

    @Test
    void pendingReportRemainsEditableAndSaveDoesNotChangeTopicPolicies() {
        when(reports.findByRunId(42L)).thenReturn(Optional.of(NewsReport.builder().id(17L).reportStatus(ReportStatus.PENDING).build()));
        var result = service.save(42L, request(false));
        assertThat(result.editable()).isTrue();
        assertThat(result.reportId()).isEqualTo(17L);
        assertThat(result.reportReady()).isFalse();
        assertThat(result.source()).isEqualTo("RUN");
        verify(snapshots).replace(eq(42L), any());
        verifyNoInteractions(automation, plans, management);
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"SUCCESS", "PARTIAL", "FAILED"})
    void terminalRunsRejectChangesEvenWithoutAReport(RunStatus status) {
        when(runs.findByIdForUpdate(42L)).thenReturn(Optional.of(CollectionRun.builder().id(42L).status(status).build()));
        assertClosed();
        assertThat(service.get(42L).reportReady()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ReportStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void completedReportRejectsChangesWhileRunStillRunning(ReportStatus status) {
        when(reports.findByRunId(42L)).thenReturn(Optional.of(NewsReport.builder().id(17L).reportStatus(status).build()));
        assertClosed();
        var result = service.get(42L);
        assertThat(result.reportReady()).isTrue();
        assertThat(result.editable()).isFalse();
    }

    @Test
    void unchangedLegacySelectionsPreserveExactPairsAndAddressesThroughOffAndOn() {
        var targets = List.of(new RunDeliverySnapshotStore.Target(7L, "첫 대상", 1L, "old-chat"),
                new RunDeliverySnapshotStore.Target(8L, "둘째 대상", 2L, "old@example.invalid"));
        var legacy = new RunDeliverySnapshotStore.Snapshot("TOPIC", true, true, false, targets);
        when(snapshots.find(42L)).thenReturn(Optional.of(legacy));
        var request = request(false);
        request.setRecipientIds(List.of(8L, 7L)); request.setChannelIds(List.of(2L, 1L));
        service.save(42L, request);
        var capture = org.mockito.ArgumentCaptor.forClass(RunDeliverySnapshotStore.Snapshot.class);
        verify(snapshots).replace(eq(42L), capture.capture());
        assertThat(capture.getValue().targets()).isEqualTo(targets);
        when(snapshots.find(42L)).thenReturn(Optional.of(capture.getValue()));
        request.setEnabled(true); request.setRun(false); request.setDaily(true);
        for (long id : List.of(7L, 8L)) when(management.findRecipient(id)).thenReturn(NotificationRecipient.builder().id(id).active(true).build());
        for (long id : List.of(1L, 2L)) when(management.findChannel(id, true)).thenReturn(NotificationChannel.builder().id(id).active(true).build());
        service.save(42L, request);
        verify(snapshots, times(2)).replace(eq(42L), capture.capture());
        assertThat(capture.getValue().targets()).isEqualTo(targets);
        assertThat(capture.getValue().daily()).isTrue();
        assertThat(capture.getValue().run()).isFalse();
        verifyNoInteractions(plans, automation);
    }

    @Test
    void changedSelectorsCaptureNewDestinationAndOriginalSelectionIds() {
        when(snapshots.find(42L)).thenReturn(Optional.of(new RunDeliverySnapshotStore.Snapshot("ONCE", true, true, false,
                List.of(new RunDeliverySnapshotStore.Target(9L, "이전 대상", 2L, "old@example.invalid")))));
        var channel = NotificationChannel.builder().id(2L).channelType(ChannelType.EMAIL).active(true).build();
        when(management.findChannel(2L, true)).thenReturn(channel);
        when(management.findRecipient(7L)).thenReturn(NotificationRecipient.builder().id(7L).active(true).build());
        when(plans.resolveTargets(List.of(), List.of(channel), List.of(7L))).thenReturn(List.of(
                new NotificationDeliveryPlanService.PreparedTarget(7L, "새 대상", channel, 4L, "new@example.invalid", true)));
        var request = request(true); request.setRecipientIds(List.of(7L)); request.setChannelIds(List.of(2L));
        var result = service.save(42L, request);
        assertThat(result.recipientIds()).containsExactly(7L);
        verify(snapshots).replace(42L, new RunDeliverySnapshotStore.Snapshot("ONCE", true, true, false,
                List.of(new RunDeliverySnapshotStore.Target(7L, "새 대상", 2L, "new@example.invalid")), List.of(), List.of(7L), List.of(2L)));
        verifyNoInteractions(automation);
    }

    @Test
    void validatesRequiredBooleansAndDisabledSelectionIds() {
        assertThatThrownBy(() -> service.save(42L, null)).hasMessage("자동 전달 설정이 필요합니다.");
        var request = request(false); request.setEnabled(null);
        assertThatThrownBy(() -> service.save(42L, request)).hasMessage("자동 전달 사용 여부를 선택해 주세요.");
        request.setEnabled(false); request.setDaily(null);
        assertThatThrownBy(() -> service.save(42L, request)).hasMessage("전달할 보고서 종류를 선택해 주세요.");
        request.setDaily(false); request.setRecipientIds(List.of(-1L));
        assertThatThrownBy(() -> service.save(42L, request)).hasMessage("선택 값이 올바르지 않습니다.");
        verify(snapshots, never()).replace(any(), any());
    }

    @Test
    void missingRunHasTheSpecifiedCodeAndMessage() {
        assertThatThrownBy(() -> service.get(999L)).isInstanceOf(GeneralException.class)
                .hasMessage("수집 실행 이력을 찾을 수 없습니다.")
                .satisfies(error -> assertThat(((GeneralException) error).getCode().getCode()).isEqualTo("RUN404"));
    }

    @Test
    void legacySnapshotWithMoreThanOneHundredExpandedRecipientsCanStillBeRead() {
        var targets = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(id -> new RunDeliverySnapshotStore.Target(id, "대상", 2L, id + "@example.invalid")).toList();
        when(snapshots.find(42L)).thenReturn(Optional.of(new RunDeliverySnapshotStore.Snapshot("ONCE", true, true, false, targets)));
        assertThat(service.get(42L).recipientIds()).hasSize(101);
    }

    private void assertClosed() {
        assertThatThrownBy(() -> service.save(42L, request(false))).hasMessage(RunDeliverySettingsService.CLOSED_MESSAGE)
                .satisfies(error -> assertThat(((GeneralException) error).getCode().getCode()).isEqualTo("COMMON409"));
        verify(snapshots, never()).replace(any(), any());
    }
    private NotificationReqDTO.RunDeliverySettings request(boolean enabled) {
        var request = new NotificationReqDTO.RunDeliverySettings();
        request.setEnabled(enabled); request.setRun(true); request.setDaily(false);
        return request;
    }
}
