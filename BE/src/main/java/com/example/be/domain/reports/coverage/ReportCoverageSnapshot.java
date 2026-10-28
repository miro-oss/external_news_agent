package com.example.be.domain.reports.coverage;

import java.time.OffsetDateTime;
import java.util.List;

/** Immutable report-input evidence. Live issue memberships must never reconstruct this snapshot. */
public record ReportCoverageSnapshot(String ruleVersion, OffsetDateTime capturedAt, String periodLabel,
                                     List<Observation> observations, List<Binding> bindings) {
    public static final String RULE_VERSION = "report-coverage.v1";

    public ReportCoverageSnapshot {
        observations = List.copyOf(observations);
        bindings = List.copyOf(bindings);
    }

    public record Observation(Long observationId, Long runId, Long topicId, Long issueId, Long articleId,
                              String publisherKey, String publisher, String title, String canonicalUrl,
                              OffsetDateTime publishedAt, Long contentGroupId) { }

    public record Binding(Long findingId, Long issueId, Long topicId) { }
}
