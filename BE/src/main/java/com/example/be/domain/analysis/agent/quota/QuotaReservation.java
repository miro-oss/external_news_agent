package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;

import java.math.BigDecimal;

public record QuotaReservation(Long id,
                               Long collectionRunId,
                               String idempotencyKey,
                               AgentTask task,
                               AgentPlan plan,
                               BigDecimal reservedUnits) {
}
