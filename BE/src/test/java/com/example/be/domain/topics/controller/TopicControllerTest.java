package com.example.be.domain.topics.controller;

import com.example.be.domain.topics.dto.req.TopicReqDTO;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.exception.TopicException;
import com.example.be.domain.topics.exception.code.TopicErrorCode;
import com.example.be.domain.topics.service.command.TopicCommandService;
import com.example.be.domain.topics.service.query.TopicQueryService;
import com.example.be.global.apiPayload.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .build();
    }
}
