package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.controller.ReportInsightController;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.settings.exception.AudienceException;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.OffsetDateTime;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportInsightController.class)
class ReportInsightControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReportInsightService service;
    @Test void explicitCreateUsesStandardEnvelopeAndRetainsOriginalClaimTypeAndZeroBasedEvidence() throws Exception {
        var insight = new ReportInsightDTO.AudienceInsight(Audience.CHIP_MAKER, "핵심 판단", "high", List.of(), List.of(),
                List.of(new ReportInsightDTO.Fact("50:0", "2027년 생산을 목표로 한다.", "FORECAST", null,
                        50L, 150L, List.of(0), "grounded")), List.of(), List.of(), "openai", "model", OffsetDateTime.parse("2026-09-30T09:00:00+09:00"));
        var result = new ReportInsightDTO.Result(false, 10L, "a".repeat(64), "report-insight.ko.v28", "report-importance.v6", 1, List.of(insight));
        when(service.create(eq(10L), any())).thenReturn(result);
        mvc.perform(post("/api/news/reports/10/insights").contentType(MediaType.APPLICATION_JSON).content("{\"audiences\":[\"CHIP_MAKER\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("성공입니다."))
                .andExpect(jsonPath("$.result.promptVersion").value("report-insight.ko.v28"))
                .andExpect(jsonPath("$.result.rubricVersion").value("report-importance.v6"))
                .andExpect(jsonPath("$.result.insights[0].facts[0].claimType").value("FORECAST"))
                .andExpect(jsonPath("$.result.insights[0].facts[0].evidenceSentenceIds[0]").value(0));
        verify(service).create(10L, new ReportInsightDTO.CreateRequest(List.of("CHIP_MAKER")));
    }
    @Test void getRoutesOnlySpecifiedPerspectiveAndUsesCacheMissingMessage() throws Exception {
        when(service.get(10L, "IT_INFRA")).thenThrow(new GeneralException(GeneralErrorCode.NOT_FOUND, "저장된 리포트 관점 인사이트가 없습니다."));
        mvc.perform(get("/api/news/reports/10/insights").param("audience", "IT_INFRA"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMON404"))
                .andExpect(jsonPath("$.message").value("저장된 리포트 관점 인사이트가 없습니다."));
        verify(service).get(10L, "IT_INFRA");
        verify(service, never()).create(any(), any());
    }
    @Test void automaticAnalysisPendingUsesExistingConflictEnvelopeWithoutTriggeringGeneration() throws Exception {
        when(service.get(10L, "IT_INFRA")).thenThrow(new GeneralException(GeneralErrorCode.CONFLICT,
                "동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요."));
        mvc.perform(get("/api/news/reports/10/insights").param("audience", "IT_INFRA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMON409"))
                .andExpect(jsonPath("$.message").value("동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요."));
        verify(service, never()).create(any(), any());
    }
    @Test void missingReportAndInvalidAudienceHaveSpecifiedDomainErrors() throws Exception {
        when(service.get(10L, "CHIP_MAKER")).thenThrow(new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        when(service.get(10L, "OTHER")).thenThrow(new AudienceException());
        mvc.perform(get("/api/news/reports/10/insights").param("audience", "CHIP_MAKER"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT404"));
        mvc.perform(get("/api/news/reports/10/insights").param("audience", "OTHER"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUDIENCE400"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 관점입니다."));
    }
}
