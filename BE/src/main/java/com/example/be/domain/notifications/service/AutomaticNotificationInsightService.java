package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.notifications.entity.RecipientInsightAudience;
import com.example.be.domain.notifications.exception.NotificationException;
import com.example.be.domain.notifications.exception.code.NotificationErrorCode;
import com.example.be.domain.notifications.repository.NotificationRecipientRepository;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reads current settings and saved analysis before entering the delivery rendering transaction. */
@Service
@RequiredArgsConstructor
public class AutomaticNotificationInsightService {
    private final NotificationRecipientRepository recipients;
    private final ReportNotificationInsightService insights;

    public record Selection(Audience audience, ReportInsightDTO.AudienceInsight insight) {
        public Selection {
            if (insight != null && insight.audience() != audience)
                throw new IllegalArgumentException("Selected audience does not match the stored insight.");
        }

        public static Selection neutral() { return new Selection(null, null); }
        public static Selection ready(ReportInsightDTO.AudienceInsight insight) {
            return new Selection(java.util.Objects.requireNonNull(insight).audience(), insight);
        }
        public static Selection unavailable(Audience audience) {
            return new Selection(java.util.Objects.requireNonNull(audience), null);
        }
        public boolean unavailable() { return audience != null && insight == null; }
    }

    public Selection resolve(Long reportId, Long recipientId) {
        var recipient = recipients.findById(recipientId)
                .filter(value -> value.isActive() && value.getDeletedAt() == null)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.RECIPIENT_NOT_FOUND));
        RecipientInsightAudience preference = recipient.getAutoInsightAudience();
        if (preference == RecipientInsightAudience.NONE) return Selection.neutral();
        Audience audience = preference.audience();
        try {
            var insight = insights.find(reportId, audience.name());
            if (insight == null || insight.audience() != audience)
                throw new IllegalStateException("Stored analysis does not match the selected audience.");
            return Selection.ready(insight);
        } catch (GeneralException unavailable) {
            if (unavailable.getCode() == GeneralErrorCode.NOT_FOUND || unavailable.getCode() == GeneralErrorCode.CONFLICT)
                return Selection.unavailable(audience);
            throw unavailable;
        }
    }
}
