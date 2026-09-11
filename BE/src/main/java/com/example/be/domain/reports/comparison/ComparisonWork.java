package com.example.be.domain.reports.comparison;

import java.time.LocalDate;
import java.util.List;

/** Persisted before any paid call. Both inputs and verified relation links remain frozen. */
public record ComparisonWork(long reportId, LocalDate reportDate, long baseReportId, LocalDate baseReportDate,
                             ReportComparisonSnapshot previous, ReportComparisonSnapshot current,
                             List<IdentityLink> links) {
    public ComparisonWork { links = List.copyOf(links); }
    public record IdentityLink(long previousIssueId, long currentIssueId, String relation) { }
}
