package com.example.be.domain.sources.controller;

import com.example.be.domain.sources.dto.req.SourceReqDTO;
import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.exception.SourceException;
import com.example.be.domain.sources.exception.code.SourceErrorCode;
import com.example.be.domain.sources.service.command.SourceCommandService;
import com.example.be.domain.sources.service.query.SourceQueryService;
import com.example.be.global.apiPayload.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SourceController.class)
class SourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SourceCommandService sourceCommandService;

    @MockitoBean
    private SourceQueryService sourceQueryService;

    @Test
    void createSourceRespondsWithCreatedEnvelope() throws Exception {
        when(sourceCommandService.createSource(any(SourceReqDTO.Create.class))).thenReturn(created());

        mockMvc.perform(post("/api/news/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceKind": "SEARCH",
                                  "name": "Naver 뉴스 검색",
                                  "urlTemplate": "NAVER",
                                  "country": "KR",
                                  "language": "ko",
                                  "crawlPolicy": {
                                    "robotsMode": "respect",
                                    "maxArticlesPerRun": 50,
                                    "fullTextAllowed": true
                                  },
                                  "reliabilityScore": 0.9
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("COMMON201"))
                .andExpect(jsonPath("$.message").value("등록되었습니다."))
                .andExpect(jsonPath("$.result.id").value(7))
                .andExpect(jsonPath("$.result.urlTemplate").value("NAVER"))
                .andExpect(jsonPath("$.result.crawlPolicy.maxArticlesPerRun").value(50))
                .andExpect(jsonPath("$.result.robotsStatus").value("unknown"))
                .andExpect(jsonPath("$.result.robotsCheckedAt").value(nullValue()));
    }

    @Test
    void createSourceRespondsWithInvalidUrlTemplateCode() throws Exception {
        when(sourceCommandService.createSource(any(SourceReqDTO.Create.class)))
                .thenThrow(new SourceException(SourceErrorCode.INVALID_SEARCH_URL_TEMPLATE));

        mockMvc.perform(post("/api/news/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceKind": "SEARCH",
                                  "name": "네이버",
                                  "urlTemplate": "네이버"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("SOURCE400"))
                .andExpect(jsonPath("$.message")
                        .value("SEARCH 소스의 URL 템플릿은 provider 키(NAVER, TAVILY, SERPAPI) 중 하나여야 합니다."));
    }

    @Test
    void getSourcesRespondsWithPagedEnvelope() throws Exception {
        PageResponse<SourceResDTO.Summary> page = PageResponse.of(List.of(summary()), 0, 20, 1L);
        when(sourceQueryService.getSources(eq("FEED"), eq(true), eq(null), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/api/news/sources").param("sourceKind", "FEED").param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].name").value("ETNews 반도체"))
                .andExpect(jsonPath("$.result.content[0].linkedTopicCount").value(3))
                .andExpect(jsonPath("$.result.totalElements").value(1))
                .andExpect(jsonPath("$.result.hasNext").value(false));
    }

    @Test
    void getSourceRespondsWithLatestCollectionState() throws Exception {
        when(sourceQueryService.getSource(1L)).thenReturn(SourceResDTO.Detail.builder()
                .id(1L)
                .sourceKind(Source.KIND_FEED)
                .name("ETNews 반도체")
                .urlTemplate("https://rss.etnews.com/Section902.xml")
                .country("KR")
                .language("ko")
                .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .active(true)
                .linkedTopics(List.of())
                .lastCollectedAt(OffsetDateTime.of(2026, 8, 18, 14, 5, 7, 0, ZoneOffset.ofHours(9)))
                .lastRunStatus("SUCCESS")
                .build());

        mockMvc.perform(get("/api/news/sources/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.lastCollectedAt").value("2026-08-18T14:05:07+09:00"))
                .andExpect(jsonPath("$.result.lastRunStatus").value("SUCCESS"));
    }

    @Test
    void getSourceRespondsWithSourceNotFoundCode() throws Exception {
        when(sourceQueryService.getSource(99L)).thenThrow(new SourceException(SourceErrorCode.SOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/news/sources/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("SOURCE404"))
                .andExpect(jsonPath("$.message").value("수집 소스를 찾을 수 없습니다."));
    }

    @Test
    void updateSourceRespondsWithUpdatedEnvelope() throws Exception {
        when(sourceCommandService.updateSource(eq(1L), any(SourceReqDTO.Update.class)))
                .thenReturn(SourceResDTO.Updated.builder()
                        .id(1L)
                        .sourceKind(Source.KIND_FEED)
                        .name("ETNews 반도체 (개편)")
                        .urlTemplate("https://rss.etnews.com/Section902.xml")
                        .country("KR")
                        .language("ko")
                        .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 20, false))
                        .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                        .reliabilityScore(new BigDecimal("0.85"))
                        .active(false)
                        .build());

        mockMvc.perform(patch("/api/news/sources/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "ETNews 반도체 (개편)",
                                  "crawlPolicy": {
                                    "robotsMode": "respect",
                                    "maxArticlesPerRun": 20,
                                    "fullTextAllowed": false
                                  },
                                  "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("수정되었습니다."))
                .andExpect(jsonPath("$.result.name").value("ETNews 반도체 (개편)"))
                .andExpect(jsonPath("$.result.crawlPolicy.fullTextAllowed").value(false))
                .andExpect(jsonPath("$.result.active").value(false));
    }

    @Test
    void deleteSourceRespondsWithDeactivatedEnvelope() throws Exception {
        when(sourceCommandService.deleteSource(1L)).thenReturn(SourceResDTO.Deleted.builder()
                .id(1L)
                .active(false)
                .deletedAt(OffsetDateTime.of(2026, 8, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9)))
                .build());

        mockMvc.perform(delete("/api/news/sources/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.message").value("삭제되었습니다."))
                .andExpect(jsonPath("$.result.id").value(1))
                .andExpect(jsonPath("$.result.active").value(false))
                .andExpect(jsonPath("$.result.deletedAt").value("2026-08-10T10:00:00+09:00"));
    }

    @Test
    void deleteSourceRespondsWithLinkedTopicIdsOnConflict() throws Exception {
        when(sourceCommandService.deleteSource(1L)).thenThrow(new SourceException(
                SourceErrorCode.SOURCE_LINKED_TO_TOPIC, Map.of("linkedTopicIds", List.of(1L, 2L))));

        mockMvc.perform(delete("/api/news/sources/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("SOURCE409"))
                .andExpect(jsonPath("$.message").value("주제에 연결된 소스는 삭제할 수 없습니다. 연결을 먼저 해제해 주세요."))
                .andExpect(jsonPath("$.result.linkedTopicIds[0]").value(1))
                .andExpect(jsonPath("$.result.linkedTopicIds[1]").value(2));
    }

    private SourceResDTO.Created created() {
        return SourceResDTO.Created.builder()
                .id(7L)
                .sourceKind(Source.KIND_SEARCH)
                .name("Naver 뉴스 검색")
                .urlTemplate("NAVER")
                .country("KR")
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 50, true))
                .robotsStatus(Source.ROBOTS_STATUS_UNKNOWN)
                .reliabilityScore(new BigDecimal("0.9"))
                .active(true)
                .build();
    }

    private SourceResDTO.Summary summary() {
        return SourceResDTO.Summary.builder()
                .id(1L)
                .sourceKind(Source.KIND_FEED)
                .name("ETNews 반도체")
                .urlTemplate("https://rss.etnews.com/Section902.xml")
                .country("KR")
                .language("ko")
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_RESPECT, 30, true))
                .robotsStatus(Source.ROBOTS_STATUS_ALLOWED)
                .reliabilityScore(new BigDecimal("0.85"))
                .active(true)
                .linkedTopicCount(3)
                .build();
    }
}
