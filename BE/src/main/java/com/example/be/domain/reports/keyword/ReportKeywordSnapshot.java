package com.example.be.domain.reports.keyword;

import java.time.OffsetDateTime;
import java.util.List;

/** Frozen eligible issue candidates before report ranking; never reconstructed from live memberships. */
public record ReportKeywordSnapshot(String ruleVersion, OffsetDateTime capturedAt, String periodLabel,
                                    String unavailableReason, List<Definition> definitions,
                                    List<Candidate> candidates) {
    public static final String RULE_VERSION = "report-keywords.v1";
    public static final String RECOVERED_SELECTED_RULE_VERSION = "report-keywords.recovered-selected.v1";
    public static final String SELECTED_RULE_VERSION = "report-keywords.selected.v1";

    public static boolean selectedBasis(String ruleVersion) {
        return SELECTED_RULE_VERSION.equals(ruleVersion) || RECOVERED_SELECTED_RULE_VERSION.equals(ruleVersion);
    }

    public ReportKeywordSnapshot {
        definitions = List.copyOf(definitions);
        candidates = List.copyOf(candidates);
    }

    public record Definition(String key, String label) { }

    public record Candidate(Long findingId, Long articleId, Long runId, Long topicId, Long issueId,
                            List<String> matchedKeywordKeys) {
        public Candidate { matchedKeywordKeys = List.copyOf(matchedKeywordKeys); }
    }
}
