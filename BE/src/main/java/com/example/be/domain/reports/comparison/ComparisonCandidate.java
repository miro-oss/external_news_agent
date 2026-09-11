package com.example.be.domain.reports.comparison;

import java.util.List;

public record ComparisonCandidate(String id, String relation, List<ComparisonClaimInput> previous, List<ComparisonClaimInput> current) {
}
