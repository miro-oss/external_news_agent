package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.insight.ReportInsightService;
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
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/news/reports") @Tag(name = "보고서")
public class ReportInsightController {
    private final ReportInsightService service;

    @PostMapping("/{reportId}/insights")
    @Operation(summary = "리포트 관점 인사이트 생성", description = "완료된 리포트의 검증된 근거로 관점별 중요도와 종합 해석을 생성합니다. 1~4개의 서로 다른 기존 관점을 요청할 수 있으며, 미저장 관점마다 INSIGHT 사용량을 별도로 예약합니다. 동일 보고서·근거 해시·관점·프롬프트·평가 기준은 재사용합니다. 사실은 저장된 주장과 원래 주장 유형을 유지하고 신규 LLM 사실을 만들지 않습니다. 검증된 근거 50개 초과는 호출 전에 거절합니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject(value = "{\"audiences\":[\"CHIP_MAKER\"]}")))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON400: reportId는 양수여야 합니다. / AUDIENCE400: 지원하지 않는 관점입니다.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                    @ExampleObject(name = "reportId", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"reportId는 양수여야 합니다.\",\"result\":{}}"),
                    @ExampleObject(name = "audience", value = "{\"isSuccess\":false,\"code\":\"AUDIENCE400\",\"message\":\"지원하지 않는 관점입니다.\",\"result\":{}}") })),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REPORT404: 보고서를 찾을 수 없습니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "COMMON409: 기능 비활성화, 검증 근거 없음, 검증된 근거 50개 초과 또는 동일 요청 생성 중"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "QUOTA429: LLM 사용 한도가 소진되었습니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "COMMON500: 리포트 관점 인사이트 생성에 실패했습니다.")
    })
    public ApiResponse<ReportInsightDTO.Result> create(@Parameter(description = "양수 보고서 ID") @PathVariable Long reportId,
            @RequestBody ReportInsightDTO.CreateRequest body) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.create(reportId, body));
    }

    @GetMapping("/{reportId}/insights")
    @Operation(summary = "저장된 리포트 관점 인사이트 조회", description = "현재 공개 가능한 보고서 근거를 다시 확인하고, 같은 근거 해시·관점·프롬프트·평가 기준으로 저장된 결과만 반환합니다. LLM 호출, 사용량 예약 및 감사 기록 쓰기가 없습니다. 과거의 다른 근거로 생성한 결과를 대신 반환하지 않습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "COMMON400: reportId는 양수여야 합니다. / AUDIENCE400: 지원하지 않는 관점입니다."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REPORT404: 보고서를 찾을 수 없습니다. / COMMON404: 저장된 리포트 관점 인사이트가 없습니다.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                    @ExampleObject(name = "report", value = "{\"isSuccess\":false,\"code\":\"REPORT404\",\"message\":\"보고서를 찾을 수 없습니다.\",\"result\":{}}"),
                    @ExampleObject(name = "cache", value = "{\"isSuccess\":false,\"code\":\"COMMON404\",\"message\":\"저장된 리포트 관점 인사이트가 없습니다.\",\"result\":{}}") })),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "COMMON409: 검증 근거가 없거나 검증된 근거가 50개를 초과합니다.")
    })
    public ApiResponse<ReportInsightDTO.Result> get(@Parameter(description = "양수 보고서 ID") @PathVariable Long reportId,
            @Parameter(description = "CHIP_MAKER, EQUIPMENT_MAKER, MARKET_INVESTOR, IT_INFRA 중 하나", required = true)
            @RequestParam String audience) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.get(reportId, audience));
    }
}
