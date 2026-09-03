package com.example.be.domain.topics.controller;

import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.service.command.TopicKeywordProposalCommandService;
import com.example.be.domain.topics.service.query.TopicKeywordProposalQueryService;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TopicKeywordProposalController.class)
class TopicKeywordProposalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TopicKeywordProposalQueryService queryService;

    @MockitoBean
    private TopicKeywordProposalCommandService commandService;

    @Test
    void listsPendingProposalsWithPagedEnvelope() throws Exception {
        when(queryService.getKeywordProposals("PENDING", 0, 20))
                .thenReturn(PageResponse.of(List.of(proposal("PENDING")), 0, 20, 1L));

        mockMvc.perform(get("/api/news/topics/keyword-proposals")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].topicName").value("HBM"))
                .andExpect(jsonPath("$.result.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.result.content[0].changes[0].keyword").value("HBM4"))
                .andExpect(jsonPath("$.result.totalElements").value(1));
    }

    @Test
    void approvesProposalWithUpdatedEnvelope() throws Exception {
        when(commandService.approve(1L)).thenReturn(proposal("APPROVED"));

        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.status").value("APPROVED"));
    }

    @Test
    void rejectsProposalWithUpdatedEnvelope() throws Exception {
        when(commandService.reject(1L)).thenReturn(proposal("REJECTED"));

        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.status").value("REJECTED"));
    }

    @Test
    void returnsBadRequestForInvalidStatus() throws Exception {
        when(queryService.getKeywordProposals("UNKNOWN", 0, 20))
                .thenThrow(new GeneralException(
                        GeneralErrorCode.BAD_REQUEST,
                        "status는 PENDING / APPROVED / REJECTED 중 하나여야 합니다."));

        mockMvc.perform(get("/api/news/topics/keyword-proposals")
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"));
    }

    @Test
    void returnsNotFoundWhenProposalDoesNotExist() throws Exception {
        when(commandService.approve(99L))
                .thenThrow(new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_NOT_FOUND));

        mockMvc.perform(post("/api/news/topics/keyword-proposals/99/approve"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOPIC404"))
                .andExpect(jsonPath("$.message").value("키워드 제안을 찾을 수 없습니다."));
    }

    @Test
    void returnsConflictWhenProposalWasAlreadyReviewed() throws Exception {
        when(commandService.reject(1L))
                .thenThrow(new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_ALREADY_REVIEWED));

        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/reject"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC409"))
                .andExpect(jsonPath("$.message").value("이미 검토가 끝난 키워드 제안입니다."));
    }

    @Test
    void returnsConflictWhenProposalBaselineIsStale() throws Exception {
        when(commandService.approve(1L))
                .thenThrow(new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_STALE));

        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC409"))
                .andExpect(jsonPath("$.message")
                        .value("제안 생성 후 주제 키워드가 변경되었습니다. 새 제안을 기다려 주세요."));
    }

    private TopicKeywordProposalResDTO.Item proposal(String status) {
        return TopicKeywordProposalResDTO.Item.builder()
                .id(1L)
                .topicId(3L)
                .topicName("HBM")
                .collectionRunId(148L)
                .status(status)
                .summary("HBM4를 선택 키워드로 추가합니다.")
                .reviewedAt("PENDING".equals(status)
                        ? null
                        : OffsetDateTime.of(2026, 9, 3, 11, 20, 0, 0, ZoneOffset.ofHours(9)))
                .createdAt(OffsetDateTime.of(2026, 9, 3, 10, 15, 0, 0, ZoneOffset.ofHours(9)))
                .currentKeywords(TopicKeywordProposalResDTO.CurrentKeywords.builder()
                        .requiredKeywords(List.of("HBM"))
                        .optionalKeywords(List.of("SK하이닉스"))
                        .excludedKeywords(List.of("광고"))
                        .build())
                .changes(List.of(TopicKeywordProposalResDTO.Change.builder()
                        .bucket("OPTIONAL")
                        .action("ADD")
                        .keyword("HBM4")
                        .reason("신규 기사에서 반복 등장했습니다.")
                        .build()))
                .build();
    }
}
