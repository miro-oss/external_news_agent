package com.example.be.domain.reports.service;

public record ReportSourceStats(
        int collected,
        int blocked,
        int failed,
        int paywalled,
        int stubExcluded
) {

    public static ReportSourceStats empty() {
        return new ReportSourceStats(0, 0, 0, 0, 0);
    }
}
