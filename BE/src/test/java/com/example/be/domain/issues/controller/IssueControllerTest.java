package com.example.be.domain.issues.controller;

import com.example.be.domain.issues.dto.res.IssueResDTO;
import com.example.be.domain.issues.entity.IssueCrossSource;
import com.example.be.domain.issues.exception.IssueException;
import com.example.be.domain.issues.exception.code.IssueErrorCode;
import com.example.be.domain.issues.service.IssueQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IssueController.class)
class IssueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueQueryService issueQueryService;

    @Test
    void getIssueReturnsProvenanceCountsAndRepresentative() throws Exception {
        when(issueQueryService.getIssue(88L)).thenReturn(IssueResDTO.Detail.builder()
                .id(88L)
                .title("HBM4 양산 일정")
                .status("EMERGING")
                .articleCount(3)
                .publisherCount(3)
                .independentContentCount(2)
                .entities(List.of("SK하이닉스", "HBM4"))
                .crossSource(IssueCrossSource.empty())
                .representativeArticleId(1024L)
                .articles(List.of(IssueResDTO.Article.builder()
                        .id(1024L)
                        .role("REPRESENTATIVE")
                        .build()))
                .build());

        mockMvc.perform(get("/api/news/issues/88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.articleCount").value(3))
                .andExpect(jsonPath("$.result.independentContentCount").value(2))
                .andExpect(jsonPath("$.result.representativeArticleId").value(1024))
                .andExpect(jsonPath("$.result.crossSource.conflicts").isArray());
    }

    @Test
    void getIssueReturnsIssue404() throws Exception {
        when(issueQueryService.getIssue(99L))
                .thenThrow(new IssueException(IssueErrorCode.ISSUE_NOT_FOUND));

        mockMvc.perform(get("/api/news/issues/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISSUE404"))
                .andExpect(jsonPath("$.message").value("이슈를 찾을 수 없습니다."));
    }
}
