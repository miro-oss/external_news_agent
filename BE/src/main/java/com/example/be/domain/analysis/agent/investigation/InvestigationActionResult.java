package com.example.be.domain.analysis.agent.investigation;

public record InvestigationActionResult(
        int addedArticleCount,
        int addedEvidenceCount,
        String summary
) {

    public static InvestigationActionResult conclude(String summary) {
        return new InvestigationActionResult(0, 0, summary);
    }
}
