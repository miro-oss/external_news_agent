package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

import java.util.List;

public record AgentEvidenceRequest(
        String idempotencyKey,
        AgentPlan plan,
        List<ClaimPayload> claims
) {

    public record ClaimPayload(
            String claimId,
            String claim,
            String claimType,
            String attributedTo,
            List<SentencePayload> sentences
    ) {
    }

    public record SentencePayload(
            Integer id,
            String text
    ) {
    }
}
