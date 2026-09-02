package com.example.be.domain.insights.controller;

import com.example.be.domain.insights.dto.InsightDTO;
import com.example.be.domain.insights.service.InsightService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/news/insights")
@RequiredArgsConstructor
@Tag(name = "Insights", description = "이슈 관점 인사이트 API")
public class InsightController {

    private final InsightService service;

    @PostMapping
    @Operation(summary = "관점 인사이트 생성", description = "동일 입력과 promptVersion이면 캐시를 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "신규 생성 또는 동일 입력 캐시 반환",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"cached":false,"targetType":"ISSUE","targetId":88,"inputHash":"sha256","promptVersion":"insight.ko.v1+perspective.ko.v1","insights":[{"audience":"CHIP_MAKER","headline":"양산 일정 변화","facts":[],"implications":[],"watchNext":[],"confidence":0.8,"llmProvider":"gemini","llmModel":"configured-model","createdAt":"2026-09-02T14:00:00+09:00"}]}}
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "target 또는 audience 입력값 검증 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {"isSuccess":false,"code":"AUDIENCE400","message":"지원하지 않는 관점입니다.","result":{}}
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "이슈가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {"isSuccess":false,"code":"ISSUE404","message":"이슈를 찾을 수 없습니다.","result":{}}
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "인사이트 입력용 Agent finding이 없는 경우"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = "LLM 사용 한도 소진",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {"isSuccess":false,"code":"QUOTA429","message":"LLM 사용 한도가 소진되었습니다.","result":{"plan":"PAID"}}
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500", description = "Agent 호출 또는 응답 계약 실패")
    })
    public ApiResponse<InsightDTO.Result> create(@RequestBody InsightDTO.CreateRequest request) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.create(request));
    }

    @GetMapping
    @Operation(summary = "최근 관점 인사이트 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "target 또는 audience 입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "저장된 관점 인사이트가 없는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {"isSuccess":false,"code":"COMMON404","message":"저장된 관점 인사이트가 없습니다.","result":{}}
                                    """)))
    })
    public ApiResponse<InsightDTO.Result> get(
            @Parameter(description = "현재 ISSUE만 지원") @RequestParam String targetType,
            @Parameter(description = "이슈 ID") @RequestParam Long targetId,
            @Parameter(description = "CHIP_MAKER / EQUIPMENT_MAKER / MARKET_INVESTOR / IT_INFRA")
            @RequestParam String audience) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.get(targetType, targetId, audience));
    }
}
