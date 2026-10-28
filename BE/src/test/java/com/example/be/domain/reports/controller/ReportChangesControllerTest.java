package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportChangesQueryService;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportChangesController.class)
class ReportChangesControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ReportChangesQueryService comparisons;

    @Test void returnsNotionEnvelopeStateAndCapturedEvidenceWithoutExtraRequestParameters() throws Exception {
        var side = new ReportChanges.Side(88, 1, "양산 일정", "10월 양산", List.of(
                new ReportChanges.Claim("finding-601-claim-0", "10월 양산", List.of(new ReportChanges.Evidence(
                        601, 1124, 52, "당시 제목", "https://example.com/news", 0, "10월에 양산한다고 발표했다.")))));
        when(comparisons.get(101)).thenReturn(new ReportChanges(101, LocalDate.of(2026, 9, 10), 100L,
                LocalDate.of(2026, 9, 9), ReportChanges.Status.READY, ReportChanges.Status.READY.message, false, List.of(),
                List.of(new ReportChanges.Item("issue-88", ReportChanges.Type.UPDATED, "양산 일정", "일정 구체화", side, side))));
        mvc.perform(get("/api/news/reports/101/changes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("성공입니다."))
                .andExpect(jsonPath("$.result.status").value("READY"))
                .andExpect(jsonPath("$.result.message").value("지난 보고서와의 비교가 완료되었습니다."))
                .andExpect(jsonPath("$.result.reportDate").value("2026-09-10"))
                .andExpect(jsonPath("$.result.items[0].previous.claims[0].evidence[0].sentenceIndex").value(0))
                .andExpect(jsonPath("$.result.items[0].previous.claims[0].evidence[0].text").value("10월에 양산한다고 발표했다."));
        verify(comparisons).get(101);
    }

    @Test void runComparisonCarriesFrozenPeriodsAndNullableNumericSampleWithoutChangingEnvelope() throws Exception {
        var result = new ReportChanges(201, null, 200L, null, ReportChanges.Status.READY,
                ReportChanges.Status.READY.message, false, List.of(), List.of(),
                new ReportChanges.Periods("RUN", "2026-10-19 09:00 실행", "2026-10-26 09:00 실행"),
                new ReportChanges.KeywordChanges(ReportChanges.KeywordStatus.AVAILABLE, null, "같은 조건의 관측 비중입니다.",
                        10, 20, false, List.of(new ReportChanges.KeywordChange("HBM", 0, 5, 0.0, 25.0, 25.0))));
        when(comparisons.get(201)).thenReturn(result);
        mvc.perform(get("/api/news/reports/201/changes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.reportDate").isEmpty())
                .andExpect(jsonPath("$.result.periods.basis").value("RUN"))
                .andExpect(jsonPath("$.result.periods.previousLabel").value("2026-10-19 09:00 실행"))
                .andExpect(jsonPath("$.result.keywordChanges.items[0].previousIssueCount").value(0))
                .andExpect(jsonPath("$.result.keywordChanges.items[0].changePercentagePoints").value(25.0));
    }

    @Test void nonexistentHiddenOrPendingReportUsesSpecified404() throws Exception {
        when(comparisons.get(101)).thenThrow(new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        mvc.perform(get("/api/news/reports/101/changes"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT404"))
                .andExpect(jsonPath("$.message").value("보고서를 찾을 수 없습니다."));
    }
}
