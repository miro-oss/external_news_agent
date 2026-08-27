package com.example.be.domain.notifications.controller;

import com.example.be.domain.notifications.dto.req.NotificationReqDTO;
import com.example.be.domain.notifications.dto.res.NotificationResDTO;
import com.example.be.domain.notifications.service.DeliveryLogQueryService;
import com.example.be.domain.notifications.service.NotificationDeliveryService;
import com.example.be.domain.notifications.service.NotificationManagementService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.PageResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
@Tag(name = "알림", description = "EMAIL·TELEGRAM 채널, 수신 대상, 보고서 발송 및 이력 API")
public class NotificationController {

    private final NotificationManagementService managementService;
    private final NotificationDeliveryService deliveryService;
    private final DeliveryLogQueryService logQueryService;

    @GetMapping("/channels")
    @Operation(summary = "알림 채널 목록 조회", description = "TELEGRAM과 EMAIL 채널의 공개 설정만 조회합니다.")
    public ApiResponse<List<NotificationResDTO.Channel>> getChannels(
            @RequestParam(required = false) Boolean active) {
        return ApiResponse.of(GeneralSuccessCode.OK, managementService.getChannels(active));
    }

    @PatchMapping("/channels/{channelId}")
    @Operation(summary = "알림 채널 설정 수정", description = "config는 전체 교체하며 비밀값은 받지 않습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정되었습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "채널 설정 오류",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = """
                                    {"isSuccess":false,"code":"CHANNEL400","message":"텔레그램 parseMode는 HTML만 지원합니다.","result":{}}
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채널 없음")
    })
    public ApiResponse<NotificationResDTO.Channel> updateChannel(
            @PathVariable Long channelId,
            @RequestBody NotificationReqDTO.ChannelUpdate request) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, managementService.updateChannel(channelId, request));
    }

    @GetMapping("/recipients")
    @Operation(summary = "수신자 목록 조회")
    public ApiResponse<PageResponse<NotificationResDTO.Recipient>> getRecipients(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String channelType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(GeneralSuccessCode.OK,
                managementService.getRecipients(active, groupId, channelType, keyword, page, size));
    }

    @PostMapping("/recipients")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "수신자 등록", description = "수신자와 채널별 주소를 함께 등록합니다.")
    public ApiResponse<NotificationResDTO.Recipient> createRecipient(
            @RequestBody NotificationReqDTO.RecipientCreate request) {
        return ApiResponse.of(GeneralSuccessCode.CREATED, managementService.createRecipient(request));
    }

    @PatchMapping("/recipients/{recipientId}")
    @Operation(summary = "수신자 수정", description = "기본 정보를 부분 수정합니다.")
    public ApiResponse<NotificationResDTO.RecipientBasic> updateRecipient(
            @PathVariable Long recipientId,
            @RequestBody NotificationReqDTO.RecipientUpdate request) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, managementService.updateRecipient(recipientId, request));
    }

    @DeleteMapping("/recipients/{recipientId}")
    @Operation(summary = "수신자 삭제", description = "발송 이력은 보존하고 수신자를 비활성화합니다.")
    public ApiResponse<NotificationResDTO.RecipientDeleted> deleteRecipient(@PathVariable Long recipientId) {
        return ApiResponse.of(GeneralSuccessCode.DELETED, managementService.deleteRecipient(recipientId));
    }

    @PutMapping("/recipients/{recipientId}/destinations")
    @Operation(summary = "수신자 채널별 수신 주소 설정", description = "기존 주소를 요청 목록으로 전체 교체합니다.")
    public ApiResponse<NotificationResDTO.Destinations> replaceDestinations(
            @PathVariable Long recipientId,
            @RequestBody NotificationReqDTO.DestinationsUpdate request) {
        return ApiResponse.of(GeneralSuccessCode.OK, "설정되었습니다.",
                managementService.replaceDestinations(recipientId, request));
    }

    @GetMapping("/groups")
    @Operation(summary = "수신 그룹 목록 조회")
    public ApiResponse<PageResponse<NotificationResDTO.Group>> getGroups(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String perspective,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(GeneralSuccessCode.OK,
                managementService.getGroups(active, perspective, page, size));
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "수신 그룹 등록", description = "recipientIds를 함께 보내면 멤버도 설정합니다.")
    public ApiResponse<NotificationResDTO.Group> createGroup(
            @RequestBody NotificationReqDTO.GroupCreate request) {
        return ApiResponse.of(GeneralSuccessCode.CREATED, managementService.createGroup(request));
    }

    @PatchMapping("/groups/{groupId}")
    @Operation(summary = "수신 그룹 수정")
    public ApiResponse<NotificationResDTO.Group> updateGroup(
            @PathVariable Long groupId,
            @RequestBody NotificationReqDTO.GroupUpdate request) {
        return ApiResponse.of(GeneralSuccessCode.UPDATED, managementService.updateGroup(groupId, request));
    }

    @DeleteMapping("/groups/{groupId}")
    @Operation(summary = "수신 그룹 삭제")
    public ApiResponse<NotificationResDTO.GroupDeleted> deleteGroup(@PathVariable Long groupId) {
        return ApiResponse.of(GeneralSuccessCode.DELETED, managementService.deleteGroup(groupId));
    }

    @PutMapping("/groups/{groupId}/members")
    @Operation(summary = "수신 그룹 멤버 설정", description = "기존 멤버를 요청 목록으로 전체 교체합니다.")
    public ApiResponse<NotificationResDTO.GroupMembers> replaceGroupMembers(
            @PathVariable Long groupId,
            @RequestBody NotificationReqDTO.MembersUpdate request) {
        return ApiResponse.of(GeneralSuccessCode.OK, "설정되었습니다.",
                managementService.replaceGroupMembers(groupId, request));
    }

    @PostMapping("/reports/{reportId}/preview")
    @Operation(summary = "보고서 발송 미리보기", description = "DB와 발송 이력을 변경하지 않습니다.")
    public ApiResponse<NotificationResDTO.Preview> preview(
            @PathVariable Long reportId,
            @RequestBody NotificationReqDTO.Preview request) {
        return ApiResponse.of(GeneralSuccessCode.OK, deliveryService.preview(reportId, request));
    }

    @PostMapping("/reports/{reportId}/send")
    @Operation(summary = "보고서 발송", description = "활성 그룹·수신자·주소에 발송하고 개별 결과를 기록합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발송을 완료했습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "발송 대상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "모든 발송 실패")
    })
    public ApiResponse<NotificationResDTO.SendBatch> send(
            @PathVariable Long reportId,
            @RequestBody NotificationReqDTO.Send request) {
        return ApiResponse.of(GeneralSuccessCode.OK, "발송을 완료했습니다.",
                deliveryService.send(reportId, request));
    }

    @GetMapping("/delivery-logs")
    @Operation(summary = "발송 이력 조회")
    public ApiResponse<NotificationResDTO.DeliveryLogs> getDeliveryLogs(
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) Long runId,
            @RequestParam(required = false) String deliveryBatchId,
            @RequestParam(required = false) String channelType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long recipientId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(GeneralSuccessCode.OK, logQueryService.getLogs(reportId, runId, deliveryBatchId,
                channelType, status, recipientId, from, to, page, size));
    }
}
