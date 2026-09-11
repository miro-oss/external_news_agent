package com.example.be.domain.reports.comparison;

import java.util.List;

public record ComparisonAssessment(String candidateId, String type, String summary, List<String> previousClaimIds, List<String> currentClaimIds) {
}
