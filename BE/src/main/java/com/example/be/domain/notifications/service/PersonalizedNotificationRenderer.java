package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.feedback.service.FeedbackDeliveryService;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;

/** Reads precomputed personal decisions and creates a recipient-bound feedback capability. */
@Service
@RequiredArgsConstructor
public class PersonalizedNotificationRenderer {
    private final NewsReportRepository reports;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevance;
    private final FeedbackDeliveryService feedback;
    private final NotificationRenderer renderer;
    private final ReportEventFeedbackProjection feedbackProjection;

    @Value("${news.notifications.public-base-url:}")
    private String publicBaseUrl = "";

    @Transactional
    public RenderedNotification render(Long reportId, NotificationChannel channel, Long recipientId) {
        NewsReport report = reports.findById(reportId)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        return render(report, channel, recipientId);
    }

    @Transactional
    public RenderedNotification render(NewsReport report, NotificationChannel channel, Long recipientId) {
        ReportFindings.Visible original = ReportFindings.loadVisible(report, findings, relevance);
        // Review keys belong to the complete public view, before any recipient-specific exclusion.
        var reviewed = feedbackProjection.project(report, original, feedbackProjection.reviews(report.getId()));
        var prepared = feedback.prepare(report, recipientId, reviewed);
        var delivered = reviewed.suppress(report, prepared.suppressedFindingIds());
        return renderer.render(report, channel, delivered, feedbackUrl(prepared.token()));
    }

    String feedbackUrl(String token) {
        if (token == null || publicBaseUrl == null || publicBaseUrl.isBlank()) return null;
        try {
            URI base = URI.create(publicBaseUrl);
            if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
                return null;
            return publicBaseUrl.replaceAll("/+$", "") + "/#/feedback?token="
                    + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) { return null; }
    }
}
