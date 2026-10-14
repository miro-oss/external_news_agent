package com.example.be.domain.reports.dart;

import com.example.be.domain.reports.controller.ReportDartController;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportDartController.class)
class ReportDartControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReportDartService service;

    @Test void readUsesCommonEnvelopeAndPreservesDecimalStringsAndNullableData() throws Exception {
        when(service.get(17L)).thenReturn(new ReportDartDTO.Result(17L, List.of(
                new ReportDartDTO.CompanyAttachment(SAMSUNG, "READY", ReportDartMessages.READY, data()))));
        mvc.perform(get("/api/news/reports/17/dart"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("성공입니다."))
                .andExpect(jsonPath("$.result.companies[0].data.quarters[0].revenue").value("10000000000000001"));
        verify(service, never()).retry(17L);
    }

    @Test void retryNeedsNoBodyAndReturnsPendingUsingTheSameContract() throws Exception {
        when(service.retry(17L)).thenReturn(new ReportDartDTO.Result(17L, List.of(
                new ReportDartDTO.CompanyAttachment(SAMSUNG, "PENDING", ReportDartMessages.PENDING, null))));
        mvc.perform(post("/api/news/reports/17/dart/retry"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.companies[0].status").value("PENDING"))
                .andExpect(jsonPath("$.result.companies[0].message").value("공시 자료를 준비하고 있습니다."))
                .andExpect(jsonPath("$.result.companies[0].data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void missingReportUsesSpecifiedDomainError() throws Exception {
        when(service.get(404L)).thenThrow(new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        mvc.perform(get("/api/news/reports/404/dart"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT404"))
                .andExpect(jsonPath("$.message").value("보고서를 찾을 수 없습니다."));
    }
}
