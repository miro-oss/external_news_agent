package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.comparison.ReportChanges;
import com.example.be.domain.reports.comparison.ReportChangesQueryService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/reports")
@Tag(name = "보고서")
public class ReportChangesController {
    private final ReportChangesQueryService comparisons;

    @GetMapping("/{reportId}/changes")
    @Operation(summary = "보고서 변화 비교 조회", description = "완료된 RUN·DAILY·전체/주제별 WEEKLY와 생성 시 고정한 동일 주제의 이전 보고서 변화를 조회합니다. periods에 실제 양쪽 기간, keywordChanges에 조건이 검증된 고유 이슈 수·비중 차이(%p)를 제공합니다. 조건 변경·입력 없음은 수치로 추정하지 않으며 조회는 LLM을 호출하지 않습니다. 과거 저장 자료가 없으면 UNAVAILABLE입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"reportId":101,"reportDate":"2026-09-10","baseReportId":100,"baseReportDate":"2026-09-09","status":"PENDING","message":"보고서 변화 비교를 준비하고 있습니다.","scopeChanged":false,"notes":[],"items":[],"periods":{"basis":"DAILY","previousLabel":"2026-09-09","currentLabel":"2026-09-10"},"keywordChanges":{"status":"UNAVAILABLE","reason":"COMPARISON_NOT_READY","message":"변화 비교가 준비되면 키워드 변화를 확인할 수 있습니다.","previousDenominatorIssueCount":null,"currentDenominatorIssueCount":null,"lowSample":false,"items":[]}}}
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "보고서가 없거나 삭제되었거나 생성 중인 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"REPORT404","message":"보고서를 찾을 수 없습니다.","result":{}}
                            """)))
    })
    public ApiResponse<ReportChanges> changes(@Parameter(description = "보고서 ID") @PathVariable Long reportId) {
        return ApiResponse.of(GeneralSuccessCode.OK, comparisons.get(reportId));
    }
}
