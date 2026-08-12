package com.example.be.domain.topics.controller;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.dto.res.TopicSourceResDTO;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.service.query.TopicSourceQueryService;
import com.example.be.global.apiPayload.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TopicSourceController.class)
class TopicSourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TopicSourceQueryService topicSourceQueryService;

    @Test
    void getCombinationsRespondsWithCombinationCountAtRoot() throws Exception {
        when(topicSourceQueryService.getCombinations(eq(null), eq(null), eq(true), eq(0), eq(20)))
                .thenReturn(TopicSourceResDTO.CombinationPage.from(
                        PageResponse.of(List.of(combination()), 0, 20, 1L)));

        mockMvc.perform(get("/api/news/topic-sources").param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].topicName").value("HBM"))
                .andExpect(jsonPath("$.result.content[0].sourceName").value("Google News RSS"))
                .andExpect(jsonPath("$.result.content[0].sourceKind").value("SEARCH"))
                .andExpect(jsonPath("$.result.content[0].active").value(true))
                .andExpect(jsonPath("$.result.content[0].lastCollectedCount").value(nullValue()))
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.combinationCount").value(1))
                .andExpect(jsonPath("$.result.hasNext").value(false));
    }

    @Test
    void getCombinationsRespondsWithTopicNotFoundCode() throws Exception {
        when(topicSourceQueryService.getCombinations(eq(99L), eq(null), eq(null), eq(0), eq(20)))
                .thenThrow(new TopicException(TopicErrorCode.TOPIC_NOT_FOUND));

        mockMvc.perform(get("/api/news/topic-sources").param("topicId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("TOPIC404"))
                .andExpect(jsonPath("$.message").value("수집 주제를 찾을 수 없습니다."));
    }

    private TopicSourceResDTO.Combination combination() {
        return TopicSourceResDTO.Combination.builder()
                .topicId(1L)
                .topicName("HBM")
                .sourceId(2L)
                .sourceName("Google News RSS")
                .sourceKind(Source.KIND_SEARCH)
                .queryText("HBM 반도체")
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .lastCollectedAt(OffsetDateTime.of(2026, 8, 10, 8, 0, 0, 0, ZoneOffset.ofHours(9)))
                .lastCollectedCount(null)
                .build();
    }
}
