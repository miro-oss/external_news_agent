package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;

import java.util.LinkedHashMap;
import java.util.List;

/** Explicit RUN or topic weekly company snapshots only; live topics are never consulted. */
public final class ReportDartEligibility {
    private ReportDartEligibility() { }

    public static List<Company> companies(NewsReport report) {
        if (report.getReportStatus() == ReportStatus.PENDING || report.getDeletedAt() != null) return List.of();
        if (report.getReportScope() == ReportScope.WEEKLY && report.getTopicId() != null) {
            Company company = report.getTopicDartCompany();
            return valid(company) ? List.of(company) : List.of();
        }
        if (report.getReportScope() != ReportScope.RUN || report.getCollectionContexts() == null) return List.of();
        var companies = new LinkedHashMap<String, Company>();
        for (var context : report.getCollectionContexts()) {
            for (var topic : context.topics()) {
                Company company = topic.dartCompany();
                if (valid(company)) {
                    companies.putIfAbsent(company.corpCode(), company);
                }
            }
        }
        return List.copyOf(companies.values());
    }

    private static boolean valid(Company company) {
        return company != null && company.corpCode() != null && company.corpCode().matches("[0-9]{8}");
    }
}
