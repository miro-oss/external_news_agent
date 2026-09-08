package com.example.be.domain.collection.controller;

import com.example.be.domain.collection.dto.req.CollectionRunReqDTO;
import com.example.be.domain.collection.dto.res.CollectionRunResDTO;
import com.example.be.domain.collection.service.command.CollectionRunCommandService;
import com.example.be.domain.collection.service.command.CollectionRunStartResult;
import com.example.be.domain.collection.service.query.CollectionRunQueryService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/runs")
@Tag(name = "수집 실행", description = "수집실행(runs) 수동 실행·내역·상세 조회 API")
public class CollectionRunController {

    private final CollectionRunCommandService runCommandService;
    private final CollectionRunQueryService runQueryService;

    @PostMapping
    @Operation(
            summary = "수동 수집 실행",
            description = """
                    수집 요청을 대기열에 저장합니다. topicIds를 주면 해당 주제만, 생략하면 활성화된 모든 주제를 대상으로 실행합니다.
                    응답은 PENDING으로 즉시 반환됩니다. 같은 주제는 순서대로, 다른 주제는 실행 상한 안에서 병렬 처리됩니다. 진행 상황은 수집 실행 상세 조회로 폴링합니다.
                    delivery를 생략하면 기존 주제별 자동 전달 정책을 유지합니다. ONCE는 이번 실행만, TOPIC은 실제 실행 대상 주제의 이후 정책까지 적용합니다.
                    전달 설정과 그룹을 펼친 수신 대상·주소는 실행 접수와 같은 트랜잭션에 저장합니다. 같은 idempotencyKey로 기존 실행을 반환할 때 전달 설정을 변경하지 않습니다.
                    delivery.enabled=false는 해당 실행의 자동 전달을 막으며 TOPIC이면 선택된 주제 정책도 끕니다. daily=true는 같은 날 다른 주제도 포함하는 일일 통합 보고서 전달입니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "수집 요청을 접수했습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON201",
                              "message": "수집 요청을 접수했습니다.",
                              "result": {
                                "runId": 42,
                                "status": "PENDING",
                                "triggerType": "MANUAL",
                                "idempotencyKey": "2026-08-10-manual-001",
                                "targetTopicIds": [1, 2],
                                "targetCombinationCount": 6,
                                "queuedAt": "2026-08-10T10:00:00+09:00"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "같은 idempotencyKey로 이미 실행 중인 run이 있는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": true,
                              "code": "COMMON200",
                              "message": "이미 진행 중인 수집입니다.",
                              "result": {
                                "runId": 42,
                                "status": "PENDING",
                                "triggerType": "MANUAL",
                                "idempotencyKey": "2026-08-10-manual-001",
                                "queuedAt": "2026-08-10T10:00:00+09:00"
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "대상 조합이 없거나 전달 설정이 올바르지 않은 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "실행 대상 없음", value = """
                            {
                              "isSuccess": false,
                              "code": "RUN400",
                              "message": "실행할 수집 조합이 없습니다. 주제에 소스를 연결해 주세요.",
                              "result": {}
                            }
                            """),
                            @ExampleObject(name = "연결된 전달 대상 없음", value = """
                            {
                              "isSuccess": false,
                              "code": "COMMON400",
                              "message": "선택한 대상에 연결된 수신 채널이 없습니다.",
                              "result": {}
                            }
                            """)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "선택한 그룹·수신자·채널을 찾을 수 없거나 그룹·채널이 비활성인 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "GROUP404",
                              "message": "수신 그룹을 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ResponseEntity<ApiResponse<CollectionRunResDTO.Created>> startRun(
            @RequestBody(required = false) CollectionRunReqDTO.Create request
    ) {
        CollectionRunStartResult result = runCommandService.startManualRun(request);
        return ResponseEntity
                .status(result.successCode().getStatus())
                .body(ApiResponse.of(result.successCode(), result.response()));
    }

    @GetMapping
    @Operation(
            summary = "수집 실행 내역 조회",
            description = "수집 실행 이력을 접수 시각 최신순으로 조회합니다. 수동 실행과 스케줄 실행이 모두 포함됩니다."
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
                                    "runId": 42,
                                    "status": "SUCCESS",
                                    "triggerType": "MANUAL",
                                    "queuedAt": "2026-08-10T10:00:00+09:00",
                                    "startedAt": "2026-08-10T10:00:00+09:00",
                                    "finishedAt": "2026-08-10T10:03:12+09:00",
                                    "scannedCount": 128,
                                    "newCount": 14,
                                    "updatedCount": 3,
                                    "skippedCount": 111,
                                    "warningCount": 1,
                                    "reportId": 17
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
                    description = "기간 조건이 잘못된 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "COMMON400",
                              "message": "from은 to보다 이전이어야 합니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<PageResponse<CollectionRunResDTO.Summary>> getRuns(
            @Parameter(description = "실행 상태(PENDING / RUNNING / SUCCESS / PARTIAL / FAILED). 생략하면 전체")
            @RequestParam(required = false) String status,

            @Parameter(description = "실행 트리거(MANUAL / SCHEDULED). 생략하면 전체")
            @RequestParam(required = false) String triggerType,

            @Parameter(description = "해당 주제를 포함한 실행만 조회. 생략하면 전체")
            @RequestParam(required = false) Long topicId,

            @Parameter(description = "시작 시각 하한. ISO-8601(예: 2026-08-10T00:00:00+09:00). 생략하면 전체")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) OffsetDateTime from,

            @Parameter(description = "시작 시각 상한. ISO-8601(예: 2026-08-11T00:00:00+09:00). 생략하면 전체")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) OffsetDateTime to,

            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK,
                runQueryService.getRuns(status, triggerType, topicId, from, to, page, size));
    }

    @GetMapping("/{runId}")
    @Operation(
            summary = "수집 실행 상세 조회",
            description = "수집 실행 1건의 대기·진행 상황과 결과를 조회합니다. queuedAt은 접수 시각이며 PENDING의 startedAt은 null입니다. 단계별 coverage, 조합별 breakdown과 warnings를 포함합니다."
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
                                "runId": 42,
                                "status": "PARTIAL",
                                "triggerType": "MANUAL",
                                "idempotencyKey": "2026-08-10-manual-001",
                                "queuedAt": "2026-08-10T10:00:00+09:00",
                                "startedAt": "2026-08-10T10:00:00+09:00",
                                "finishedAt": "2026-08-10T10:03:12+09:00",
                                "scannedCount": 128,
                                "newCount": 14,
                                "updatedCount": 3,
                                "skippedCount": 111,
                                "reportId": 17,
                                "coverage": {
                                  "observedArticleCount": 32,
                                  "issueAssignedArticleCount": 32,
                                  "issueAssignmentRate": 1.0,
                                  "issueCount": 32,
                                  "analysisTargetIssueCount": 30,
                                  "llmAnalyzedIssueCount": 30,
                                  "llmAnalysisRate": 0.9375,
                                  "reportReflectedIssueCount": 28,
                                  "reportExcludedIssueCount": 2,
                                  "reportCoverageRate": 1.0,
                                  "issueLimitPerRun": 30
                                },
                                "breakdown": [],
                                "warnings": []
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "실행 이력이 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "RUN404",
                              "message": "수집 실행 이력을 찾을 수 없습니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<CollectionRunResDTO.Detail> getRun(
            @Parameter(description = "수집 실행 ID")
            @PathVariable Long runId
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, runQueryService.getRun(runId));
    }
}
