package com.example.be.domain.sources.controller;

import com.example.be.domain.sources.dto.req.SourceReqDTO;
import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.domain.sources.service.command.SourceCommandService;
import com.example.be.domain.sources.service.query.SourceQueryService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/sources")
@Tag(name = "수집 소스", description = "수집 소스(sources) 등록·조회·수정·삭제 API")
public class SourceController {

    private final SourceCommandService sourceCommandService;
    private final SourceQueryService sourceQueryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "수집 소스 등록",
            description = """
                    새 수집 소스를 등록합니다. 설정 화면의 소스 등록 폼에 대응합니다.
                    같은 소스 종류에서 같은 URL 템플릿은 중복 등록할 수 없습니다.
                    FEED는 고정 http/https URL이고, SEARCH는 provider 키(NAVER/TAVILY/SERPAPI) 중 하나입니다.
                    robotsStatus는 요청으로 넣을 수 없고 등록 직후에는 항상 unknown입니다.
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
                                "id": 7,
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
                                "robotsStatus": "unknown",
                                "robotsCheckedAt": null,
                                "reliabilityScore": 0.9,
                                "active": true
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "SEARCH 소스인데 urlTemplate이 provider 키가 아닌 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE400",
                              "message": "SEARCH 소스의 URL 템플릿은 provider 키(NAVER, TAVILY, SERPAPI) 중 하나여야 합니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "같은 종류에 같은 URL이 이미 등록된 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE409",
                              "message": "이미 등록된 수집 소스입니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<SourceResDTO.Created> createSource(@RequestBody SourceReqDTO.Create request) {
        return ApiResponse.of(GeneralSuccessCode.CREATED, sourceCommandService.createSource(request));
    }

    @GetMapping
    @Operation(
            summary = "수집 소스 목록 조회",
            description = """
                    등록된 수집 소스를 목록으로 조회합니다. 설정 화면의 소스 테이블을 그립니다.
                    FEED는 ETNews·EE Times 같은 고정 URL이고, SEARCH는 질의를 넣어야 결과가 나오는 소스입니다.
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
                                    "sourceKind": "FEED",
                                    "name": "ETNews 반도체",
                                    "urlTemplate": "https://rss.etnews.com/Section902.xml",
                                    "country": "KR",
                                    "language": "ko",
                                    "crawlPolicy": {
                                      "robotsMode": "respect",
                                      "maxArticlesPerRun": 30,
                                      "fullTextAllowed": true
                                    },
                                    "robotsStatus": "allowed",
                                    "robotsCheckedAt": "2026-08-10T09:00:00+09:00",
                                    "reliabilityScore": 0.85,
                                    "active": true,
                                    "linkedTopicCount": 3
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
    public ApiResponse<PageResponse<SourceResDTO.Summary>> getSources(
            // 선택 필터에는 example을 두지 않는다. Swagger UI가 example을 "Try it out" 폼에 미리 채워 넣어서
            // 비워둔 줄 알았던 필터가 그대로 적용된다.
            @Parameter(description = "소스 종류 필터(FEED / SEARCH). 생략하면 전체")
            @RequestParam(required = false) String sourceKind,

            @Parameter(description = "활성 여부 필터. 생략하면 전체")
            @RequestParam(required = false) Boolean active,

            @Parameter(description = "소스명 부분 일치 검색. 생략하면 전체")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK,
                sourceQueryService.getSources(sourceKind, active, keyword, page, size));
    }

    @GetMapping("/{sourceId}")
    @Operation(
            summary = "수집 소스 상세 조회",
            description = """
                    수집 소스 1건의 상세 정보를 조회합니다.
                    소스에 연결된 주제 목록과 최근 수집 상태를 함께 내려 소스 상태 점검 화면을 그립니다.
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
                                "id": 1,
                                "sourceKind": "FEED",
                                "name": "ETNews 반도체",
                                "urlTemplate": "https://rss.etnews.com/Section902.xml",
                                "country": "KR",
                                "language": "ko",
                                "crawlPolicy": {
                                  "robotsMode": "respect",
                                  "maxArticlesPerRun": 30,
                                  "fullTextAllowed": true
                                },
                                "robotsStatus": "allowed",
                                "robotsCheckedAt": "2026-08-10T09:00:00+09:00",
                                "reliabilityScore": 0.85,
                                "active": true,
                                "linkedTopics": [
                                  { "id": 1, "name": "HBM" },
                                  { "id": 2, "name": "DRAM" }
                                ],
                                "lastCollectedAt": "2026-08-10T08:00:00+09:00",
                                "lastRunStatus": "SUCCESS"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "소스가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE404",
                              "message": "수집 소스를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<SourceResDTO.Detail> getSource(
            @Parameter(description = "수집 소스 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long sourceId
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, sourceQueryService.getSource(sourceId));
    }

    @PatchMapping("/{sourceId}")
    @Operation(
            summary = "수집 소스 수정",
            description = """
                    수집 소스의 설정을 부분 수정합니다. 전달한 필드만 반영되고, 생략한 필드는 기존 값을 유지합니다.
                    crawlPolicy는 부분 병합이 아니라 통째로 교체됩니다.
                    robotsStatus와 robotsCheckedAt은 서버가 계산하는 값이라 요청으로 바꿀 수 없습니다.
                    소스 종류(sourceKind)도 바꿀 수 없습니다. 종류가 달라지면 새 소스로 등록합니다.
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
                                "sourceKind": "FEED",
                                "name": "ETNews 반도체 (개편)",
                                "urlTemplate": "https://rss.etnews.com/Section902.xml",
                                "country": "KR",
                                "language": "ko",
                                "crawlPolicy": {
                                  "robotsMode": "respect",
                                  "maxArticlesPerRun": 20,
                                  "fullTextAllowed": false
                                },
                                "robotsStatus": "allowed",
                                "robotsCheckedAt": "2026-08-10T09:00:00+09:00",
                                "reliabilityScore": 0.85,
                                "active": false
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "소스가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE404",
                              "message": "수집 소스를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "변경한 URL이 이미 다른 소스에 등록된 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE409",
                              "message": "이미 등록된 수집 소스입니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<SourceResDTO.Updated> updateSource(
            @Parameter(description = "수집 소스 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long sourceId,
            @RequestBody SourceReqDTO.Update request
    ) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, sourceCommandService.updateSource(sourceId, request));
    }

    @DeleteMapping("/{sourceId}")
    @Operation(
            summary = "수집 소스 삭제",
            description = """
                    수집 소스를 삭제합니다. 이미 수집된 기사가 소스를 참조하고 있으므로 기본 동작은 비활성화(soft delete)입니다.
                    기사 이력은 남고 이후 수집 대상에서만 제외됩니다. 목록·상세 조회에서는 계속 보입니다.
                    주제에 연결된 소스는 먼저 연결을 해제해야 합니다.
                    응답의 deletedAt은 비활성 시각이 아니라 삭제를 처리한 응답 시각입니다.
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
                                "active": false,
                                "deletedAt": "2026-08-10T10:00:00+09:00"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "소스가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE404",
                              "message": "수집 소스를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "주제에 연결되어 있는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE409",
                              "message": "주제에 연결된 소스는 삭제할 수 없습니다. 연결을 먼저 해제해 주세요.",
                              "result": {
                                "linkedTopicIds": [1, 2]
                              }
                            }
                            """)))
    })
    public ApiResponse<SourceResDTO.Deleted> deleteSource(
            @Parameter(description = "수집 소스 ID. 목록 조회로 확인한 실제 ID를 넣는다")
            @PathVariable Long sourceId
    ) {
        return ApiResponse.of(GeneralSuccessCode.DELETED, sourceCommandService.deleteSource(sourceId));
    }

    @PostMapping("/{sourceId}/robots-check")
    @Operation(
            summary = "robots.txt 정책 재확인",
            description = """
                    소스 URL의 robots.txt를 조회해 수집 허용 여부를 갱신합니다.
                    결과는 robots_status와 robots_checked_at에 저장되어 목록 화면에서 재계산 없이 표시됩니다.
                    수집 실행 전에 서버가 자동으로도 확인하지만, 소스 등록 직후나 정책이 바뀐 경우 수동 재확인에 씁니다.
                    robots.txt가 없으면(404) 제한이 없다는 뜻이라 allowed입니다.
                    조회 자체에 실패하면 상태를 unknown으로 저장한 뒤 SOURCE502로 응답합니다.
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
                                "sourceId": 1,
                                "robotsStatus": "allowed",
                                "robotsCheckedAt": "2026-08-10T10:05:00+09:00",
                                "crawlDelaySeconds": 5,
                                "robotsTxtUrl": "https://www.etnews.com/robots.txt"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "수집 소스를 찾을 수 없습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE404",
                              "message": "수집 소스를 찾을 수 없습니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502",
                    description = "robots.txt를 확인하지 못했습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "SOURCE502",
                              "message": "robots.txt를 확인하지 못했습니다.",
                              "result": {
                                "sourceId": 1,
                                "robotsStatus": "unknown",
                                "reason": "CONNECT_TIMEOUT"
                              }
                            }
                            """)))
    })
    public ApiResponse<SourceResDTO.RobotsChecked> checkRobots(
            @Parameter(description = "수집 소스 ID", example = "1")
            @PathVariable Long sourceId
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, sourceCommandService.checkRobots(sourceId));
    }
}
