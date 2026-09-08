package com.example.be.domain.notifications.service;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CollectionRunDeliveryServiceTest {
    private final NotificationManagementService management = mock(NotificationManagementService.class);
    private final NotificationDeliveryPlanService plans = mock(NotificationDeliveryPlanService.class);
    private final ReportNotificationAutomationService automation = mock(ReportNotificationAutomationService.class);
    private final RunDeliverySnapshotStore snapshots = mock(RunDeliverySnapshotStore.class);
    private final CollectionRunDeliveryService service = new CollectionRunDeliveryService(management, plans, automation, snapshots);

    @Test
    void absentSelectionKeepsLegacyPolicyWithoutCreatingAnOverride() {
        assertThat(service.prepare(null)).isNull();
        service.save(42L, List.of(1L), null);
        verifyNoInteractions(management, plans, automation, snapshots);
    }

    @Test
    void onceCapturesTheResolvedRecipientsAndAddressesWithoutChangingTopicPolicies() {
        var request = enabled("ONCE");
        var email = channel(ChannelType.EMAIL);
        stubEligibleTarget(email, true);
        var prepared = service.prepare(request);
        request.setRecipientIds(List.of(99L));
        request.setChannelIds(List.of(99L));
        service.save(42L, List.of(1L, 2L), prepared);

        assertThat(prepared.snapshot().targets()).containsExactly(
                new RunDeliverySnapshotStore.Target(7L, "선택 수신자", 2L, "selected@example.invalid"));
        assertThat(prepared.policy().recipientIds()).containsExactly(7L);
        assertThat(prepared.snapshot().run()).isTrue();
        assertThat(prepared.snapshot().daily()).isFalse();
        verify(snapshots).save(42L, prepared.snapshot());
        verifyNoInteractions(automation);
    }

    @Test
    void repeatedGroupAndIndividualMatchesMakeOnlyOneSnapshotTarget() {
        var request = enabled("ONCE");
        var email = channel(ChannelType.EMAIL);
        stubEligibleTarget(email, true);
        var target = new NotificationDeliveryPlanService.PreparedTarget(7L, "선택 수신자", email, 9L, "selected@example.invalid", true);
        when(plans.resolveTargets(List.of(), List.of(email), List.of(7L))).thenReturn(List.of(target, target));

        assertThat(service.prepare(request).snapshot().targets()).hasSize(1);
    }

    @Test
    void persistentModeUpdatesOnlyTheRunsSelectedTopicsAndCapturesTheSameSelection() {
        var request = enabled("TOPIC");
        request.setRun(false);
        request.setDaily(true);
        stubEligibleTarget(channel(ChannelType.EMAIL), true);
        var prepared = service.prepare(request);
        service.save(42L, List.of(3L, 1L, 3L), prepared);

        var order = inOrder(automation, snapshots);
        order.verify(automation).savePolicy(1L, prepared.policy());
        order.verify(automation).savePolicy(3L, prepared.policy());
        order.verify(snapshots).save(42L, prepared.snapshot());
        assertThat(prepared.policy().run()).isFalse();
        assertThat(prepared.policy().daily()).isTrue();
        verifyNoMoreInteractions(automation);
    }

    @Test
    void explicitOffPersistsAnEmptyOverrideEvenWhenOldSelectionsAreNoLongerAvailable() {
        var request = enabled("ONCE");
        request.setEnabled(false);
        var prepared = service.prepare(request);
        service.save(42L, List.of(1L), prepared);
        assertThat(prepared.snapshot().enabled()).isFalse();
        assertThat(prepared.snapshot().targets()).isEmpty();
        verify(snapshots).save(42L, prepared.snapshot());
        verifyNoInteractions(management, plans, automation);
    }

    @Test
    void persistentOffDisablesTheSelectedTopicPoliciesAsWell() {
        var request = enabled("TOPIC");
        request.setEnabled(false);
        var prepared = service.prepare(request);
        service.save(42L, List.of(1L), prepared);
        verify(automation).savePolicy(1L, prepared.policy());
        verify(snapshots).save(42L, prepared.snapshot());
        assertThat(prepared.policy().enabled()).isFalse();
    }

    @Test
    void rejectsMissingOrInvalidModeEnabledAndIds() {
        var request = enabled("UNKNOWN");
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        request.setMode("ONCE"); request.setEnabled(null);
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        request.setEnabled(false); request.setRecipientIds(List.of(-1L));
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        verifyNoInteractions(automation, snapshots);
    }

    @Test
    void rejectsEnabledSelectionWithoutScopeRecipientsOrChannels() {
        var request = enabled("ONCE");
        request.setRun(false); request.setDaily(false);
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        request.setRun(true); request.setRecipientIds(List.of());
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        request.setRecipientIds(List.of(7L)); request.setChannelIds(List.of());
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        verifyNoInteractions(management, plans, automation, snapshots);
    }

    @Test
    void rejectsInactiveRecipientsAndUnconnectedTelegramInsteadOfAcceptingAnEmptyDelivery() {
        var request = enabled("ONCE");
        var telegram = channel(ChannelType.TELEGRAM);
        stubEligibleTarget(telegram, false);
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        when(management.findRecipient(7L)).thenReturn(NotificationRecipient.builder().id(7L).active(false).build());
        assertThatThrownBy(() -> service.prepare(request)).isInstanceOf(GeneralException.class);
        verifyNoInteractions(automation, snapshots);
    }

    private void stubEligibleTarget(NotificationChannel channel, boolean onboarded) {
        when(management.findChannel(2L, true)).thenReturn(channel);
        when(management.findRecipient(7L)).thenReturn(NotificationRecipient.builder().id(7L).active(true).build());
        when(plans.resolveTargets(List.of(), List.of(channel), List.of(7L))).thenReturn(List.of(
                new NotificationDeliveryPlanService.PreparedTarget(7L, "선택 수신자", channel, 9L, "selected@example.invalid", onboarded)));
    }

    private NotificationChannel channel(ChannelType type) {
        return NotificationChannel.builder().id(2L).channelType(type).active(true).build();
    }

    private CollectionRunReqDTO.Delivery enabled(String mode) {
        var request = new CollectionRunReqDTO.Delivery();
        request.setMode(mode); request.setEnabled(true);
        request.setRecipientIds(List.of(7L)); request.setChannelIds(List.of(2L));
        return request;
    }
}
