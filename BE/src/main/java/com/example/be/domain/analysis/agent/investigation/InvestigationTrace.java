package com.example.be.domain.analysis.agent.investigation;

public record InvestigationTrace(
        String status,
        int stepCount,
        int addedArticleCount,
        int addedEvidenceCount,
        String reason,
        String rejectionReason
) {
}
