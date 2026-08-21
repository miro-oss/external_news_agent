package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.entity.AgentPlan;

public class QuotaExceededException extends RuntimeException {

    private final AgentPlan plan;

    public QuotaExceededException(AgentPlan plan, String message) {
        super(message);
        this.plan = plan;
    }

    public AgentPlan getPlan() {
        return plan;
    }
}
