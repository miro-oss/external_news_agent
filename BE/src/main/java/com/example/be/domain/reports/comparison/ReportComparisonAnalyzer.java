package com.example.be.domain.reports.comparison;

import java.util.List;

public interface ReportComparisonAnalyzer {
    List<ComparisonAssessment> analyze(long reportId, long baseReportId, List<ComparisonCandidate> candidates);
}
