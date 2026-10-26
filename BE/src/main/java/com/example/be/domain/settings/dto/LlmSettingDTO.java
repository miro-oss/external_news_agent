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
                            @Schema(description = "한국시간 오늘 시작한 FREE 작업에 기록된 예상 USD 비용 합계. 알려진 실패 비용은 포함하고 미기록 비용·진행 중 요청은 제외하므로 실제 청구액과 다를 수 있습니다.",
                                    example = "0.123456")
                            BigDecimal dailyEstimatedCostUsd,
                            OffsetDateTime resetAt) {
    }

    public record PaidUsage(BigDecimal dailyCreditsUsed,
                            BigDecimal dailyCreditsLimit,
                            BigDecimal dailyCreditsRemaining,
                            BigDecimal analysisCreditsRemaining,
                            BigDecimal insightCreditsUsed,
                            BigDecimal insightCreditsCap,
                            BigDecimal insightCreditsRemaining,
                            BigDecimal reportReserve,
                            BigDecimal monthlyCreditsUsed,
                            BigDecimal monthlyCreditsLimit,
                            BigDecimal monthlyCreditsRemaining,
                            OffsetDateTime dailyResetAt,
                            OffsetDateTime monthlyResetAt) {
    }
}
