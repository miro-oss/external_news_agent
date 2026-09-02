package com.example.be.domain.analysis.agent.investigation;

public record IssueInvestigationState(
        Long id,
        Long runId,
        Long issueId,
        String idempotencyKey,
        String status,
        String triggerReason,
        int nextStep,
        Integer inFlightStep,
        int evidenceCountBefore,
        int evidenceCountCurrent,
        int addedArticleCount,
        String firstActionReason,
        String rejectionReason,
        String terminationReason
) {

    public boolean finished() {
        return !"IN_PROGRESS".equals(status);
    }
}
