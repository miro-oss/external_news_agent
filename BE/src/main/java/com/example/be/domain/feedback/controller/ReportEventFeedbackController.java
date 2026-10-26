package com.example.be.domain.feedback.controller;

import com.example.be.domain.feedback.service.ReportEventFeedbackService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.example.be.domain.feedback.dto.req.FeedbackReqDTO.*;
import static com.example.be.domain.feedback.dto.res.FeedbackResDTO.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/reports/{reportId}/event-feedback")
@Tag(name="피드백",description="보고서 이벤트의 의견과 비동기 진단")
public class ReportEventFeedbackController {
    private final ReportEventFeedbackService service;

    @GetMapping
    @Operation(summary="보고서 중요 이벤트 피드백 조회",description="로컬 PoC API. 수신자·token 없이 현재 표시 이벤트와 로컬 제보를 조회합니다. AI 호출·DB 쓰기·토큰 발급이 없고 기존 수신자 제보는 섞지 않습니다. eventIndex는 표시 중요 이벤트의 0-based 순서, eventKey는 보고서·문구·전체 근거 revision의 64자리 SHA-256입니다. 레거시 보고서는 events=[]. 400 COMMON400, 404 REPORT404.")
    public ResponseEntity<ApiResponse<EventFeedbackContext>> context(@PathVariable long reportId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.OK,service.context(reportId)));
    }

    @PostMapping
    @Operation(summary="보고서 중요 이벤트 의견 접수",description="로컬 PoC API. comment는 trim 후 1~2000자, idempotencyKey는 1~100자입니다. 같은 key 또는 같은 이벤트의 동일 본문은 기존 결과, 다른 본문·다른 이벤트의 key 재사용·오래된 eventKey는 COMMON409입니다. 전체 이벤트·원문·당시 주제 조건을 동결해 비동기 진단하며 개인 정책을 만들지 않습니다. 자료 누락·원문 10건 초과·입력 크기 초과는 일부 근거로 판단하지 않고 INSUFFICIENT_EVIDENCE로 완료합니다. 201 COMMON201, 400 COMMON400, 404 REPORT404, 409 COMMON409.")
    public ResponseEntity<ApiResponse<EventFeedback>> submit(@PathVariable long reportId,@RequestBody EventSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.CREATED,service.submit(reportId,request)));
    }
}
