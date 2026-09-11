package com.example.be.domain.reports.comparison;

import java.util.List;

public record ComparisonClaimInput(String id, String text, List<String> evidence) {
}
