package com.example.be.domain.analysis.agent.service;

/** Source text stays out of audit payloads; the immutable input hash identifies it. */
public record ReportInsightAuditContext(int schemaVersion, String type, String inputHash,
        String promptVersion, String rubricVersion, int inputFindingCount, int requestedAudienceCount,
        int cachedAudienceCount, boolean agentRequestIssued, String metadataSource, String usageCompleteness) {
    public ReportInsightAuditContext(int schemaVersion, String type, String inputHash,
            String promptVersion, String rubricVersion, int inputFindingCount, int requestedAudienceCount,
            int cachedAudienceCount, boolean agentRequestIssued) {
        this(schemaVersion, type, inputHash, promptVersion, rubricVersion, inputFindingCount,
                requestedAudienceCount, cachedAudienceCount, agentRequestIssued, "UNAVAILABLE", "UNKNOWN");
    }

    public ReportInsightAuditContext withExecution(String source, String completeness) {
        return new ReportInsightAuditContext(schemaVersion, type, inputHash, promptVersion, rubricVersion,
                inputFindingCount, requestedAudienceCount, cachedAudienceCount, agentRequestIssued,
                source == null ? "UNAVAILABLE" : source,
                "COMPLETE".equals(completeness) || "PARTIAL".equals(completeness) ? completeness : "UNKNOWN");
    }
}
