package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.dart.ReportIssueCompanyProjection;
import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;

import java.util.List;

/** The final delivery view shares the public API company/link exclusion rules. */
final class NotificationCompanyVisibility {
    private NotificationCompanyVisibility() { }

    static List<CompanyAttachment> visible(NewsReport report, ReportEventFeedbackProjection.View view,
                                           List<CompanyAttachment> companies) {
        return ReportIssueCompanyProjection.project(report, view, companies);
    }
}
