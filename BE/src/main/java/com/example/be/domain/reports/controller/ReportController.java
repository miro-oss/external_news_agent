package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportQueryService;
import com.example.be.domain.reports.service.ReportCommandService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/reports")
@Tag(name = "보고서", description = "실행별·일일 통합 보고서 조회·삭제 API")
public class ReportController {

    private final ReportQueryService reportQueryService;
    private final ReportCommandService reportCommandService;

    @DeleteMapping("/{reportId}")
    @Operation(summary = "보고서 삭제", description = "보고서를 목록·최신·상세 조회와 새 공유 대상에서 제외합니다. 원문·분석·발송 이력 및 기존 일일 통합 내용은 보존합니다. 이미 삭제한 보고서도 같은 성공 응답을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "삭제되었습니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":true,"code":"COMMON200","message":"삭제되었습니다.","result":{"id":17,"deleted":true}}
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "보고서가 존재하지 않거나 생성 중인 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"REPORT404","message":"보고서를 찾을 수 없습니다.","result":{}}
                            """)))
    })
    public ApiResponse<ReportResDTO.Deleted> deleteReport(
            @Parameter(description = "보고서 ID") @PathVariable Long reportId
    ) {
        return ApiResponse.of(GeneralSuccessCode.DELETED, reportCommandService.deleteReport(reportId));
    }

    @GetMapping
    @Operation(summary = "보고서 목록 조회", description = "본문 없이 생성 시각·집계값·저장된 수집 조건을 페이징 조회합니다. collectionStartedAt은 RUN 원본 실행의 시작 시각(Asia/Seoul)이며 DAILY 또는 기록이 없으면 null입니다. from/to는 기존 생성일 기준을 유지합니다.")
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
    @Operation(summary = "최신 보고서 조회", description = "저장된 보고서 구조·수집 조건·고유 기사 통계를 함께 조회합니다. 보고서가 없으면 200과 null을 반환합니다.")
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
    @Operation(summary = "보고서 상세 조회", description = "마크다운과 구조화 본문, 실행 접수 당시 수집 조건, 고유 기사 통계 및 근거 findings를 조회합니다. 이전 보고서의 structuredContent는 null일 수 있습니다.")
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
