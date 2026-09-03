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
    void dailyFilterAndNullableRunIdFollowContract() throws Exception {
        var scope = com.example.be.domain.reports.entity.ReportScope.DAILY;
        var date = java.time.LocalDate.of(2026, 9, 3);
        var detail = ReportResDTO.Detail.builder().id(77L).reportScope(scope).reportDate(date)
                .sourceRunIds(List.of(42L, 43L)).build();
        when(reportQueryService.getLatest(true, scope)).thenReturn(detail);
        mockMvc.perform(get("/api/news/reports/latest").param("reportScope", "DAILY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.reportScope").value("DAILY"))
                .andExpect(jsonPath("$.result.runId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.result.reportDate").value("2026-09-03"))
                .andExpect(jsonPath("$.result.sourceRunIds[1]").value(43));
        when(reportQueryService.getReports(null, null, 0, 20, scope))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));
        mockMvc.perform(get("/api/news/reports").param("reportScope", "DAILY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalElements").value(0));
        mockMvc.perform(get("/api/news/reports").param("reportScope", "UNKNOWN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON400"));
    }

    @Test
    void getReportsRespondsWithPagedSummary() throws Exception {
        ReportResDTO.Summary summary = ReportResDTO.Summary.builder()
                .id(17L)
                .runId(42L)
                .title("반도체 뉴스 보고서")
                .generatedAt(OffsetDateTime.parse("2026-08-18T10:03:12+09:00"))
                .modelName("stub-report-v1")
                .findingCount(17)
                .highSensitivityCount(3)
                .deliveryStatus("NOT_SENT")
                .build();
        when(reportQueryService.getReports("2026-08-01", null, 0, 20))
                .thenReturn(PageResponse.of(List.of(summary), 0, 20, 1));

        mockMvc.perform(get("/api/news/reports").param("from", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].runId").value(42))
                .andExpect(jsonPath("$.result.content[0].highSensitivityCount").value(3))
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
                .modelName("claude-sonnet-5")
                .promptVersion("report.ko.v1.4")
                .llmProvider("anthropic")
                .summaryStats(ReportResDTO.SummaryStats.builder()
                        .findingCount(1)
                        .newCount(1)
                        .updatedCount(0)
                        .bySensitivityLevel(Map.of("high", 1L))
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
    void detailIncludesSentenceEvidenceForFindingKeyPoints() throws Exception {
        ReportResDTO.Detail detail = ReportResDTO.Detail.builder()
                .id(17L)
                .runId(42L)
                .title("반도체 뉴스 보고서")
                .markdownBody("# 보고서")
                .modelName("claude-sonnet-5")
                .promptVersion("report.ko.v1.4")
                .llmProvider("anthropic")
                .summaryStats(ReportResDTO.SummaryStats.builder()
                        .findingCount(1)
                        .newCount(1)
                        .updatedCount(0)
                        .bySensitivityLevel(Map.of("high", 1L))
                        .byCategory(Map.of("정책", 1L))
                        .build())
                .findings(List.of(ReportResDTO.Finding.builder()
                        .id(501L)
                        .articleId(1024L)
                        .issueId(88L)
                        .issue(ReportResDTO.IssueSummary.builder()
                                .id(88L)
                                .title("HBM4 양산 일정 이슈")
                                .summary("양산 일정이 앞당겨졌다.")
                                .lastSeenAt(OffsetDateTime.parse("2026-08-18T09:00:00+09:00"))
                                .articleCount(3)
                                .publisherCount(2)
                                .independentContentCount(2)
                                .topicName("HBM")
                                .entities(List.of("SK하이닉스", "HBM4"))
                                .build())
                        .articleTitle("HBM4 양산 일정 단축")
                        .keyPoints(List.of(ReportResDTO.KeyPoint.builder()
                                .text("양산 일정이 앞당겨졌다.")
                                .evidence(List.of(0))
                                .groundedness("grounded")
                                .groundingReason("문장에서 직접 확인됩니다.")
                                .claimType("FACT")
                                .attributedTo(null)
                                .build()))
                        .perspectiveTags(List.of(ReportResDTO.PerspectiveTag.builder()
                                .audience("CHIP_MAKER")
                                .relevance("high")
                                .hook("생산 계획에 직접 영향을 줍니다.")
                                .evidenceSentenceIds(List.of(0))
                                .build()))
                        .build()))
                .build();
        when(reportQueryService.getReport(17L, true)).thenReturn(detail);

        mockMvc.perform(get("/api/news/reports/17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.promptVersion").value("report.ko.v1.4"))
                .andExpect(jsonPath("$.result.llmProvider").value("anthropic"))
                .andExpect(jsonPath("$.result.findings[0].articleId").value(1024))
                .andExpect(jsonPath("$.result.findings[0].issueId").value(88))
                .andExpect(jsonPath("$.result.findings[0].issue.title").value("HBM4 양산 일정 이슈"))
                .andExpect(jsonPath("$.result.findings[0].issue.articleCount").value(3))
                .andExpect(jsonPath("$.result.findings[0].issue.entities[1]").value("HBM4"))
                .andExpect(jsonPath("$.result.findings[0].keyPoints[0].text")
                        .value("양산 일정이 앞당겨졌다."))
                .andExpect(jsonPath("$.result.findings[0].keyPoints[0].evidence[0]").value(0))
                .andExpect(jsonPath("$.result.findings[0].keyPoints[0].groundedness")
                        .value("grounded"))
                .andExpect(jsonPath("$.result.findings[0].keyPoints[0].groundingReason")
                        .value("문장에서 직접 확인됩니다."))
                .andExpect(jsonPath("$.result.findings[0].keyPoints[0].claimType").value("FACT"))
                .andExpect(jsonPath("$.result.findings[0].keyPoints[0].attributedTo").isEmpty())
                .andExpect(jsonPath("$.result.findings[0].perspectiveTags[0].audience")
                        .value("CHIP_MAKER"))
                .andExpect(jsonPath("$.result.findings[0].perspectiveTags[0].hook")
                        .value("생산 계획에 직접 영향을 줍니다."));
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
