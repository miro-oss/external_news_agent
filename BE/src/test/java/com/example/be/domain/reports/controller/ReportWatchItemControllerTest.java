package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.dto.req.ReportWatchItemPromotionRequest;
import com.example.be.domain.reports.dto.res.ReportWatchItemPromotionResult;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import com.example.be.domain.reports.service.ReportWatchItemPromotionService;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportWatchItemController.class)
class ReportWatchItemControllerTest {
    private static final String BODY = """
            {"topic":"공급 일정 확인","reason":"공식 발표가 나면 확인합니다.","sourceFindingIds":[501,502]}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean ReportWatchItemPromotionService service;

    @Test void promotionUsesTheCommonUpdateEnvelopeAndFullWatchIdentity() throws Exception {
        when(service.promote(eq(17L), any())).thenReturn(new ReportWatchItemPromotionResult(17));
        mvc.perform(post("/api/news/reports/17/watch-items/promote").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON200")).andExpect(jsonPath("$.message").value("성공입니다."))
                .andExpect(jsonPath("$.result.reportId").value(17));
        verify(service).promote(17, new ReportWatchItemPromotionRequest("공급 일정 확인", "공식 발표가 나면 확인합니다.", List.of(501L, 502L)));
    }

    @Test void conflictNotFoundAndInvalidInputKeepCommonErrorContracts() throws Exception {
        when(service.promote(eq(17L), any())).thenThrow(new GeneralException(GeneralErrorCode.CONFLICT));
        when(service.promote(eq(99L), any())).thenThrow(new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        when(service.promote(eq(0L), any())).thenThrow(new GeneralException(GeneralErrorCode.BAD_REQUEST));
        mvc.perform(post("/api/news/reports/17/watch-items/promote").contentType("application/json").content(BODY))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON409"));
        mvc.perform(post("/api/news/reports/99/watch-items/promote").contentType("application/json").content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT404"));
        mvc.perform(post("/api/news/reports/0/watch-items/promote").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON400"));
    }

    @Test void nonLocalCallsNeverReachMutation() throws Exception {
        mvc.perform(post("/api/news/reports/17/watch-items/promote").contentType("application/json").content(BODY)
                        .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; }))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }
}
