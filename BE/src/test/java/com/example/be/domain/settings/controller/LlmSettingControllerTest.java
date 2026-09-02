package com.example.be.domain.settings.controller;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.settings.dto.LlmSettingDTO;
import com.example.be.domain.settings.entity.PaidExhaustedAction;
import com.example.be.domain.settings.exception.LlmException;
import com.example.be.domain.settings.exception.code.LlmErrorCode;
import com.example.be.domain.settings.service.LlmPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LlmSettingController.class)
class LlmSettingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmPlanService planService;

    @MockitoBean
    private AgentQuotaService quotaService;

    @Test
    void getsAndUpdatesPlanWithCommonEnvelope() throws Exception {
        LlmSettingDTO.PlanResponse response = new LlmSettingDTO.PlanResponse(
                AgentPlan.PAID, true, PaidExhaustedAction.FALLBACK_FREE);
        when(planService.get()).thenReturn(response);
        when(planService.update(any())).thenReturn(response);

        mockMvc.perform(get("/api/settings/llm-plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.plan").value("PAID"))
                .andExpect(jsonPath("$.result.allowRunOverride").value(true))
                .andExpect(jsonPath("$.result.paidExhaustedAction").value("FALLBACK_FREE"));

        mockMvc.perform(put("/api/settings/llm-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plan":"PAID","paidExhaustedAction":"FALLBACK_FREE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.plan").value("PAID"));
    }

    @Test
    void returnsDailyAndMonthlyUsage() throws Exception {
        OffsetDateTime reset = OffsetDateTime.of(
                2026, 8, 22, 0, 0, 0, 0, ZoneOffset.ofHours(9));
        when(quotaService.usage()).thenReturn(new LlmSettingDTO.UsageResponse(
                AgentPlan.PAID,
                new LlmSettingDTO.FreeUsage(
                        new BigDecimal("12"), new BigDecimal("1500"),
                        new BigDecimal("1488"), reset),
                new LlmSettingDTO.PaidUsage(
                        new BigDecimal("71"), new BigDecimal("90"), new BigDecimal("19"),
                        BigDecimal.ZERO,
                        new BigDecimal("7"), new BigDecimal("15"), new BigDecimal("8"),
                        new BigDecimal("20"),
                        new BigDecimal("2140"), new BigDecimal("3000"), new BigDecimal("860"),
                        reset, reset.plusMonths(1))));

        mockMvc.perform(get("/api/usage/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.currentPlan").value("PAID"))
                .andExpect(jsonPath("$.result.paid.dailyCreditsRemaining").value(19))
                .andExpect(jsonPath("$.result.paid.analysisCreditsRemaining").value(0))
                .andExpect(jsonPath("$.result.paid.insightCreditsUsed").value(7))
                .andExpect(jsonPath("$.result.paid.insightCreditsCap").value(15))
                .andExpect(jsonPath("$.result.paid.insightCreditsRemaining").value(8))
                .andExpect(jsonPath("$.result.paid.reportReserve").value(20))
                .andExpect(jsonPath("$.result.paid.monthlyCreditsRemaining").value(860));
    }

    @Test
    void returnsQuotaDetailsWithQuota429() throws Exception {
        when(quotaService.usage()).thenThrow(new LlmException(
                LlmErrorCode.QUOTA_EXHAUSTED,
                Map.of("plan", "PAID", "dailyRemaining", BigDecimal.ZERO)));

        mockMvc.perform(get("/api/usage/llm"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA429"))
                .andExpect(jsonPath("$.result.plan").value("PAID"))
                .andExpect(jsonPath("$.result.dailyRemaining").value(0));
    }
}
