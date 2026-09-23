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
@Tag(name = "알림", description = "수신자별 주제 보고서 알림 및 개인 추가·제외 설정")
public class RecipientReportSubscriptionController {
    private final RecipientReportSubscriptionService subscriptions;

    @GetMapping
    @Operation(summary = "수신자 주제 보고서 알림 조회", description = "직접 또는 그룹으로 지정된 주제와 저장한 개인 추가·제외를 주제명, ID 순으로 반환합니다. configuredScopes와 includedScopes의 합집합에서 excludedScopes를 제외한 종류를 선택한 상태입니다. 현재 연결된 채널만 수신 가능으로 표시합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RECIPIENT404 / 수신자를 찾을 수 없습니다.")
    })
    public ApiResponse<RecipientReportSubscriptionService.Subscriptions> get(@PathVariable Long recipientId) {
        return ApiResponse.of(GeneralSuccessCode.OK, subscriptions.get(recipientId));
    }

    @PutMapping("/{topicId}")
    @Operation(summary = "수신자 주제 보고서 알림 설정", description = "excludedScopes와 includedScopes로 개인 제외·추가를 저장합니다. 각각 RUN/DAILY/WEEKLY, 최대 3개이며 []는 초기화입니다. includedScopes 생략/null이면 기존 추가에서 새 제외 종류만 제거합니다. 두 배열에 같은 종류를 명시할 수 없습니다. 공통 정책·그룹·다른 수신자를 바꾸거나 과거 보고서를 재발송하지 않습니다. 개인 추가는 주제 정책 활성·대상·전달 경로 조건을 따르며 별도 확정한 실행 대상은 확장하지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "제외 종류 검증 오류",
                    content = @Content(examples = {@ExampleObject(name = "excludedScopes", value = """
                            {"isSuccess":false,"code":"COMMON400","message":"제외할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요.","result":{}}
                            """), @ExampleObject(name = "includedScopes", value = """
                            {"isSuccess":false,"code":"COMMON400","message":"추가할 보고서 종류는 RUN, DAILY, WEEKLY 중에서 선택해 주세요.","result":{}}
                            """), @ExampleObject(name = "overlap", value = """
                            {"isSuccess":false,"code":"COMMON400","message":"같은 보고서 종류를 추가와 제외에 동시에 선택할 수 없습니다.","result":{}}
                            """)})),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RECIPIENT404 수신자 없음 / COMMON404 수집 주제 없음")
    })
    public ApiResponse<RecipientReportSubscriptionService.TopicSubscription> save(@PathVariable Long recipientId,
            @PathVariable Long topicId, @RequestBody RecipientReportSubscriptionService.Exclusions request) {
        return ApiResponse.of(GeneralSuccessCode.OK, subscriptions.save(recipientId, topicId, request));
    }
}
