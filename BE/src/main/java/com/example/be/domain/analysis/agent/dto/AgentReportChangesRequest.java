package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.reports.comparison.ComparisonCandidate;
import java.util.List;

public record AgentReportChangesRequest(String idempotencyKey, AgentPlan plan, long reportId,
                                        long baseReportId, List<ComparisonCandidate> candidates) {
    public AgentReportChangesRequest { candidates = List.copyOf(candidates); }
}
