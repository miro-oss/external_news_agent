package com.example.be.domain.settings.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class LlmSettingDTO {

    private LlmSettingDTO() {
    }

    @Schema(name = "LlmPlanUpdateRequest")
    public record UpdateRequest(String plan, String paidExhaustedAction) {
    }

    @Schema(name = "LlmPlanResponse")
    public record PlanResponse(AgentPlan plan,
                               boolean allowRunOverride,
                               PaidExhaustedAction paidExhaustedAction) {
    }

    @Schema(name = "LlmUsageResponse")
    public record UsageResponse(AgentPlan currentPlan,
                                FreeUsage free,
                                PaidUsage paid) {
    }

    public record FreeUsage(BigDecimal dailyCallsUsed,
                            BigDecimal dailyCallsLimit,
                            BigDecimal dailyCallsRemaining,
                            OffsetDateTime resetAt) {
    }

    public record PaidUsage(BigDecimal dailyCreditsUsed,
                            BigDecimal dailyCreditsLimit,
                            BigDecimal dailyCreditsRemaining,
                            BigDecimal analysisCreditsRemaining,
                            BigDecimal reportReserve,
                            BigDecimal monthlyCreditsUsed,
                            BigDecimal monthlyCreditsLimit,
                            BigDecimal monthlyCreditsRemaining,
                            OffsetDateTime dailyResetAt,
                            OffsetDateTime monthlyResetAt) {
    }
}
