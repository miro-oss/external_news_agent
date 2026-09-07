package com.example.be.domain.analysis.agent.service;

import com.example.be.domain.analysis.agent.dto.AgentInsightRequest;

import java.util.List;

/** 인사이트 요청의 계측 메타데이터. 기사·주장 원문과 비용은 보관하지 않는다. */
public record InsightAuditContext(
        int schemaVersion,
        String type,
        String cacheOutcome,
        int requestedAudienceCount,
        int cachedAudienceCount,
        int generationAudienceCount,
        int selectedCurrentFindingCount,
        int selectedHistoryFindingCount,
        int submittedCurrentFindingCount,
        int submittedHistoryFindingCount,
        boolean agentRequestIssued,
        String inputHash,
        String expectedPromptVersion,
        String configuredModel,
        String metadataSource,
        String usageCompleteness
) {

    public static InsightAuditContext capture(String inputHash,
                                               List<AgentInsightRequest.FindingPayload> findings,
                                               int requestedAudienceCount,
                                               int cachedAudienceCount,
                                               boolean agentRequestIssued,
                                               String expectedPromptVersion,
                                               String configuredModel) {
        if (requestedAudienceCount <= 0
                || cachedAudienceCount < 0
                || cachedAudienceCount > requestedAudienceCount) {
            throw new IllegalArgumentException("인사이트 관점 계측 건수가 올바르지 않습니다.");
        }
        int current = 0;
        int history = 0;
        for (AgentInsightRequest.FindingPayload finding : findings) {
            if (finding.role() == AgentInsightRequest.FindingRole.CURRENT) {
                current++;
            } else if (finding.role() == AgentInsightRequest.FindingRole.HISTORY) {
                history++;
            }
        }
        String cacheOutcome = cachedAudienceCount == requestedAudienceCount
                ? "FULL" : cachedAudienceCount == 0 ? "MISS" : "PARTIAL";
        return new InsightAuditContext(
                1, "INSIGHT_OBSERVATION", cacheOutcome,
                requestedAudienceCount, cachedAudienceCount,
                requestedAudienceCount - cachedAudienceCount,
                current, history,
                agentRequestIssued ? current : 0,
                agentRequestIssued ? history : 0,
                agentRequestIssued, inputHash, expectedPromptVersion,
                configuredModel == null || configuredModel.isBlank() ? null : configuredModel,
                "UNAVAILABLE", "UNKNOWN");
    }

    public InsightAuditContext withMetadataSource(String source) {
        return new InsightAuditContext(
                schemaVersion, type, cacheOutcome,
                requestedAudienceCount, cachedAudienceCount, generationAudienceCount,
                selectedCurrentFindingCount, selectedHistoryFindingCount,
                submittedCurrentFindingCount, submittedHistoryFindingCount,
                agentRequestIssued, inputHash, expectedPromptVersion, configuredModel,
                source == null ? "UNAVAILABLE" : source, usageCompleteness);
    }

    public InsightAuditContext withUsageCompleteness(String completeness) {
        String normalized = "COMPLETE".equals(completeness) || "PARTIAL".equals(completeness)
                ? completeness : "UNKNOWN";
        return new InsightAuditContext(
                schemaVersion, type, cacheOutcome,
                requestedAudienceCount, cachedAudienceCount, generationAudienceCount,
                selectedCurrentFindingCount, selectedHistoryFindingCount,
                submittedCurrentFindingCount, submittedHistoryFindingCount,
                agentRequestIssued, inputHash, expectedPromptVersion, configuredModel,
                metadataSource, normalized);
    }
}
