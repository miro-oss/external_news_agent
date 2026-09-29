package com.example.be.domain.feedback.controller;

import com.example.be.domain.feedback.service.FeedbackService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.example.be.domain.feedback.dto.req.FeedbackReqDTO.*;
import static com.example.be.domain.feedback.dto.res.FeedbackResDTO.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/feedback")
@Tag(name="피드백",description="발송 수신자·보고서에 한정된 제보와 개인 정책")
public class FeedbackController {
    private final FeedbackService service;
    @PostMapping("/context")
    @Operation(summary="피드백 화면 조회",description="token은 JSON 본문으로만 전달합니다. 전문·수신자 주소를 반환하지 않고 저장된 발송 snapshot과 관련 제보·정책을 조회합니다. 400 COMMON400, 잘못되거나 만료된 token은 404 COMMON404입니다.")
    public ResponseEntity<ApiResponse<Context>> context(@RequestBody TokenRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.OK,service.context(request)));
    }
    @PostMapping
    @Operation(summary="사용자 제보 접수",description="comment 1~2000자, idempotencyKey 1~100자. PREFERENCE만 allowPersonalization=true를 허용합니다. 같은 링크·key·본문 재요청과 같은 수신자·보고서·항목의 동일 본문은 기존 결과, 다른 본문은 COMMON409입니다. 진단은 비동기로 처리하며 사실 제보는 정답으로 확정하지 않습니다.")
    public ResponseEntity<ApiResponse<PublicFeedback>> submit(@RequestBody SubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.CREATED,service.submit(request)));
    }
    @PostMapping("/policies/{policyId}/revoke")
    @Operation(summary="개인 정책 철회",description="같은 수신자·보고서 관련 주제의 정책만 철회합니다. version 낙관적 잠금과 동일 철회 재요청을 지원합니다. 400 COMMON400, 404 COMMON404, 409 COMMON409.")
    public ResponseEntity<ApiResponse<PublicPolicy>> revoke(@PathVariable long policyId,@RequestBody RevokeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.OK,service.revoke(policyId,request)));
    }
    @GetMapping("/evaluation-candidates")
    @Operation(summary="공통 개선 평가 후보 내보내기",description="운영 관리 API. afterId>=0, size 1~100(기본50). schemaVersion=1/cases/nextAfterId/hasNext. 개인 선호는 제외하며 goldLabel·split은 null, labelSource=USER_FEEDBACK입니다. 독립 사람 검증 전 정답으로 사용할 수 없습니다. 400 COMMON400.")
    public ResponseEntity<ApiResponse<Map<String,Object>>> export(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="50")int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.OK,service.export(afterId,size)));
    }
}
