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
    @Operation(summary = "리포트 공시·분기 실적 조회", description = "최종 표시 가능한 중요 이슈 제목에 직접 나온 저장 상장 회사(6자리 영숫자 종목코드)의 공시·최근 12분기 자료만 반환합니다. 제목에 없는 명시 선택 회사와 원문·요약·관찰 항목에만 언급된 기업, 비상장은 제외합니다. 저장 회사명·검증된 matchedName·종목코드의 정확한 일치만 사용하며 그룹·계열사를 추정하지 않습니다. issueLinks는 해당 제목 이슈의 살아 있는 근거만 제공하고 filingRows의 직접 참조와 회사 참고를 구분합니다. 해당 회사나 구조화 이슈 제목이 없으면 companies는 빈 배열입니다. 원본 연결을 변경하거나 과거 자동 연결을 소급하지 않고 조회는 DART·LLM을 호출하지 않습니다. fetchedAt은 실제 자료 확인 시각입니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"isSuccess\":true,\"code\":\"COMMON200\",\"message\":\"성공입니다.\",\"result\":{\"reportId\":17,\"companies\":[{\"company\":{\"corpCode\":\"00126380\",\"corpName\":\"삼성전자\",\"stockCode\":\"005930\"},\"status\":\"PENDING\",\"message\":\"공시 자료를 준비하고 있습니다.\",\"data\":null,\"issueLinks\":[],\"filingRows\":[]}]}}"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REPORT404: 보고서를 찾을 수 없습니다.")
    })
    public ApiResponse<ReportDartDTO.Result> get(@PathVariable Long reportId) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.get(reportId));
    }

    @PostMapping("/{reportId}/dart/retry")
    @Operation(summary = "리포트 공시 자료 재시도", description = "상장 회사의 FAILED/DISABLED 작업만 다시 예약합니다. READY/PARTIAL 자료와 저장된 회사 스냅샷은 변경하지 않고 PENDING을 중복 예약하지 않습니다. 키 미설정은 DISABLED입니다. 외부 DART 호출을 기다리지 않습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다. 조회와 같은 reportId·companies 응답"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REPORT404: 보고서를 찾을 수 없습니다.")
    })
    public ApiResponse<ReportDartDTO.Result> retry(@PathVariable Long reportId) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.retry(reportId));
    }
}
