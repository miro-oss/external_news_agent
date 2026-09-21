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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        when(commandService.approve(1L, null)).thenReturn(proposal("APPROVED"));

        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.status").value("APPROVED"));
        verify(commandService).approve(1L, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"selectedChangeIndexes\":null}"})
    void missingOrNullSelectionUsesLegacyFullApproval(String body) throws Exception {
        when(commandService.approve(1L, null)).thenReturn(proposal("APPROVED"));
        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/approve")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.selectedChangeIndexes[0]").value(0));
        verify(commandService).approve(1L, null);
    }

    @Test
    void forwardsSelectedIndexesAndReturnsThePersistedSelection() throws Exception {
        when(commandService.approve(1L, List.of(0))).thenReturn(proposal("APPROVED"));
        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selectedChangeIndexes\":[0]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.selectedChangeIndexes[0]").value(0))
                .andExpect(jsonPath("$.result.changes[0].keyword").value("HBM4"));
        verify(commandService).approve(1L, List.of(0));
    }

    @Test
    void returnsDomainBadRequestForInvalidSelection() throws Exception {
        when(commandService.approve(1L, List.of()))
                .thenThrow(new TopicException(TopicErrorCode.INVALID_KEYWORD_PROPOSAL_SELECTION));
        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"selectedChangeIndexes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOPIC400"))
                .andExpect(jsonPath("$.message").value("적용할 키워드 변경 항목을 올바르게 선택해 주세요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[0.5]", "[\"0\"]", "[true]", "[{}]", "[2147483648]", "0"})
    void malformedSelectionCannotBeCoercedIntoApprovingADifferentKeyword(String selection) throws Exception {
        mockMvc.perform(post("/api/news/topics/keyword-proposals/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedChangeIndexes\":" + selection + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.message").value("입력값 검증 실패입니다."));
        verifyNoInteractions(commandService);
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
        when(commandService.approve(99L, null))
                .thenThrow(new TopicException(TopicErrorCode.KEYWORD_PROPOSAL_NOT_FOUND));

        mockMvc.perform(post("/api/news/topics/keyword-proposals/99/approve"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOPIC404"))
                .andExpect(jsonPath("$.message").value("키워드 제안을 찾을 수 없습니다."));
    }

    @Test
    void repeatReviewReturnsTheExistingResultWithoutNewResponseFields() throws Exception {
        when(commandService.reject(1L)).thenReturn(proposal("REJECTED"));
        for (int retry = 0; retry < 2; retry++) {
            mockMvc.perform(post("/api/news/topics/keyword-proposals/1/reject"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("COMMON200"))
                    .andExpect(jsonPath("$.message").value("수정되었습니다."))
                    .andExpect(jsonPath("$.result.status").value("REJECTED"))
                    .andExpect(jsonPath("$.result.appliedChanges").doesNotExist());
        }
    }

    @Test
    void returnsConflictWhenProposalBaselineIsStale() throws Exception {
        when(commandService.approve(1L, null))
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
                .selectedChangeIndexes("PENDING".equals(status) ? null : List.of(0))
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
