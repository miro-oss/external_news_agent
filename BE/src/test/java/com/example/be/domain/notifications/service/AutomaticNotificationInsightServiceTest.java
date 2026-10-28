package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.notifications.entity.NotificationRecipient;
import com.example.be.domain.notifications.entity.RecipientInsightAudience;
import com.example.be.domain.notifications.repository.NotificationRecipientRepository;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomaticNotificationInsightServiceTest {
    private final NotificationRecipientRepository recipients = mock(NotificationRecipientRepository.class);
    private final ReportNotificationInsightService insights = mock(ReportNotificationInsightService.class);
    private final AutomaticNotificationInsightService service = new AutomaticNotificationInsightService(recipients, insights);

    @Test
    void neutralDefaultNeverQueriesOrGeneratesAnalysis() {
        when(recipients.findById(2L)).thenReturn(Optional.of(recipient(RecipientInsightAudience.NONE)));
        assertThat(service.resolve(17L, 2L)).isEqualTo(AutomaticNotificationInsightService.Selection.neutral());
        verifyNoInteractions(insights);
    }

    @Test
    void currentRecipientChoiceIsReadForEachDeliveryWithoutSharingAnotherRecipientsChoice() {
        var selected = recipient(RecipientInsightAudience.EQUIPMENT_MAKER);
        when(recipients.findById(2L)).thenReturn(Optional.of(selected));
        when(recipients.findById(3L)).thenReturn(Optional.of(recipient(RecipientInsightAudience.NONE)));
        var insight = NotificationInsightTestSupport.insight();
        when(insights.find(17L, "EQUIPMENT_MAKER")).thenReturn(insight);

        var resolved = service.resolve(17L, 2L);
        assertThat(resolved.audience()).isEqualTo(Audience.EQUIPMENT_MAKER);
        assertThat(resolved.insight()).isSameAs(insight);
        assertThat(resolved.unavailable()).isFalse();
        assertThat(service.resolve(17L, 3L)).isEqualTo(AutomaticNotificationInsightService.Selection.neutral());
        selected.updateAutoInsightAudience(RecipientInsightAudience.NONE);
        assertThat(service.resolve(17L, 2L)).isEqualTo(AutomaticNotificationInsightService.Selection.neutral());
        verify(insights).find(17L, "EQUIPMENT_MAKER");
        verifyNoMoreInteractions(insights);
    }

    @Test
    void absentFailedOrPreparingStoredResultAllowsNeutralDeliveryWithTheSelectedAudienceNotice() {
        when(recipients.findById(2L)).thenReturn(Optional.of(recipient(RecipientInsightAudience.EQUIPMENT_MAKER)));
        for (var code : List.of(GeneralErrorCode.NOT_FOUND, GeneralErrorCode.CONFLICT)) {
            doThrow(new GeneralException(code)).when(insights).find(17L, "EQUIPMENT_MAKER");
            var selection = service.resolve(17L, 2L);
            assertThat(selection.audience()).isEqualTo(Audience.EQUIPMENT_MAKER);
            assertThat(selection.insight()).isNull();
            assertThat(selection.unavailable()).isTrue();
        }
        verify(insights, times(2)).find(17L, "EQUIPMENT_MAKER");
        verifyNoMoreInteractions(insights);
    }

    @Test
    void infrastructureAndDeletedReportFailuresAreNotMisrepresentedAsMissingAnalysis() {
        when(recipients.findById(2L)).thenReturn(Optional.of(recipient(RecipientInsightAudience.IT_INFRA)));
        for (var failure : List.of(new GeneralException(GeneralErrorCode.INTERNAL_SERVER_ERROR),
                new IllegalStateException("synthetic storage outage"), new ReportException(ReportErrorCode.REPORT_NOT_FOUND))) {
            doThrow(failure).when(insights).find(17L, "IT_INFRA");
            assertThatThrownBy(() -> service.resolve(17L, 2L)).isSameAs(failure);
        }
    }

    @Test
    void wrongAudienceIsNeverSubstituted() {
        when(recipients.findById(2L)).thenReturn(Optional.of(recipient(RecipientInsightAudience.IT_INFRA)));
        when(insights.find(17L, "IT_INFRA")).thenReturn(NotificationInsightTestSupport.insight());
        assertThatThrownBy(() -> service.resolve(17L, 2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void absentInactiveOrDeletedRecipientNeverLoadsInsights() {
        for (var recipient : List.of(NotificationRecipient.builder().id(2L).active(false).build(),
                NotificationRecipient.builder().id(2L).active(true).deletedAt(LocalDateTime.now()).build())) {
            when(recipients.findById(2L)).thenReturn(Optional.of(recipient));
            assertThatThrownBy(() -> service.resolve(17L, 2L)).hasMessage("수신자를 찾을 수 없습니다.");
        }
        when(recipients.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(17L, 2L)).hasMessage("수신자를 찾을 수 없습니다.");
        verifyNoInteractions(insights);
    }

    private static NotificationRecipient recipient(RecipientInsightAudience audience) {
        return NotificationRecipient.builder().id(2L).active(true).autoInsightAudience(audience).build();
    }
}
