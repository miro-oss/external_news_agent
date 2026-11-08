package com.example.be.domain.reports.controller;

import com.example.be.domain.reports.dto.req.ReportWatchItemPromotionRequest;
import com.example.be.domain.reports.dto.res.ReportWatchItemPromotionResult;
import com.example.be.domain.reports.service.ReportWatchItemPromotionService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/reports/{reportId}/watch-items")
@Tag(name = "보고서", description = "보고서 조회 및 관리")
public class ReportWatchItemController {
    private final ReportWatchItemPromotionService service;

    /** Saves a selection and returns the report ID the client must reload with its related displays. */
    @PostMapping("/promote")
    @Operation(summary = "관찰 항목을 중요 이벤트로 올리기", description = "로컬 PoC API. 현재 표시된 관찰 항목의 topic·reason·sourceFindingIds 전체를 저장 내용과 대조합니다. 문구·조건·근거와 원본 보고서를 보존하고 선택만 따로 저장하며, 웹과 이후 발송에서 중요 이벤트로 표시합니다. 같은 선택의 재시도는 중복 생성하지 않습니다. 현재 숨긴 근거·피드백 제외 항목과 변경된 항목은 올리지 않습니다. AI 호출과 실제 발송은 하지 않습니다. 200 COMMON200 성공입니다., 400 COMMON400 입력값 검증 실패입니다., 404 REPORT404 보고서를 찾을 수 없습니다., 409 COMMON409 상태 충돌이 발생했습니다.")
    public ResponseEntity<ApiResponse<ReportWatchItemPromotionResult>> promote(
            @PathVariable long reportId, @RequestBody ReportWatchItemPromotionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(GeneralSuccessCode.OK, service.promote(reportId, request)));
    }
}
