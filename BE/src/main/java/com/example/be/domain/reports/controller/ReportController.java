package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportQueryService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/reports")
@Tag(name = "보고서", description = "실행별·일일 통합 보고서 목록·최신·상세 조회 API")
public class ReportController {

    private final ReportQueryService reportQueryService;

    @GetMapping
    @Operation(summary = "보고서 목록 조회", description = "본문 없이 생성 시각과 집계값을 페이징 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "기간 또는 페이징 조건이 잘못된 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"COMMON400","message":"from은 to보다 이전이어야 합니다.","result":{}}
                            """)))
    })
    public ApiResponse<PageResponse<ReportResDTO.Summary>> getReports(
            @Parameter(description = "생성일 하한. ISO-8601 date 또는 datetime")
            @RequestParam(required = false) String from,
            @Parameter(description = "생성일 상한. ISO-8601 date 또는 datetime")
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size,
            @Parameter(description = "RUN 실행별 / DAILY 일일 통합. 생략하면 전체")
            @RequestParam(required = false) ReportScope reportScope
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, reportScope == null
                ? reportQueryService.getReports(from, to, page, size)
                : reportQueryService.getReports(from, to, page, size, reportScope));
    }

    @GetMapping("/latest")
    @Operation(summary = "최신 보고서 조회", description = "보고서가 없으면 200과 null을 반환합니다.")
    public ApiResponse<ReportResDTO.Detail> getLatest(
            @RequestParam(defaultValue = "true") boolean includeFindings,
            @Parameter(description = "RUN 실행별 / DAILY 일일 통합. 생략하면 전체에서 최신")
            @RequestParam(required = false) ReportScope reportScope
    ) {
        ReportResDTO.Detail result = reportScope == null ? reportQueryService.getLatest(includeFindings)
                : reportQueryService.getLatest(includeFindings, reportScope);
        return result == null
                ? ApiResponse.of(GeneralSuccessCode.OK, "생성된 보고서가 없습니다.", null)
                : ApiResponse.of(GeneralSuccessCode.OK, result);
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "보고서 상세 조회", description = "마크다운 본문과 근거 findings를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "보고서가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"REPORT404","message":"보고서를 찾을 수 없습니다.","result":{}}
                            """)))
    })
    public ApiResponse<ReportResDTO.Detail> getReport(
            @Parameter(description = "보고서 ID") @PathVariable Long reportId,
            @RequestParam(defaultValue = "true") boolean includeFindings
    ) {
        return ApiResponse.of(GeneralSuccessCode.OK, reportQueryService.getReport(reportId, includeFindings));
    }
}
