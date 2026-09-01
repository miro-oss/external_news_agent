package com.example.be.domain.articles.controller;

import com.example.be.domain.articles.dto.res.ArticleResDTO;
import com.example.be.domain.articles.service.ArticleQueryService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/articles")
@Tag(name = "기사", description = "수집 기사 목록·상세 조회 API")
public class ArticleController {

    private final ArticleQueryService articleQueryService;

    @GetMapping
    @Operation(summary = "수집 기사 목록 조회", description = "본문 없이 요약과 분류 결과를 페이징 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "지원하지 않는 필터·정렬 조건",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"COMMON400","message":"지원하지 않는 정렬 조건입니다.","result":{}}
                            """)))
    })
    public ApiResponse<PageResponse<ArticleResDTO.Summary>> getArticles(
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) Long topicId,
            @RequestParam(required = false) Long sourceId,
            @RequestParam(required = false) String changeType,
            @RequestParam(required = false) String relevance,
            @Parameter(description = "low / medium / high")
            @RequestParam(required = false) String sensitivityLevel,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String language,
            @Parameter(description = "CHIP_MAKER / EQUIPMENT_MAKER / MARKET_INVESTOR / IT_INFRA")
            @RequestParam(required = false) String audience,
            @Parameter(description = "none / low / medium / high. audience 지정 시 기본 medium")
            @RequestParam(required = false) String minAudienceRelevance,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @RequestParam(required = false) OffsetDateTime from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @RequestParam(required = false) OffsetDateTime to,
            @Parameter(description = "PUBLISHED_DESC / PUBLISHED_ASC / SENSITIVITY_DESC")
            @RequestParam(defaultValue = "PUBLISHED_DESC") String sort,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK,
                articleQueryService.getArticles(runId, topicId, sourceId, changeType, relevance, sensitivityLevel,
                        category, language, audience, minAudienceRelevance,
                        from, to, sort, page, size));
    }

    @GetMapping("/{articleId}")
    @Operation(summary = "수집 기사 상세 조회", description = "기사 전문과 구조화된 최신 분석 결과를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "기사가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"ARTICLE404","message":"기사를 찾을 수 없습니다.","result":{}}
                            """)))
    })
    public ApiResponse<ArticleResDTO.Detail> getArticle(
            @Parameter(description = "기사 ID") @PathVariable Long articleId,
            @Parameter(description = "특정 실행의 분석 결과. 생략하면 최신")
            @RequestParam(required = false) Long runId
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, articleQueryService.getArticle(articleId, runId));
    }
}
