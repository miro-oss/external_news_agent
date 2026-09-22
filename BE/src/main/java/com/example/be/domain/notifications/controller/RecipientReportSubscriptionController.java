package com.example.be.domain.notifications.controller;

import com.example.be.domain.notifications.service.RecipientReportSubscriptionService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications/recipients/{recipientId}/report-subscriptions")
@Tag(name = "알림", description = "수신자별 주제 보고서 알림 및 개인 제외 설정")
public class RecipientReportSubscriptionController {
    private final RecipientReportSubscriptionService subscriptions;

    @GetMapping
    @Operation(summary = "수신자 주제 보고서 알림 조회", description = "직접 또는 그룹으로 지정된 주제와 저장한 개인 제외를 주제명, ID 순으로 반환합니다. 현재 연결된 채널만 수신 가능으로 표시합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RECIPIENT404 / 수신자를 찾을 수 없습니다.")
    })
    public ApiResponse<RecipientReportSubscriptionService.Subscriptions> get(@PathVariable Long recipientId) {
        return ApiResponse.of(GeneralSuccessCode.OK, subscriptions.get(recipientId));
    }

    @PutMapping("/{topicId}")
    @Operation(summary = "수신자 주제 보고서 알림 제외 설정", description = "excludedScopes를 전체 교체합니다. RUN/DAILY/WEEKLY, 최대 3개이며 []는 개인 제외 해제입니다. 그룹과 다른 수신자의 설정을 바꾸지 않고 과거 보고서를 발송하지 않습니다. 예약 시와 자동 발송 직전에 적용합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "제외 종류 검증 오류",
                    content = @Content(examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"COMMON400","message":"제외할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요.","result":{}}
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RECIPIENT404 수신자 없음 / COMMON404 수집 주제 없음")
    })
    public ApiResponse<RecipientReportSubscriptionService.TopicSubscription> save(@PathVariable Long recipientId,
            @PathVariable Long topicId, @RequestBody RecipientReportSubscriptionService.Exclusions request) {
        return ApiResponse.of(GeneralSuccessCode.OK, subscriptions.save(recipientId, topicId, request));
    }
}
