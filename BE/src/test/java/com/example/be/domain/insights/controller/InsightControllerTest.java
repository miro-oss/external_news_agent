package com.example.be.domain.insights.controller;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.dto.InsightDTO;
import com.example.be.domain.insights.service.InsightService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InsightController.class)
class InsightControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InsightService service;

    @Test
    void createsInsightWithCommonEnvelope() throws Exception {
        when(service.create(any())).thenReturn(result(false));

        mockMvc.perform(post("/api/news/insights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"ISSUE","targetId":88,"audiences":["CHIP_MAKER"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.cached").value(false))
                .andExpect(jsonPath("$.result.insights[0].audience").value("CHIP_MAKER"))
                .andExpect(jsonPath("$.result.insights[0].facts[0].evidenceSentenceIds[0]")
                        .value(0))
                .andExpect(jsonPath("$.result.insights[0].implications[0].falsifiedBy")
                        .value("일정 번복"));
    }

    @Test
    void getsLatestInsightWithCommonEnvelope() throws Exception {
        when(service.get("ISSUE", 88L, "CHIP_MAKER")).thenReturn(result(true));

        mockMvc.perform(get("/api/news/insights")
                        .param("targetType", "ISSUE")
                        .param("targetId", "88")
                        .param("audience", "CHIP_MAKER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.cached").value(true))
                .andExpect(jsonPath("$.result.promptVersion")
                        .value("insight.ko.v2+perspective.ko.v1"));
    }

    private InsightDTO.Result result(boolean cached) {
        return new InsightDTO.Result(
                cached,
                "ISSUE",
                88L,
                "a".repeat(64),
                "insight.ko.v2+perspective.ko.v1",
                List.of(new InsightDTO.AudienceInsight(
                        Audience.CHIP_MAKER,
                        "양산 일정 변화",
                        List.of(new com.example.be.domain.insights.entity.InsightFact(
                                "FACT", "f1", "확인된 사실", 501L, 10L, List.of(0),
                                "grounded", "원문 확인")),
                        List.of(new com.example.be.domain.insights.entity.InsightImplication(
                                "IMPLICATION", "i1", "점검 필요", List.of("f1"),
                                "일정 유지", "일정 번복")),
                        List.of("후속 발표"),
                        new BigDecimal("0.8"),
                        "gemini",
                        "gemini-test",
                        OffsetDateTime.parse("2026-09-02T12:00:00+09:00"))));
    }
}
