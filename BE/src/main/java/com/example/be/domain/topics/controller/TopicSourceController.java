package com.example.be.domain.topics.controller;

import com.example.be.domain.topics.dto.res.TopicSourceResDTO;
import com.example.be.domain.topics.service.query.TopicSourceQueryService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/topic-sources")
@Tag(name = "수집 조합", description = "주제 × 소스 조합 조회 API")
public class TopicSourceController {

    private final TopicSourceQueryService topicSourceQueryService;

    @GetMapping
    @Operation(
            summary = "등록된 수집 조합 목록 조회",
            description = """
                    설정 화면의 "등록된 수집 주제" 테이블을 그리는 전용 조회 API입니다.
                    한 행 = (주제 × 소스) 조합 1건입니다. 주제 목록과 소스 목록을 따로 받아 프론트에서 조인하지 않도록
                    서버가 펼쳐서 내려줍니다.
                    조합의 active는 주제와 소스가 모두 활성일 때만 true입니다.
                    lastCollectedCount는 수집 실행 이력이 생기는 M3까지 null입니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "성공입니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON200",
                              "message": "성공입니다.",
                              "result": {
                                "content": [
                                  {
                                    "topicId": 1,
                                    "topicName": "HBM",
                                    "sourceId": 2,
                                    "sourceName": "Google News RSS",
                                    "sourceKind": "SEARCH",
                                    "queryText": "HBM 반도체",
                                    "batchSize": 100,
                                    "intervalMinutes": 60,
                                    "active": true,
                                    "lastCollectedAt": "2026-08-10T08:00:00+09:00",
                                    "lastCollectedCount": null
                                  }
                                ],
                                "page": 0,
                                "size": 20,
                                "totalElements": 1,
                                "totalPages": 1,
                                "hasNext": false,
                                "combinationCount": 1
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "존재하지 않는 주제를 필터로 준 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC404",
                              "message": "수집 주제를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicSourceResDTO.CombinationPage> getCombinations(
            // 선택 필터에는 example을 두지 않는다. Swagger UI가 example을 "Try it out" 폼에 미리 채워 넣어서
            // 비워둔 줄 알았던 필터가 그대로 적용된다.
            @Parameter(description = "특정 주제의 조합만 조회. 생략하면 전체")
            @RequestParam(required = false) Long topicId,

            @Parameter(description = "특정 소스의 조합만 조회. 생략하면 전체")
            @RequestParam(required = false) Long sourceId,

            @Parameter(description = "주제와 소스가 모두 활성인 조합만 조회. 생략하면 전체")
            @RequestParam(required = false) Boolean active,

            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK,
                topicSourceQueryService.getCombinations(topicId, sourceId, active, page, size));
    }
}
