package com.example.be.domain.settings.controller;

import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "LLM 설정 및 사용량")
public class LlmSettingController {

    private final LlmPlanService planService;
    private final AgentQuotaService quotaService;

    @GetMapping("/settings/llm-plan")
    @Operation(summary = "LLM 플랜 설정 조회")
    public ApiResponse<LlmSettingDTO.PlanResponse> getPlan() {
        return ApiResponse.of(GeneralSuccessCode.OK, planService.get());
    }

    @PutMapping("/settings/llm-plan")
    @Operation(summary = "LLM 플랜 설정 변경")
    public ApiResponse<LlmSettingDTO.PlanResponse> updatePlan(
            @RequestBody LlmSettingDTO.UpdateRequest request) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, planService.update(request));
    }

    @GetMapping("/usage/llm")
    @Operation(summary = "LLM 일·월 사용량 조회")
    public ApiResponse<LlmSettingDTO.UsageResponse> getUsage() {
        return ApiResponse.of(GeneralSuccessCode.OK, quotaService.usage());
    }
}
