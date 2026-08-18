package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.service.ReportQueryService;
import com.example.be.global.apiPayload.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportQueryService reportQueryService;

    @Test
    void getReportsRespondsWithPagedSummary() throws Exception {
        ReportResDTO.Summary summary = ReportResDTO.Summary.builder()
                .id(17L)
                .runId(42L)
                .title("반도체 뉴스 보고서")
                .generatedAt(OffsetDateTime.parse("2026-08-18T10:03:12+09:00"))
                .modelName("stub-report-v1")
                .findingCount(17)
                .highRiskCount(3)
                .deliveryStatus("NOT_SENT")
                .build();
        when(reportQueryService.getReports("2026-08-01", null, 0, 20))
                .thenReturn(PageResponse.of(List.of(summary), 0, 20, 1));

        mockMvc.perform(get("/api/news/reports").param("from", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].runId").value(42))
                .andExpect(jsonPath("$.result.content[0].highRiskCount").value(3))
                .andExpect(jsonPath("$.result.content[0].deliveryStatus").value("NOT_SENT"));
    }

    @Test
    void latestReturnsSuccessfulNullForInitialState() throws Exception {
        when(reportQueryService.getLatest(true)).thenReturn(null);

        mockMvc.perform(get("/api/news/reports/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("생성된 보고서가 없습니다."))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void detailOmitsFindingsWhenNotIncluded() throws Exception {
        ReportResDTO.Detail detail = ReportResDTO.Detail.builder()
                .id(17L)
                .runId(42L)
                .title("반도체 뉴스 보고서")
                .markdownBody("# 보고서")
                .summaryStats(ReportResDTO.SummaryStats.builder()
                        .findingCount(1)
                        .newCount(1)
                        .updatedCount(0)
                        .byRiskLevel(Map.of("high", 1L))
                        .byCategory(Map.of("정책", 1L))
                        .build())
                .findings(null)
                .build();
        when(reportQueryService.getReport(17L, false)).thenReturn(detail);

        mockMvc.perform(get("/api/news/reports/17").param("includeFindings", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.markdownBody").value("# 보고서"))
                .andExpect(jsonPath("$.result.findings").doesNotExist());
    }

    @Test
    void detailReturnsReportNotFoundCode() throws Exception {
        when(reportQueryService.getReport(99L, true))
                .thenThrow(new ReportException(ReportErrorCode.REPORT_NOT_FOUND));

        mockMvc.perform(get("/api/news/reports/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT404"))
                .andExpect(jsonPath("$.message").value("보고서를 찾을 수 없습니다."));
    }
}
