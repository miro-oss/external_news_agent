package com.example.be.domain.feedback.controller;

import com.example.be.domain.feedback.exception.FeedbackErrors;
import com.example.be.domain.feedback.service.ReportEventFeedbackService;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.exception.code.ReportErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static com.example.be.domain.feedback.dto.res.FeedbackResDTO.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReportEventFeedbackController.class)
class ReportEventFeedbackControllerTest {
    private static final String KEY="a".repeat(64);
    @Autowired MockMvc mvc;
    @MockitoBean ReportEventFeedbackService service;

    @Test void localCardContextDoesNotRequireRecipientOrToken() throws Exception {
        when(service.context(17)).thenReturn(new EventFeedbackContext(17,List.of(new PublicEvent(KEY,0,"이벤트","요약","이유",List.of(501L,502L))),List.of()));
        mvc.perform(get("/api/news/reports/17/event-feedback"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("COMMON200")).andExpect(jsonPath("$.result.events[0].sourceFindingIds[1]").value(502))
                .andExpect(jsonPath("$.result.events[0].eventKey").value(KEY)).andExpect(jsonPath("$.result.feedback").isEmpty());
    }
    @Test void submitUses201EnvelopeAndEventTargetWithoutFindingId() throws Exception {
        when(service.submit(eq(17L),any())).thenReturn(new EventFeedback(1,KEY,"SUMMARY_ERROR","의견","PENDING",null,null,OffsetDateTime.parse("2026-09-29T10:00:00+09:00")));
        mvc.perform(post("/api/news/reports/17/event-feedback").contentType("application/json").content("""
                {"eventKey":"%s","category":"SUMMARY_ERROR","comment":"의견","idempotencyKey":"key"}
                """.formatted(KEY)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("COMMON201")).andExpect(jsonPath("$.result.eventKey").value(KEY))
                .andExpect(jsonPath("$.result.itemId").doesNotExist()).andExpect(jsonPath("$.result.recipientId").doesNotExist());
    }
    @Test void notFoundAndStaleResponsesAreNotCachedAndRemoteRequestsStayBlocked() throws Exception {
        when(service.context(99)).thenThrow(new ReportException(ReportErrorCode.REPORT_NOT_FOUND));
        mvc.perform(get("/api/news/reports/99/event-feedback"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("REPORT404"));
        when(service.submit(eq(17L),any())).thenThrow(FeedbackErrors.conflict());
        mvc.perform(post("/api/news/reports/17/event-feedback").contentType("application/json").content("""
                {"eventKey":"%s","category":"OTHER","comment":"의견","idempotencyKey":"key"}
                """.formatted(KEY)))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("COMMON409"));
        mvc.perform(get("/api/news/reports/18/event-feedback").with(r->{r.setRemoteAddr("203.0.113.10");return r;}))
                .andExpect(status().isBadRequest());
        verify(service,never()).context(18);
    }
}
