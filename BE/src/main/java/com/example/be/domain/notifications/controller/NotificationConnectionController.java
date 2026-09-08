package com.example.be.domain.notifications.controller;

import com.example.be.domain.notifications.channel.TelegramConnectionAdapter;
import com.example.be.domain.notifications.channel.EmailNotificationSender;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.exception.NotificationTransportException;
import com.example.be.domain.notifications.repository.NotificationChannelRepository;
import com.example.be.domain.notifications.service.NotificationDeliveryPlanService;
import com.example.be.domain.notifications.service.ReportDeliveryOutboxStore;
import com.example.be.domain.notifications.service.ReportNotificationAutomationService;
import com.example.be.domain.notifications.service.TelegramConnectionService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
@Tag(name = "알림", description = "보고서 자동 전달, 텔레그램 연결 및 메일 전달 경로 API")
public class NotificationConnectionController {

    private static final String CONNECTED_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"status":"CONNECTED","expiresAt":null}}
            """;

    private static final String WAITING_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"status":"WAITING","expiresAt":"2026-09-08T16:10:00+09:00"}}
            """;

    private static final String LINK_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"url":"https://t.me/example_bot?start=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expiresAt":"2026-09-08T16:10:00+09:00"}}
            """;

    private static final String DISCONNECTED_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"status":"DISCONNECTED","expiresAt":null}}
            """;

    private static final String POLICY_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"enabled":true,"run":true,"daily":false,"groupIds":[1],"recipientIds":[2],"channelIds":[1,2]}}
            """;

    private static final String DELIVERIES_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":[{"id":1,"recipientName":"수신자","channelType":"EMAIL","status":"SENT","attempts":1,"message":null}]}
            """;

    private static final String RETRY_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"queuedCount":1}}
            """;

    private static final String EMAIL_EXAMPLE = """
            {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"mode":"LOCAL_CAPTURE","configured":true,"message":"현재 메일은 테스트 보관함(Mailpit)에 저장됩니다. 실제 이메일 수신함으로 전달되지 않습니다."}}
            """;

    private static final String RECIPIENT_NOT_FOUND = """
            {"isSuccess":false,"code":"RECIPIENT404","message":"수신자를 찾을 수 없습니다.","result":{}}
            """;

    private static final String RECIPIENT_INACTIVE = """
            {"isSuccess":false,"code":"COMMON400","message":"활성 수신자만 연결할 수 있습니다.","result":{}}
            """;

    private static final String TOPIC_NOT_FOUND = """
            {"isSuccess":false,"code":"COMMON404","message":"수집 주제를 찾을 수 없습니다.","result":{}}
            """;

    private static final String REPORT_NOT_FOUND = """
            {"isSuccess":false,"code":"REPORT404","message":"보고서를 찾을 수 없습니다.","result":{}}
            """;

    private static final String GROUP_NOT_FOUND = """
            {"isSuccess":false,"code":"GROUP404","message":"수신 그룹을 찾을 수 없습니다.","result":{}}
            """;

    private static final String CHANNEL_NOT_FOUND = """
            {"isSuccess":false,"code":"CHANNEL404","message":"알림 채널을 찾을 수 없습니다.","result":{}}
            """;

    private static final String KIND_REQUIRED = """
            {"isSuccess":false,"code":"COMMON400","message":"전달할 보고서 종류를 선택해 주세요.","result":{}}
            """;

    private static final String TARGET_REQUIRED = """
            {"isSuccess":false,"code":"COMMON400","message":"수신 그룹이나 수신자를 선택해 주세요.","result":{}}
            """;

    private static final String CHANNEL_REQUIRED = """
            {"isSuccess":false,"code":"COMMON400","message":"전달 채널을 선택해 주세요.","result":{}}
            """;

    private static final String INVALID_IDS = """
            {"isSuccess":false,"code":"COMMON400","message":"선택 값이 올바르지 않습니다.","result":{}}
            """;

    private static final String TELEGRAM_NOT_CONFIGURED = """
            {"isSuccess":false,"code":"COMMON400","message":"텔레그램 연결 설정이 준비되지 않았습니다.","result":{}}
            """;

    private static final String TELEGRAM_WEBHOOK_CONFLICT = """
            {"isSuccess":false,"code":"COMMON400","message":"이 봇은 다른 연결 서비스를 사용 중입니다. 관리자에게 연결 설정 확인을 요청해 주세요.","result":{}}
            """;

    private static final String TELEGRAM_UNAVAILABLE = """
            {"isSuccess":false,"code":"COMMON400","message":"텔레그램 연결 서버에 접속하지 못했습니다. 잠시 후 다시 시도해 주세요.","result":{}}
            """;

    private static final String SERVER_ERROR = """
            {"isSuccess":false,"code":"COMMON500","message":"서버 내부 오류입니다.","result":{}}
            """;

    private final TelegramConnectionService connections;
    private final TelegramConnectionAdapter telegram;
    private final ReportNotificationAutomationService automation;
    private final ReportDeliveryOutboxStore outbox;
    private final NotificationDeliveryPlanService plans;
    private final NotificationChannelRepository channels;
    private final EmailNotificationSender email;

    @GetMapping("/recipients/{recipientId}/telegram")
    @Operation(summary = "텔레그램 연결 상태", description = "주소와 연결 토큰을 노출하지 않고 CONNECTED/WAITING/EXPIRED/DISCONNECTED 및 만료 시각을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 상태 조회",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "connected_example", value = CONNECTED_EXAMPLE),
                            @ExampleObject(name = "waiting_example", value = WAITING_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "비활성 수신자",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "recipient_inactive", value = RECIPIENT_INACTIVE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "수신자 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "recipient_not_found", value = RECIPIENT_NOT_FOUND)
                    }))
    })
    public ApiResponse<TelegramConnectionService.Connection> status(@PathVariable Long recipientId) {
        return ApiResponse.of(GeneralSuccessCode.OK, connections.status(recipientId));
    }

    @PostMapping("/recipients/{recipientId}/telegram/link")
    @Operation(summary = "텔레그램 연결 링크 만들기", description = "선택한 활성 수신자에 귀속되는 10분 유효 일회성 링크를 만듭니다. 기존 미사용 링크는 폐기합니다. 메시지를 발송하지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "10분 유효 일회성 연결 링크",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "link_example", value = LINK_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "수신자 또는 텔레그램 연결 설정 확인 필요",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "recipient_inactive", value = RECIPIENT_INACTIVE),
                            @ExampleObject(name = "telegram_not_configured", value = TELEGRAM_NOT_CONFIGURED),
                            @ExampleObject(name = "telegram_webhook_conflict", value = TELEGRAM_WEBHOOK_CONFLICT),
                            @ExampleObject(name = "telegram_unavailable", value = TELEGRAM_UNAVAILABLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "수신자 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "recipient_not_found", value = RECIPIENT_NOT_FOUND)
                    }))
    })
    public ApiResponse<TelegramConnectionService.Link> link(@PathVariable Long recipientId) {
        try {
            return ApiResponse.of(GeneralSuccessCode.OK,
                    connections.createLink(recipientId, telegram.botUsername()));
        } catch (NotificationTransportException error) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, error.getMessage());
        }
    }

    @DeleteMapping("/recipients/{recipientId}/telegram")
    @Operation(summary = "텔레그램 연결 해제", description = "해당 수신자의 연결과 아직 사용하지 않은 링크를 취소합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 해제",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "disconnected_example", value = DISCONNECTED_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "비활성 수신자",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "recipient_inactive", value = RECIPIENT_INACTIVE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "수신자 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "recipient_not_found", value = RECIPIENT_NOT_FOUND)
                    }))
    })
    public ApiResponse<TelegramConnectionService.Connection> disconnect(@PathVariable Long recipientId) {
        return ApiResponse.of(GeneralSuccessCode.OK, connections.disconnect(recipientId));
    }

    @GetMapping("/topics/{topicId}/delivery-policy")
    @Operation(summary = "주제 자동 전달 설정 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "주제별 자동 전달 설정",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "policy_example", value = POLICY_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "주제 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "topic_not_found", value = TOPIC_NOT_FOUND)
                    }))
    })
    public ApiResponse<ReportNotificationAutomationService.Policy> policy(@PathVariable Long topicId) {
        return ApiResponse.of(GeneralSuccessCode.OK, automation.policy(topicId));
    }

    @PutMapping("/topics/{topicId}/delivery-policy")
    @Operation(summary = "주제 자동 전달 설정 저장", description = "enabled/run/daily/groupIds/recipientIds/channelIds 전체 교체. enabled=true일 때 활성 대상·채널을 검증합니다. false이면 중지·삭제된 기존 선택도 보존하여 끌 수 있습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전체 교체한 자동 전달 설정",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "policy_example", value = POLICY_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "보고서 종류·대상·채널 또는 ID 검증 오류",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "kind_required", value = KIND_REQUIRED),
                            @ExampleObject(name = "target_required", value = TARGET_REQUIRED),
                            @ExampleObject(name = "channel_required", value = CHANNEL_REQUIRED),
                            @ExampleObject(name = "invalid_ids", value = INVALID_IDS)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "주제 또는 선택 대상 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "topic_not_found", value = TOPIC_NOT_FOUND),
                            @ExampleObject(name = "group_not_found", value = GROUP_NOT_FOUND),
                            @ExampleObject(name = "recipient_not_found", value = RECIPIENT_NOT_FOUND),
                            @ExampleObject(name = "channel_not_found", value = CHANNEL_NOT_FOUND)
                    }))
    })
    public ApiResponse<ReportNotificationAutomationService.Policy> policy(
            @PathVariable Long topicId,
            @RequestBody ReportNotificationAutomationService.Policy policy) {
        return ApiResponse.of(GeneralSuccessCode.OK, automation.savePolicy(topicId, policy));
    }

    @GetMapping("/reports/{reportId}/auto-deliveries")
    @Operation(summary = "보고서 자동 전달 상태", description = "대상별 PENDING/PROCESSING/SENT/FAILED/SKIPPED/UNKNOWN 상태 및 공개 오류를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "자동 전달 상태 목록",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "deliveries_example", value = DELIVERIES_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "완료된 보고서 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "report_not_found", value = REPORT_NOT_FOUND)
                    }))
    })
    public ApiResponse<List<ReportDeliveryOutboxStore.Delivery>> deliveries(@PathVariable Long reportId) {
        plans.requireReport(reportId);
        return ApiResponse.of(GeneralSuccessCode.OK, outbox.deliveries(reportId));
    }

    @PostMapping("/reports/{reportId}/auto-deliveries/retry")
    @Operation(summary = "실패한 자동 전달 재시도", description = "FAILED 대상만 재시도합니다. 이미 전달된 대상과 전송 결과가 불확실한 UNKNOWN은 제외합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확정 실패 대상 재접수",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "retry_example", value = RETRY_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "완료된 보고서 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "report_not_found", value = REPORT_NOT_FOUND)
                    }))
    })
    public ApiResponse<Map<String, Integer>> retry(@PathVariable Long reportId) {
        plans.requireReport(reportId);
        return ApiResponse.of(GeneralSuccessCode.OK, Map.of("queuedCount", outbox.retryFailed(reportId)));
    }

    public record EmailReadiness(String mode, boolean configured, String message) {
    }

    @GetMapping("/email-readiness")
    @Operation(summary = "메일 전달 경로 안내", description = "비밀값 없이 테스트 보관함/SMTP 설정 상태를 구분합니다. 서버 연결이나 실제 메일 전송은 수행하지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공개 설정에 따른 메일 전달 경로",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "email_example", value = EMAIL_EXAMPLE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "server_error", value = SERVER_ERROR)
                    }))
    })
    public ApiResponse<EmailReadiness> emailReadiness() {
        var channel = channels.findByChannelType(ChannelType.EMAIL).orElse(null);
        if (channel == null || !email.isConfigured(channel)) {
            return ApiResponse.of(GeneralSuccessCode.OK,
                    new EmailReadiness("UNCONFIGURED", false, "메일 전달 설정이 필요합니다."));
        }
        String host = String.valueOf(channel.getConfig().getOrDefault("host", ""));
        String port = String.valueOf(channel.getConfig().getOrDefault("port", ""));
        boolean local = (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equalsIgnoreCase("mailpit")) && port.equals("1025");
        return ApiResponse.of(GeneralSuccessCode.OK, new EmailReadiness(local ? "LOCAL_CAPTURE" : "SMTP", true,
                local ? "현재 메일은 테스트 보관함(Mailpit)에 저장됩니다. 실제 이메일 수신함으로 전달되지 않습니다."
                        : "메일 서버 전달이 설정되어 있습니다. 발송 성공은 서버 접수를 뜻하며 실제 수신은 발송 이력과 수신함에서 확인해 주세요."));
    }
}
