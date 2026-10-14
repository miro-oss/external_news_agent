package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.DartModels.Company;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;

import java.util.LinkedHashMap;
import java.util.List;

/** Only explicit companies captured for this RUN are eligible; live topics are never consulted. */
final class ReportDartEligibility {
    private ReportDartEligibility() { }

    static List<Company> companies(NewsReport report) {
        if (report.getReportScope() != ReportScope.RUN || report.getReportStatus() == ReportStatus.PENDING
                || report.getDeletedAt() != null || report.getCollectionContexts() == null) return List.of();
        var companies = new LinkedHashMap<String, Company>();
        for (var context : report.getCollectionContexts()) {
            for (var topic : context.topics()) {
                Company company = topic.dartCompany();
                if (company != null && company.corpCode() != null && company.corpCode().matches("[0-9]{8}")) {
                    companies.putIfAbsent(company.corpCode(), company);
                }
            }
        }
        return List.copyOf(companies.values());
    }
}
