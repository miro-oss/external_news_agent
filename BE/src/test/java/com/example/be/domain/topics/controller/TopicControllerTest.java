package com.example.be.domain.topics.controller;

import com.example.be.domain.topics.dto.req.TopicReqDTO;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.service.command.TopicCommandService;
import com.example.be.domain.topics.service.query.TopicQueryService;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TopicController.class)
class TopicControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TopicCommandService topicCommandService;

    @MockitoBean
    private TopicQueryService topicQueryService;

    @Test
    void createTopicRespondsWithCreatedEnvelope() throws Exception {
        when(topicCommandService.createTopic(any(TopicReqDTO.Create.class))).thenReturn(created());

        mockMvc.perform(post("/api/news/topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "HBM",
                                  "queryText": "HBM 반도체",
                                  "sourceIds": [1]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("COMMON201"))
                .andExpect(jsonPath("$.message").value("등록되었습니다."))
                .andExpect(jsonPath("$.result.id").value(1))
                .andExpect(jsonPath("$.result.sources[0].sourceKind").value("FEED"));
    }

    @Test
    void getTopicsRespondsWithPagedEnvelope() throws Exception {
        PageResponse<TopicResDTO.Summary> page = PageResponse.of(List.of(summary()), 0, 20, 1L);
        when(topicQueryService.getTopics(eq(true), eq(null), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/news/topics").param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].name").value("HBM"))
                .andExpect(jsonPath("$.result.content[0].linkedSourceCount").value(4))
                .andExpect(jsonPath("$.result.content[0].surgeKeywords[0].keyword").value("HBM4"))
                .andExpect(jsonPath("$.result.content[0].relatedKeywords[0].sharePercent").value(60.00))
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.hasNext").value(false));
    }

    @Test
    void getTopicRespondsWithTopicNotFoundCode() throws Exception {
        when(topicQueryService.getTopic(99L)).thenThrow(new TopicException(TopicErrorCode.TOPIC_NOT_FOUND));

        mockMvc.perform(get("/api/news/topics/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("TOPIC404"))
                .andExpect(jsonPath("$.message").value("수집 주제를 찾을 수 없습니다."));
    }

    @Test
    void updateActivationRespondsWithUpdatedEnvelope() throws Exception {
        when(topicCommandService.updateActivation(eq(1L), any(TopicReqDTO.Activation.class)))
                .thenReturn(TopicResDTO.Activated.builder()
                        .id(1L)
                        .name("HBM")
                        .active(false)
                        .build());

        mockMvc.perform(patch("/api/news/topics/1/activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "active": false }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.active").value(false))
                .andExpect(jsonPath("$.result.nextScheduledAt").value(nullValue()));
    }

    @Test
    void updateTopicRespondsWithUpdatedEnvelope() throws Exception {
        when(topicCommandService.updateTopic(eq(1L), any(TopicReqDTO.Update.class)))
                .thenReturn(TopicResDTO.Updated.builder()
                        .id(1L)
                        .name("HBM")
                        .queryText("HBM4 반도체")
                        .requiredKeywords(List.of("HBM"))
                        .optionalKeywords(List.of())
                        .excludedKeywords(List.of("광고", "채용", "주가"))
                        .batchSize(20)
                        .intervalMinutes(720)
                        .active(true)
                        .build());

        mockMvc.perform(patch("/api/news/topics/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "queryText": "HBM4 반도체",
                                  "excludedKeywords": ["광고", "채용", "주가"],
                                  "batchSize": 20,
                                  "intervalMinutes": 720
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.queryText").value("HBM4 반도체"))
                .andExpect(jsonPath("$.result.batchSize").value(20))
                .andExpect(jsonPath("$.result.intervalMinutes").value(720));
    }

    @Test
    void replaceSourcesRespondsWithLinkedEnvelope() throws Exception {
        when(topicCommandService.replaceSources(eq(1L), any(TopicReqDTO.SourceLink.class)))
                .thenReturn(TopicResDTO.SourcesLinked.builder()
                        .topicId(1L)
                        .sources(List.of(
                                TopicResDTO.SourceBrief.builder()
                                        .id(1L).name("ETNews 반도체").sourceKind("FEED").build(),
                                TopicResDTO.SourceBrief.builder()
                                        .id(2L).name("Google News RSS").sourceKind("SEARCH").build()))
                        .addedCount(1)
                        .removedCount(0)
                        .combinationCount(2)
                        .build());

        mockMvc.perform(put("/api/news/topics/1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sourceIds": [1, 2] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("연결되었습니다."))
                .andExpect(jsonPath("$.result.topicId").value(1))
                .andExpect(jsonPath("$.result.sources[1].sourceKind").value("SEARCH"))
                .andExpect(jsonPath("$.result.addedCount").value(1))
                .andExpect(jsonPath("$.result.removedCount").value(0))
                .andExpect(jsonPath("$.result.combinationCount").value(2));
    }

    @Test
    void replaceSourcesRespondsWithNotFoundSourceIds() throws Exception {
        when(topicCommandService.replaceSources(eq(1L), any(TopicReqDTO.SourceLink.class)))
                .thenThrow(new TopicException(TopicErrorCode.SOURCE_NOT_FOUND,
                        Map.of("notFoundSourceIds", List.of(99L))));

        mockMvc.perform(put("/api/news/topics/1/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sourceIds": [99] }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE404"))
                .andExpect(jsonPath("$.result.notFoundSourceIds[0]").value(99));
    }

    @Test
    void deleteTopicRespondsWithDeletedEnvelope() throws Exception {
        when(topicCommandService.deleteTopic(1L)).thenReturn(TopicResDTO.Deleted.builder()
                .id(1L)
                .deletedAt(OffsetDateTime.of(2026, 8, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9)))
                .unlinkedSourceCount(3)
                .build());

        mockMvc.perform(delete("/api/news/topics/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("삭제되었습니다."))
                .andExpect(jsonPath("$.result.id").value(1))
                .andExpect(jsonPath("$.result.deletedAt").value("2026-08-10T10:00:00+09:00"))
                .andExpect(jsonPath("$.result.unlinkedSourceCount").value(3));
    }

    @Test
    void getTopicsRespondsWithBadRequestEnvelopeOnInvalidSize() throws Exception {
        when(topicQueryService.getTopics(null, null, 0, 101)).thenThrow(
                new GeneralException(GeneralErrorCode.BAD_REQUEST, "size는 1 이상 100 이하여야 합니다."));

        mockMvc.perform(get("/api/news/topics").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.message").value("size는 1 이상 100 이하여야 합니다."));
    }

    private TopicResDTO.Created created() {
        return TopicResDTO.Created.builder()
                .id(1L)
                .name("HBM")
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .sources(List.of(TopicResDTO.SourceBrief.builder()
                        .id(1L)
                        .name("ETNews 반도체")
                        .sourceKind("FEED")
                        .build()))
                .build();
    }

    private TopicResDTO.Summary summary() {
        return TopicResDTO.Summary.builder()
                .id(1L)
                .name("HBM")
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of())
                .excludedKeywords(List.of())
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .linkedSourceCount(4)
                .surgeKeywords(List.of(TopicResDTO.KeywordTrend.builder()
                        .keyword("HBM4")
                        .issueCount(4)
                        .previousIssueCount(1)
                        .deltaIssueCount(3)
                        .zScore(new BigDecimal("2.87"))
                        .burst(true)
                        .build()))
                .relatedKeywords(List.of(TopicResDTO.RelatedKeyword.builder()
                        .keyword("마이크론")
                        .issueCount(3)
                        .sharePercent(new BigDecimal("60.00"))
                        .build()))
                .build();
    }
}
