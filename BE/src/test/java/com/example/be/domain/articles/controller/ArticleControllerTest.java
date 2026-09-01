package com.example.be.domain.articles.controller;

import com.example.be.domain.articles.dto.res.ArticleResDTO;
import com.example.be.domain.articles.exception.ArticleException;
import com.example.be.domain.articles.exception.code.ArticleErrorCode;
import com.example.be.domain.articles.service.ArticleQueryService;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ArticleController.class)
class ArticleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArticleQueryService articleQueryService;

    @Test
    void getArticlesRespondsWithPagedAnalysis() throws Exception {
        when(articleQueryService.getArticles(eq(42L), eq(null), eq(null), eq("NEW"), eq(null), eq("high"),
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq("PUBLISHED_DESC"), eq(0), eq(20)))
                .thenReturn(PageResponse.of(List.of(summary()), 0, 20, 1));

        mockMvc.perform(get("/api/news/articles")
                        .param("runId", "42")
                        .param("changeType", "NEW")
                        .param("riskLevel", "high"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.content[0].id").value(1025))
                .andExpect(jsonPath("$.result.content[0].summary")
                        .value("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다."))
                .andExpect(jsonPath("$.result.content[0].riskLevel").value("high"))
                .andExpect(jsonPath("$.result.content[0].perspectiveTags[0].audience")
                        .value("CHIP_MAKER"));
    }

    @Test
    void getArticleRespondsWithBodySectionsAndAnalysis() throws Exception {
        when(articleQueryService.getArticle(1024L, 42L)).thenReturn(detail());

        mockMvc.perform(get("/api/news/articles/1024").param("runId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.sentences[0].index").value(0))
                .andExpect(jsonPath("$.result.analysis.keyPoints[0].evidence[0]").value(0))
                .andExpect(jsonPath("$.result.analysis.keyPoints[0].groundingReason")
                        .value("문장에서 직접 확인됩니다."))
                .andExpect(jsonPath("$.result.analysis.keyPoints[0].claimType").value("FACT"))
                .andExpect(jsonPath("$.result.analysis.keyPoints[0].attributedTo").isEmpty())
                .andExpect(jsonPath("$.result.analysis.perspectiveTags[0].evidenceSentenceIds[0]")
                        .value(0))
                .andExpect(jsonPath("$.result.analysis.runId").value(42))
                .andExpect(jsonPath("$.result.analysisArticleId").value(1024))
                .andExpect(jsonPath("$.result.issueId").value(88));
    }

    @Test
    void forwardsAudienceFilterWithDefaultMinimum() throws Exception {
        when(articleQueryService.getArticles(any(), any(), any(), any(), any(), any(), any(), any(),
                eq("IT_INFRA"), eq(null), any(), any(), eq("PUBLISHED_DESC"), eq(0), eq(20)))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/news/articles").param("audience", "IT_INFRA"))
                .andExpect(status().isOk());
    }

    @Test
    void getArticleRespondsWithArticleNotFoundCode() throws Exception {
        when(articleQueryService.getArticle(99L, null))
                .thenThrow(new ArticleException(ArticleErrorCode.ARTICLE_NOT_FOUND));

        mockMvc.perform(get("/api/news/articles/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE404"))
                .andExpect(jsonPath("$.message").value("기사를 찾을 수 없습니다."));
    }

    @Test
    void getArticlesKeepsSpecifiedBadRequestMessage() throws Exception {
        when(articleQueryService.getArticles(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), eq("UNKNOWN"), eq(0), eq(20)))
                .thenThrow(new GeneralException(GeneralErrorCode.BAD_REQUEST, "지원하지 않는 정렬 조건입니다."));

        mockMvc.perform(get("/api/news/articles").param("sort", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 정렬 조건입니다."));
    }

    private ArticleResDTO.Summary summary() {
        return ArticleResDTO.Summary.builder()
                .id(1025L)
                .title("US tightens export controls")
                .summary("미국의 첨단 반도체 장비 수출 통제 강화와 관련된 소식이 보도됐다.")
                .riskLevel("high")
                .perspectiveTags(List.of(perspectiveTag()))
                .build();
    }

    private ArticleResDTO.Detail detail() {
        return ArticleResDTO.Detail.builder()
                .id(1024L)
                .title("SK하이닉스, HBM4 양산 일정 앞당겨")
                .bodyText("SK하이닉스가 HBM4 양산 일정을 앞당겼다.")
                .sentences(List.of(ArticleResDTO.Sentence.builder()
                        .index(0)
                        .text("SK하이닉스가 HBM4 양산 일정을 앞당겼다.")
                        .build()))
                .analysis(ArticleResDTO.Analysis.builder()
                        .changeType("NEW")
                        .summary("SK하이닉스가 HBM4 양산 일정을 앞당겼다.")
                        .keyPoints(List.of(ArticleResDTO.KeyPoint.builder()
                                .text("HBM4 양산 일정이 앞당겨졌다.")
                                .evidence(List.of(0))
                                .groundedness("grounded")
                                .groundingReason("문장에서 직접 확인됩니다.")
                                .claimType("FACT")
                                .attributedTo(null)
                                .build()))
                        .perspectiveTags(List.of(perspectiveTag()))
                        .runId(42L)
                        .build())
                .analysisArticleId(1024L)
                .issueId(88L)
                .relatedArticles(List.of())
                .build();
    }

    private ArticleResDTO.PerspectiveTag perspectiveTag() {
        return ArticleResDTO.PerspectiveTag.builder()
                .audience("CHIP_MAKER")
                .relevance("high")
                .hook("HBM4 양산 일정이 앞당겨졌다.")
                .evidenceSentenceIds(List.of(0))
                .build();
    }
}
