package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.dto.res.ReportDartDTO;
import com.example.be.domain.reports.dart.ReportDartService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/reports")
@Tag(name = "보고서")
public class ReportDartController {
    private final ReportDartService service;

    @GetMapping("/{reportId}/dart")
    @Operation(summary = "리포트 공시·분기 실적 조회", description = "RUN 접수 당시 선택한 회사 또는 주제별 WEEKLY 생성 요청 당시 연결한 회사의 저장된 공시·최근 연속 12분기 자료를 반환합니다. RUN 회사는 중복 제거하고 주제별 WEEKLY는 최대 1개입니다. 회사 미선택·회사 스냅샷 없는 이전 보고서·전체 DAILY/WEEKLY는 companies가 빈 배열입니다. 조회는 DART 및 LLM을 호출하지 않습니다. fetchedAt은 실제 자료 확인 시각입니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"isSuccess\":true,\"code\":\"COMMON200\",\"message\":\"성공입니다.\",\"result\":{\"reportId\":17,\"companies\":[{\"company\":{\"corpCode\":\"00126380\",\"corpName\":\"삼성전자\",\"stockCode\":\"005930\"},\"status\":\"PENDING\",\"message\":\"공시 자료를 준비하고 있습니다.\",\"data\":null}]}}"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REPORT404: 보고서를 찾을 수 없습니다.")
    })
    public ApiResponse<ReportDartDTO.Result> get(@PathVariable Long reportId) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.get(reportId));
    }

    @PostMapping("/{reportId}/dart/retry")
    @Operation(summary = "리포트 공시 자료 재시도", description = "FAILED/DISABLED 작업만 다시 예약합니다. READY/PARTIAL 자료와 저장된 회사 스냅샷은 변경하지 않고 PENDING을 중복 예약하지 않습니다. 키 미설정은 DISABLED입니다. 외부 DART 호출을 기다리지 않습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다. 조회와 같은 reportId·companies 응답"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REPORT404: 보고서를 찾을 수 없습니다.")
    })
    public ApiResponse<ReportDartDTO.Result> retry(@PathVariable Long reportId) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.retry(reportId));
    }
}
