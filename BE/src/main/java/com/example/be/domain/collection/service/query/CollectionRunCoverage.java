package com.example.be.domain.collection.service.query;

import java.math.BigDecimal;

public record CollectionRunCoverage(
        int observedArticleCount,
        int issueAssignedArticleCount,
        BigDecimal issueAssignmentRate,
        int issueCount,
        int analysisTargetIssueCount,
        int llmAnalyzedIssueCount,
        BigDecimal llmAnalysisRate,
        int reportReflectedIssueCount,
        int reportExcludedIssueCount,
        BigDecimal reportCoverageRate,
        int issueLimitPerRun
) {
}
