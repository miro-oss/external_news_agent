package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.insight.ReportInsightService;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportNotificationInsightServiceTest {
    private final ReportInsightService insights = mock(ReportInsightService.class);
    private final ReportNotificationInsightService service = new ReportNotificationInsightService(insights);

    @Test void omittedAudienceNeverLooksUpOrGeneratesInsights() {
        assertNull(service.find(17L, null));
        verifyNoInteractions(insights);
    }

    @Test void readsOnlyTheCurrentSavedResultThroughTheExistingGetContract() {
        var insight = NotificationInsightTestSupport.insight();
        when(insights.get(17L, "EQUIPMENT_MAKER")).thenReturn(new ReportInsightDTO.Result(
                true, 17L, "current-hash", "current-prompt", "current-rubric", 1, List.of(insight)));
        assertSame(insight, service.find(17L, "EQUIPMENT_MAKER"));
        verify(insights).get(17L, "EQUIPMENT_MAKER");
        verifyNoMoreInteractions(insights);
    }

    @Test void preservesMissingPreparingAndInvalidAudienceErrorsWithoutGenerating() {
        for (var failure : List.of(new GeneralException(GeneralErrorCode.NOT_FOUND),
                new GeneralException(GeneralErrorCode.CONFLICT), new AudienceException())) {
            doThrow(failure).when(insights).get(17L, "selected");
            assertSame(failure, assertThrows(RuntimeException.class, () -> service.find(17L, "selected")));
        }
        verify(insights, times(3)).get(17L, "selected");
        verifyNoMoreInteractions(insights);
    }
}
