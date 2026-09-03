package com.example.be.domain.topics.controller;

import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;
import com.example.be.domain.topics.service.command.TopicKeywordProposalCommandService;
import com.example.be.domain.topics.service.query.TopicKeywordProposalQueryService;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/topics/keyword-proposals")
@Tag(name = "주제 키워드 제안", description = "수집 전략가가 만든 키워드 제안 조회·승인·반려 API")
public class TopicKeywordProposalController {

    private final TopicKeywordProposalQueryService queryService;
    private final TopicKeywordProposalCommandService commandService;

    @GetMapping
    @Operation(
            summary = "키워드 제안 목록 조회",
            description = """
                    수집 전략가가 만든 topic 키워드 제안을 조회합니다.
                    status를 생략하면 전체를, PENDING을 주면 검토 대기 제안만 반환합니다.
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
                                    "topicId": 3,
                                    "topicName": "HBM",
                                    "collectionRunId": 148,
                                    "status": "PENDING",
                                    "summary": "HBM4와 경쟁사 확장 키워드를 추가하고 잡음 키워드를 정리합니다.",
                                    "reviewedAt": null,
                                    "createdAt": "2026-09-03T10:15:00+09:00",
                                    "currentKeywords": {
                                      "requiredKeywords": ["HBM"],
                                      "optionalKeywords": ["SK하이닉스", "삼성전자"],
                                      "excludedKeywords": ["광고"]
                                    },
                                    "changes": [
                                      {
                                        "bucket": "OPTIONAL",
                                        "action": "ADD",
                                        "keyword": "HBM4",
                                        "reason": "이번 주기 신규 기사에서 반복 등장했습니다."
                                      }
                                    ]
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
                    description = "상태값 또는 페이지가 잘못된 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "COMMON400",
                              "message": "status는 PENDING / APPROVED / REJECTED 중 하나여야 합니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<PageResponse<TopicKeywordProposalResDTO.Item>> getKeywordProposals(
            @Parameter(description = "검토 상태 필터. PENDING / APPROVED / REJECTED, 생략하면 전체")
            @RequestParam(required = false) String status,

            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, queryService.getKeywordProposals(status, page, size));
    }

    @PostMapping("/{proposalId}/approve")
    @Operation(
            summary = "키워드 제안 승인",
            description = """
                    검토 대기 상태의 키워드 제안을 승인하고 현재 topic 키워드에 반영합니다.
                    승인 전까지는 실제 topic 키워드가 절대 변경되지 않습니다.
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
                                "topicId": 3,
                                "topicName": "HBM",
                                "collectionRunId": 148,
                                "status": "APPROVED",
                                "summary": "HBM4와 경쟁사 확장 키워드를 추가하고 잡음 키워드를 정리합니다.",
                                "reviewedAt": "2026-09-03T11:20:00+09:00",
                                "createdAt": "2026-09-03T10:15:00+09:00",
                                "currentKeywords": {
                                  "requiredKeywords": ["HBM"],
                                  "optionalKeywords": ["SK하이닉스", "삼성전자", "HBM4"],
                                  "excludedKeywords": ["광고"]
                                },
                                "changes": [
                                  {
                                    "bucket": "OPTIONAL",
                                    "action": "ADD",
                                    "keyword": "HBM4",
                                    "reason": "이번 주기 신규 기사에서 반복 등장했습니다."
                                  }
                                ]
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "제안이 없는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC404",
                              "message": "키워드 제안을 찾을 수 없습니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 검토가 끝난 제안인 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC409",
                              "message": "이미 검토가 끝난 키워드 제안입니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicKeywordProposalResDTO.Item> approve(
            @Parameter(description = "키워드 제안 ID")
            @PathVariable Long proposalId
    ) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, commandService.approve(proposalId));
    }

    @PostMapping("/{proposalId}/reject")
    @Operation(
            summary = "키워드 제안 반려",
            description = "검토 대기 상태의 키워드 제안을 반려하고 topic 키워드는 그대로 유지합니다."
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
                                "topicId": 3,
                                "topicName": "HBM",
                                "collectionRunId": 148,
                                "status": "REJECTED",
                                "summary": "HBM4와 경쟁사 확장 키워드를 추가하고 잡음 키워드를 정리합니다.",
                                "reviewedAt": "2026-09-03T11:20:00+09:00",
                                "createdAt": "2026-09-03T10:15:00+09:00",
                                "currentKeywords": {
                                  "requiredKeywords": ["HBM"],
                                  "optionalKeywords": ["SK하이닉스", "삼성전자"],
                                  "excludedKeywords": ["광고"]
                                },
                                "changes": [
                                  {
                                    "bucket": "OPTIONAL",
                                    "action": "ADD",
                                    "keyword": "HBM4",
                                    "reason": "이번 주기 신규 기사에서 반복 등장했습니다."
                                  }
                                ]
                              }
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "제안이 없는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC404",
                              "message": "키워드 제안을 찾을 수 없습니다.",
                              "result": {}
                            }
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "이미 검토가 끝난 제안인 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {
                              "isSuccess": false,
                              "code": "TOPIC409",
                              "message": "이미 검토가 끝난 키워드 제안입니다.",
                              "result": {}
                            }
                            """)))
    })
    public ApiResponse<TopicKeywordProposalResDTO.Item> reject(
            @Parameter(description = "키워드 제안 ID")
            @PathVariable Long proposalId
    ) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, commandService.reject(proposalId));
    }
}
