package com.example.be.domain.companyinterest;

import com.example.be.domain.feedback.exception.FeedbackErrors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static com.example.be.domain.companyinterest.CompanyInterestModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CompanyInterestController.class)
class CompanyInterestControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CompanyInterestService service;

    @Test void getUsesCommonEnvelopeAndExplicitUnappliedState() throws Exception {
        var now = OffsetDateTime.parse("2026-10-12T10:00:00+09:00");
        var criterion = new Criterion(1, 10, 17, "수학 성과", "원래 의견", "진단", List.of(new Topic(2, "AI")),
                2L, "수학 성능 기사 제외", "사용자 관심", Status.DRAFT, 1, now, now, null, null);
        when(service.list()).thenReturn(new Criteria(List.of(criterion)));
        mvc.perform(get("/api/news/company-interests"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.criteria[0].feedbackId").value(10))
                .andExpect(jsonPath("$.result.criteria[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.result.criteria[0].lastApplication").isEmpty())
                .andExpect(jsonPath("$.result.criteria[0].createdAt").value("2026-10-12T10:00:00+09:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/news-agent"})
    void patchConflictAndMalformedRequestsRemainUncached(String contextPath) throws Exception {
        when(service.update(eq(1L), any())).thenThrow(FeedbackErrors.conflict());
        mvc.perform(patch(contextPath + "/api/news/company-interests/1").contextPath(contextPath)
                .contentType("application/json").content("""
                {"version":1,"instruction":"기준","topicId":2,"status":"ACTIVE"}
                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMON409"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(patch(contextPath + "/api/news/company-interests/1").contextPath(contextPath)
                .contentType("application/json").content("bad json"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/news-agent"})
    void remoteCallsCannotReadOrActivateCompanyCriteria(String contextPath) throws Exception {
        mvc.perform(get(contextPath + "/api/news/company-interests").contextPath(contextPath)
                .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; }))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(service);
    }
}
