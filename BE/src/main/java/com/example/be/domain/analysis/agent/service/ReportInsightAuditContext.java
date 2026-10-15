package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.entity.Audience;

import java.util.HashSet;
import java.util.List;

/** Source text stays out of audit payloads; the immutable input hash identifies it. */
public record ReportInsightAuditContext(int schemaVersion, String type, String inputHash,
        String promptVersion, String rubricVersion, int inputFindingCount, int requestedAudienceCount,
        int cachedAudienceCount, boolean agentRequestIssued, String metadataSource, String usageCompleteness,
        List<Audience> audiences, AgentClientException.ValidationFailure validationFailure) {
    public ReportInsightAuditContext {
        // Older observations did not record audience identities. Do not infer them from counts.
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
        if (audiences.size() > Audience.values().length || new HashSet<>(audiences).size() != audiences.size()) {
            throw new IllegalArgumentException("Invalid report insight audit audiences");
        }
    }

    public ReportInsightAuditContext(int schemaVersion, String type, String inputHash,
            String promptVersion, String rubricVersion, int inputFindingCount, int requestedAudienceCount,
            int cachedAudienceCount, boolean agentRequestIssued, String metadataSource, String usageCompleteness) {
        this(schemaVersion, type, inputHash, promptVersion, rubricVersion, inputFindingCount,
                requestedAudienceCount, cachedAudienceCount, agentRequestIssued, metadataSource,
                usageCompleteness, List.of(), null);
    }

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
                "COMPLETE".equals(completeness) || "PARTIAL".equals(completeness) ? completeness : "UNKNOWN",
                audiences, validationFailure);
    }

    public ReportInsightAuditContext withAudiences(List<String> values) {
        return new ReportInsightAuditContext(schemaVersion, type, inputHash, promptVersion, rubricVersion,
                inputFindingCount, requestedAudienceCount, cachedAudienceCount, agentRequestIssued,
                metadataSource, usageCompleteness, values.stream().map(Audience::valueOf).toList(), validationFailure);
    }

    public ReportInsightAuditContext withValidationFailure(AgentClientException.ValidationFailure failure) {
        return new ReportInsightAuditContext(schemaVersion, type, inputHash, promptVersion, rubricVersion,
                inputFindingCount, requestedAudienceCount, cachedAudienceCount, agentRequestIssued,
                metadataSource, usageCompleteness, audiences, failure);
    }
}
