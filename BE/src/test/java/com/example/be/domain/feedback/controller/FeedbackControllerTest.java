package com.example.be.domain.feedback.controller;

import com.example.be.domain.feedback.exception.FeedbackErrors;
import com.example.be.domain.feedback.service.FeedbackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.example.be.domain.feedback.dto.res.FeedbackResDTO.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FeedbackController.class)
class FeedbackControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FeedbackService service;

    @Test
    void localExportIntentionallyAllowsAccessWithoutOperatorOrRecipientCredentials() throws Exception {
        when(service.export(0, 50)).thenReturn(Map.of(
                "schemaVersion", 1, "cases", List.of(), "nextAfterId", 0, "hasNext", false));

        mvc.perform(get("/api/feedback/evaluation-candidates"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.schemaVersion").value(1))
                .andExpect(jsonPath("$.result.cases").isEmpty());
        verify(service).export(0, 50);
    }

    @Test
    void remoteExportCannotBypassLocalBoundaryWithForwardedHeaders() throws Exception {
        mvc.perform(get("/api/feedback/evaluation-candidates")
                        .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
                        .header("Forwarded", "for=127.0.0.1;host=localhost")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .header("X-Forwarded-Host", "localhost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"));
        verifyNoInteractions(service);
    }

    @Test void creationUses201EnvelopeAndNeverCachesCapabilityResponsesIncludingFailures()throws Exception {
        when(service.submit(any())).thenReturn(new PublicFeedback(1,2,"PREFERENCE","주가 제외","PENDING",null,null,OffsetDateTime.parse("2026-09-29T10:00:00+09:00")));
        mvc.perform(post("/api/feedback").contentType("application/json").content("""
                {"token":"capability","itemId":2,"category":"PREFERENCE","comment":"주가 제외","allowPersonalization":true,"idempotencyKey":"key"}
                """)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("COMMON201")).andExpect(jsonPath("$.result.status").value("PENDING"))
                .andExpect(jsonPath("$.result.createdAt").value("2026-09-29T10:00:00+09:00"));
        when(service.context(any())).thenThrow(FeedbackErrors.missing());
        mvc.perform(post("/api/feedback/context").contentType("application/json").content("{\"token\":\"bad\"}"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("COMMON404")).andExpect(jsonPath("$.message").value("리소스를 찾을 수 없습니다."));
    }
}
