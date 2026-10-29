package com.example.be.domain.reports.dart;

import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.dart.config.DartProperties;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportFindings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportDartService {
    private final NewsReportRepository reports;
    private final ReportDartRepository repository;
    private final ReportDartPersistence persistence;
    private final DartProperties properties;
    private final FindingRepository findings;
    private final TopicRelevancePolicy relevance;
    private final ReportEventFeedbackProjection feedback;

    /** Pure stored-state read: never enqueues work or calls the DART provider. */
    @Transactional(readOnly = true)
    public ReportDartDTO.Result get(long reportId) {
        var report = reports.findByIdAndReportStatusNot(reportId, ReportStatus.PENDING)
                .orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        var stored = getStored(report);
        if (stored.companies().isEmpty()) return stored;
        var view = feedback.project(report, ReportFindings.loadVisible(report, findings, relevance), feedback.reviews(reportId));
        return new ReportDartDTO.Result(reportId, ReportIssueCompanyProjection.project(report, view, stored.companies()));
    }

    @Transactional(readOnly = true)
    public ReportDartDTO.Result getStored(NewsReport report) {
        Long reportId = report.getId();
        var companies = ReportDartEligibility.companies(report);
        if (companies.isEmpty()) return new ReportDartDTO.Result(reportId, List.of());
        var attachments = repository.find(reportId).stream()
                .collect(Collectors.toMap(row -> row.company().corpCode(), Function.identity()));
        return new ReportDartDTO.Result(reportId, companies.stream().map(company -> {
            var attachment = attachments.get(company.corpCode());
            var links = ReportIssueCompanyProjection.storedLinks(report, company.corpCode());
            if (attachment == null) return new ReportDartDTO.CompanyAttachment(company,
                    properties.isEnabled() ? "PENDING" : "DISABLED",
                    properties.isEnabled() ? ReportDartMessages.PENDING : ReportDartMessages.DISABLED, null, links, List.of());
            String status = "RUNNING".equals(attachment.status()) ? "PENDING" : attachment.status();
            return new ReportDartDTO.CompanyAttachment(company, status, attachment.message(), attachment.data(), links,
                    ReportIssueCompanyProjection.filingRows(attachment.data(), links));
        }).toList());
    }

    @Transactional
    public ReportDartDTO.Result retry(long reportId) {
        persistence.retry(reportId);
        return get(reportId);
    }
}
