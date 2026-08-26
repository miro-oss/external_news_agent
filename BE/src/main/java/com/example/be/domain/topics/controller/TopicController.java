package com.example.be.domain.topics.controller;

import com.example.be.domain.topics.dto.req.TopicReqDTO;
import com.example.be.domain.topics.dto.res.TopicResDTO;
import com.example.be.domain.topics.service.command.TopicCommandService;
import com.example.be.domain.topics.service.query.TopicQueryService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/topics")
@Tag(name = "수집 주제", description = "수집 주제(topics) 등록·조회·수정·삭제 API")
public class TopicController {

    private final TopicCommandService topicCommandService;
    private final TopicQueryService topicQueryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "수집 주제 등록",
            description = """
                    새 수집 주제를 등록합니다. 설정 화면의 주제 등록 폼에 대응합니다.
                    sourceIds를 함께 보내면 등록과 동시에 소스 연결까지 끝납니다.
                    주제명은 중복할 수 없고, SEARCH 소스를 연결하려면 queryText가 필요합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "등록되었습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON201",
                              "message": "등록되었습니다.",
                              "result": {
                                "id": 1,
                                "name": "HBM",
                                "queryText": "HBM 반도체",
                                "requiredKeywords": ["HBM"],
                                "optionalKeywords": ["SK하이닉스", "삼성전자", "마이크론"],
                                "excludedKeywords": ["광고", "채용"],
                                "batchSize": 20,
                                "intervalMinutes": 60,
                                "active": true,
                                "sources": [
                                  { "id": 1, "name": "ETNews 반도체", "sourceKind": "FEED" },
                                  { "id": 2, "name": "Google News RSS", "sourceKind": "SEARCH" }
                                ]
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "SEARCH 소스를 연결했지만 검색어가 없는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC400",
                              "message": "SEARCH 소스를 연결하려면 검색어가 필요합니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "sourceIds에 존재하지 않는 소스가 섞인 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE404",
                              "message": "수집 소스를 찾을 수 없습니다.",
                              "result": {
                                "notFoundSourceIds": [99]
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "주제명이 중복된 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC409",
                              "message": "이미 존재하는 주제명입니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicResDTO.Created> createTopic(@RequestBody TopicReqDTO.Create request) {
        return ApiResponse.of(GeneralSuccessCode.CREATED, topicCommandService.createTopic(request));
    }

    @GetMapping
    @Operation(
            summary = "수집 주제 목록 조회",
            description = """
                    등록된 수집 주제를 목록으로 조회합니다.
                    실제 수집은 주제에 연결된 소스와의 조합으로 실행되므로 연결된 소스 수를 함께 내려줍니다.
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
                                    "id": 1,
                                    "name": "HBM",
                                    "queryText": "HBM 반도체",
                                    "requiredKeywords": ["HBM"],
                                    "optionalKeywords": ["SK하이닉스", "삼성전자", "마이크론"],
                                    "excludedKeywords": ["광고", "채용"],
                                    "batchSize": 20,
                                    "intervalMinutes": 60,
                                    "active": true,
                                    "linkedSourceCount": 4,
                                    "lastCollectedAt": "2026-08-10T08:00:00+09:00"
                                  }
                                ],
                                "page": 0,
                                "size": 20,
                                "totalElements": 1,
                                "totalPages": 1,
                                "hasNext": false
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "페이지 크기가 허용 범위를 벗어난 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "COMMON400",
                              "message": "size는 1 이상 100 이하여야 합니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<PageResponse<TopicResDTO.Summary>> getTopics(
            // 선택 필터에는 example을 두지 않는다. Swagger UI가 example을 "Try it out" 폼에 미리 채워 넣어서
            // 비워둔 줄 알았던 필터가 그대로 적용된다.
            @Parameter(description = "활성 여부 필터. 생략하면 전체")
            @RequestParam(required = false) Boolean active,

            @Parameter(description = "주제명 부분 일치 검색. 생략하면 전체")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, topicQueryService.getTopics(active, keyword, page, size));
    }

    @GetMapping("/{topicId}")
    @Operation(
            summary = "수집 주제 상세 조회",
            description = "수집 주제 1건의 상세 정보와 연결된 소스 목록을 조회합니다. 주제 수정 화면 진입 시 호출합니다."
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
                                "id": 1,
                                "name": "HBM",
                                "queryText": "HBM 반도체",
                                "requiredKeywords": ["HBM"],
                                "optionalKeywords": ["SK하이닉스", "삼성전자", "마이크론"],
                                "excludedKeywords": ["광고", "채용"],
                                "batchSize": 20,
                                "intervalMinutes": 60,
                                "active": true,
                                "lastCollectedAt": "2026-08-10T08:00:00+09:00",
                                "sources": [
                                  {
                                    "id": 1,
                                    "name": "ETNews 반도체",
                                    "sourceKind": "FEED",
                                    "language": "ko",
                                    "robotsStatus": "allowed",
                                    "active": true
                                  }
                                ]
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "주제가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC404",
                              "message": "수집 주제를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicResDTO.Detail> getTopic(
            @Parameter(description = "수집 주제 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long topicId
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, topicQueryService.getTopic(topicId));
    }

    @PatchMapping("/{topicId}")
    @Operation(
            summary = "수집 주제 수정",
            description = """
                    수집 주제를 부분 수정합니다. 전달한 필드만 반영됩니다.
                    키워드 배열은 부분 병합이 아니라 전체 교체입니다. 빈 배열을 보내면 해당 필터가 사라집니다.
                    intervalMinutes를 바꾸면 스케줄러가 다음 실행부터 바뀐 주기를 적용합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "수정되었습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON200",
                              "message": "수정되었습니다.",
                              "result": {
                                "id": 1,
                                "name": "HBM",
                                "queryText": "HBM4 반도체",
                                "requiredKeywords": ["HBM"],
                                "optionalKeywords": ["SK하이닉스", "삼성전자", "마이크론"],
                                "excludedKeywords": ["광고", "채용", "주가"],
                                "batchSize": 30,
                                "intervalMinutes": 30,
                                "active": true
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "배치 건수나 주기가 허용 범위를 벗어난 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC400",
                              "message": "batchSize는 1 이상 100 이하, intervalMinutes는 10 이상이어야 합니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "주제가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC404",
                              "message": "수집 주제를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicResDTO.Updated> updateTopic(
            @Parameter(description = "수집 주제 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long topicId,
            @RequestBody TopicReqDTO.Update request
    ) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, topicCommandService.updateTopic(topicId, request));
    }

    @PatchMapping("/{topicId}/activation")
    @Operation(
            summary = "수집 주제 활성 토글",
            description = """
                    주제의 활성 상태만 바꿉니다. 설정 화면 테이블의 토글 스위치에 대응합니다.
                    비활성화하면 스케줄러가 다음 틱부터 해당 주제를 건너뜁니다. 진행 중인 수집은 끝까지 실행됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "수정되었습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON200",
                              "message": "수정되었습니다.",
                              "result": {
                                "id": 1,
                                "name": "HBM",
                                "active": false,
                                "nextScheduledAt": null
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "active 값이 누락된 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "COMMON400",
                              "message": "active 값은 필수입니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "주제가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC404",
                              "message": "수집 주제를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicResDTO.Activated> updateActivation(
            @Parameter(description = "수집 주제 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long topicId,
            @RequestBody TopicReqDTO.Activation request
    ) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, topicCommandService.updateActivation(topicId, request));
    }

    @PutMapping("/{topicId}/sources")
    @Operation(
            summary = "주제-소스 연결 설정",
            description = """
                    주제에 연결된 소스 목록을 전체 교체합니다. 주제와 소스는 M:N 관계이고, 이 API가 그 매핑 테이블을 재구성합니다.
                    전달한 목록에 없는 기존 연결은 제거됩니다. 빈 배열을 보내면 모든 연결이 해제됩니다.
                    소스 수 × 주제 수만큼 조합이 늘어나므로, 응답의 combinationCount를 화면에 표시해 조합 폭발을 인지할 수 있게 합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "연결되었습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON200",
                              "message": "연결되었습니다.",
                              "result": {
                                "topicId": 1,
                                "sources": [
                                  { "id": 1, "name": "ETNews 반도체", "sourceKind": "FEED" },
                                  { "id": 2, "name": "Google News RSS", "sourceKind": "SEARCH" },
                                  { "id": 5, "name": "EE Times", "sourceKind": "FEED" },
                                  { "id": 8, "name": "Naver 뉴스 검색", "sourceKind": "SEARCH" }
                                ],
                                "addedCount": 1,
                                "removedCount": 0,
                                "combinationCount": 4
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "SEARCH 소스를 연결했지만 주제에 검색어가 없는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC400",
                              "message": "SEARCH 소스를 연결하려면 검색어가 필요합니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "주제가 없거나(TOPIC404), 존재하지 않는 소스 ID가 포함된 경우(SOURCE404)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE404",
                              "message": "수집 소스를 찾을 수 없습니다.",
                              "result": {
                                "notFoundSourceIds": [99]
                              }
                            }
                            """)))
    })
    public ApiResponse<TopicResDTO.SourcesLinked> replaceSources(
            @Parameter(description = "수집 주제 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long topicId,
            @RequestBody TopicReqDTO.SourceLink request
    ) {
        return ApiResponse.of(GeneralSuccessCode.LINKED, topicCommandService.replaceSources(topicId, request));
    }

    @DeleteMapping("/{topicId}")
    @Operation(
            summary = "수집 주제 삭제",
            description = """
                    수집 주제를 삭제합니다. 주제-소스 연결은 함께 제거되고, 이미 수집된 기사와 보고서 이력은 유지됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "삭제되었습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON200",
                              "message": "삭제되었습니다.",
                              "result": {
                                "id": 1,
                                "deletedAt": "2026-08-10T10:00:00+09:00",
                                "unlinkedSourceCount": 3
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "주제가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC404",
                              "message": "수집 주제를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicResDTO.Deleted> deleteTopic(
            @Parameter(description = "수집 주제 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long topicId
    ) {
        return ApiResponse.of(GeneralSuccessCode.DELETED, topicCommandService.deleteTopic(topicId));
    }
}
