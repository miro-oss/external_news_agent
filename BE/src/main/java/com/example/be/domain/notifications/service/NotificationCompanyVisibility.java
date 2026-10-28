package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.dto.res.ReportDartDTO.CompanyAttachment;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Company navigation uses saved topic links only, never names extracted from news prose. */
final class NotificationCompanyVisibility {
    private NotificationCompanyVisibility() { }

    static List<CompanyAttachment> visible(NewsReport report, ReportEventFeedbackProjection.View view,
                                           List<CompanyAttachment> companies) {
        if (companies == null || companies.isEmpty()) return List.of();
        if (view == null) return report.getCompanySuppressedFindingIds().isEmpty() ? List.copyOf(companies) : List.of();
        if (!view.sourceFiltered() && !view.excludedEvents()
                && view.excludedEvidenceFindingIds().isEmpty()
                && report.getCompanySuppressedFindingIds().isEmpty()) return List.copyOf(companies);
        Set<Long> topics = new HashSet<>();
        Set<Long> retainedIds = view.findings().stream()
                .map(com.example.be.domain.analysis.entity.Finding::getId)
                .filter(id -> !view.excludedEvidenceFindingIds().contains(id)
                        && !report.getCompanySuppressedFindingIds().contains(id)).collect(java.util.stream.Collectors.toSet());
        var coverage = report.getReportCoverageSnapshot();
        if (coverage != null) coverage.bindings().stream().filter(binding -> retainedIds.contains(binding.findingId()))
                .map(com.example.be.domain.reports.coverage.ReportCoverageSnapshot.Binding::topicId)
                .filter(java.util.Objects::nonNull).forEach(topics::add);
        // A legacy article's first topic does not prove which topic observed it in this RUN.
        // Topic WEEKLY's persisted selection already binds its retained findings to report.topicId.
        if (report.getReportScope() == ReportScope.WEEKLY && report.getTopicId() != null && !retainedIds.isEmpty())
            topics.add(report.getTopicId());
        Set<String> allowed = new HashSet<>();
        if (report.getReportScope() == ReportScope.WEEKLY && report.getTopicId() != null
                && topics.contains(report.getTopicId()) && report.getTopicDartCompany() != null)
            allowed.add(report.getTopicDartCompany().corpCode());
        if (report.getReportScope() == ReportScope.RUN && report.getCollectionContexts() != null)
            report.getCollectionContexts().forEach(context -> context.topics().stream()
                    .filter(topic -> topics.contains(topic.topicId()) && topic.dartCompany() != null)
                    .forEach(topic -> allowed.add(topic.dartCompany().corpCode())));
        return companies.stream().filter(company -> allowed.contains(company.company().corpCode())).toList();
    }
}
