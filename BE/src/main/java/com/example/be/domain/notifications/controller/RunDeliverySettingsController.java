package com.example.be.domain.notifications.controller;

import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.service.RunDeliverySettingsService;
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
@RequestMapping("/api/notifications/runs/{runId}/delivery-settings")
@Tag(name = "알림", description = "진행 중 수집 실행의 보고서 알림 설정")
public class RunDeliverySettingsController {
    private final RunDeliverySettingsService service;
    private static final String SETTINGS = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"runId":42,"editable":true,"reportId":null,"reportReady":false,"source":"RUN","enabled":true,"run":true,"daily":false,"groupIds":[1],"recipientIds":[],"channelIds":[1],"topicPolicies":[]}}
            """;
    private static final String NOT_FOUND = """
            {"isSuccess":false,"code":"RUN404","message":"수집 실행 이력을 찾을 수 없습니다.","result":{}}
            """;
    private static final String CLOSED = """
            {"isSuccess":false,"code":"COMMON409","message":"보고서가 완성되었거나 수집이 종료되어 알림 설정을 변경할 수 없습니다.","result":{}}
            """;

    @GetMapping
    @Operation(summary = "수집 실행 보고서 알림 설정 조회", description = "RUN은 저장한 이번 실행 설정입니다. TOPIC이면 topicPolicies가 현재 주제별 정책이고 최상위 선택은 새 설정 기본값입니다. reportReady=true일 때만 완성 보고서 공유가 가능합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "실행별 설정 또는 주제별 적용 정책",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = SETTINGS))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "실행 없음",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = NOT_FOUND)))
    })
    public ApiResponse<RunDeliverySettingsService.Settings> get(@PathVariable Long runId) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.get(runId));
    }

    @PutMapping
    @Operation(summary = "수집 실행 보고서 알림 설정 저장", description = "PENDING/RUNNING이며 보고서 완성 전인 실행만 수정합니다. enabled/run/daily는 Boolean 필수이고 ID 배열은 양수 최대 100개입니다. 이번 실행만 교체하며 주제 정책을 바꾸거나 즉시 발송하지 않습니다. 선택 ID 집합이 같으면 확정된 수신자·채널 조합과 주소를 보존합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장한 이번 실행 설정",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = SETTINGS))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "필수 Boolean·보고서 종류·활성 대상·채널·ID 검증 오류",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "settings_required", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"자동 전달 설정이 필요합니다.\",\"result\":{}}"),
                            @ExampleObject(name = "enabled_required", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"자동 전달 사용 여부를 선택해 주세요.\",\"result\":{}}"),
                            @ExampleObject(name = "kind_required", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"전달할 보고서 종류를 선택해 주세요.\",\"result\":{}}"),
                            @ExampleObject(name = "target_required", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"수신 그룹이나 수신자를 선택해 주세요.\",\"result\":{}}"),
                            @ExampleObject(name = "channel_required", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"전달 채널을 선택해 주세요.\",\"result\":{}}"),
                            @ExampleObject(name = "invalid_ids", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"선택 값이 올바르지 않습니다.\",\"result\":{}}"),
                            @ExampleObject(name = "inactive_recipient", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"활성 수신자를 선택해 주세요.\",\"result\":{}}"),
                            @ExampleObject(name = "no_destination", value = "{\"isSuccess\":false,\"code\":\"COMMON400\",\"message\":\"선택한 대상에 연결된 수신 채널이 없습니다.\",\"result\":{}}")
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "실행 또는 선택 대상 없음",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "run_not_found", value = NOT_FOUND),
                            @ExampleObject(name = "group_not_found", value = "{\"isSuccess\":false,\"code\":\"GROUP404\",\"message\":\"수신 그룹을 찾을 수 없습니다.\",\"result\":{}}"),
                            @ExampleObject(name = "recipient_not_found", value = "{\"isSuccess\":false,\"code\":\"RECIPIENT404\",\"message\":\"수신자를 찾을 수 없습니다.\",\"result\":{}}"),
                            @ExampleObject(name = "channel_not_found", value = "{\"isSuccess\":false,\"code\":\"CHANNEL404\",\"message\":\"알림 채널을 찾을 수 없습니다.\",\"result\":{}}")
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "보고서 완성 또는 수집 종료",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = CLOSED)))
    })
    public ApiResponse<RunDeliverySettingsService.Settings> save(@PathVariable Long runId,
            @RequestBody(required = false) NotificationReqDTO.RunDeliverySettings request) {
        return ApiResponse.of(GeneralSuccessCode.OK, service.save(runId, request));
    }
}
