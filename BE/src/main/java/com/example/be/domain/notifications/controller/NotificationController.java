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
    @Operation(summary = "보고서 발송 미리보기", description = """
            실제 발송과 같은 텔레그램 뉴스 카드형·이메일 브리핑형 본문을 반환합니다. DB와 발송 이력을 변경하지 않습니다.
            저장된 importantEvents 중 sourceFindingIds가 비어 있지 않고 모두 표시 가능한 finding인 이벤트를 저장 순서대로 최대 3개 사용합니다.
            텔레그램은 제목·요약·확인할 점·근거 원문을 채널 maxLength 안의 HTML 메시지 1개로 구성합니다.
            긴 URL은 원문 링크 수를 2개에서 1개 또는 0개로 줄여 카드 주요 내용과 가능한 전체 보고서 링크를 유지하며, 극소 길이에서만 일반 텍스트로 안전하게 축약합니다.
            이메일은 executiveSummary를 우선한 핵심 요약과 이벤트별 주요 내용·후속 확인·원문을 제공합니다.
            후속 확인은 모든 근거가 표시 가능하고 해당 이벤트와 근거가 연결된 저장 watchItems만 최대 2개 사용합니다.
            이벤트별 원문 링크는 해당 sourceFindingIds에 연결된 안전한 URL을 최대 2개 표시합니다.
            읽는 관점과 significance는 표시하지 않습니다. 후속 확인을 새로 만들거나 AI를 다시 호출하지 않습니다.
            기존 요약 대체 경로, 본문 확보·관련성 필터와 DAILY에 저장된 finding 선택·순서를 유지합니다.
            public-base-url 설정 시 전체 보고서 링크를 추가합니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "TELEGRAM 뉴스 카드형", value = """
                                    {
                                      "isSuccess": true,
                                      "code": "COMMON200",
                                      "message": "성공입니다.",
                                      "result": {
                                        "reportId": 17,
                                        "channelId": 1,
                                        "channelType": "TELEGRAM",
                                        "parseMode": "HTML",
                                        "maxLength": 3500,
                                        "subject": null,
                                        "chunks": [
                                          {
                                            "seq": 1,
                                            "length": 486,
                                            "body": "<b>반도체 뉴스 브리핑 · 2026-09-22 (형식 예시)</b>\\n\\n<b>1. HBM 공급 확대</b>\\nHBM 공급 확대 계획이 발표됐습니다. 공급 일정과 생산 여력에 관한 내용을 함께 정리했습니다.\\n확인할 점: 공급 일정 — 세부 일정이 공개되면 후속 내용을 확인합니다.\\n<a href=\\"https://example.com/article-11\\">원문 1 · example.com</a>\\n\\n<b>2. 반도체 장비 생산거점 확대</b>\\n반도체 장비 기업이 생산거점 확대 계획을 공개했습니다. 거점의 역할과 대응 시장에 관한 내용을 다룹니다.\\n확인할 점: 거점 운영 계획 — 신규 거점의 가동 일정과 생산 품목을 확인합니다.\\n<a href=\\"https://example.com/article-22\\">원문 1 · example.com</a>\\n\\n<a href=\\"https://news.example.com/#/reports?reportId=17\\">보고서 전체 보기</a>\\n"
                                          }
                                        ],
                                        "chunkCount": 1
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "EMAIL 브리핑형", value = """
                                    {
                                      "isSuccess": true,
                                      "code": "COMMON200",
                                      "message": "성공입니다.",
                                      "result": {
                                        "reportId": 17,
                                        "channelId": 2,
                                        "channelType": "EMAIL",
                                        "parseMode": null,
                                        "maxLength": 2147483647,
                                        "subject": "[뉴스 보고서] 반도체 뉴스 브리핑 · 2026-09-22 (형식 예시)",
                                        "chunks": [
                                          {
                                            "seq": 1,
                                            "length": 6824,
                                            "body": "<!DOCTYPE html><html lang=\\"ko\\"><head><meta charset=\\"UTF-8\\">\\n<meta name=\\"viewport\\" content=\\"width=device-width, initial-scale=1\\">\\n<style>@media only screen and (max-width:600px){.email-content{padding:24px 20px!important}.email-card{padding:20px!important}.email-title{font-size:25px!important}}</style>\\n</head><body style=\\"margin:0;padding:0;background:#f2f4f6;color:#191f28;font-family:Pretendard,-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;line-height:1.7;-webkit-text-size-adjust:100%\\">\\n<table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" bgcolor=\\"#f2f4f6\\" style=\\"background:#f2f4f6\\"><tbody><tr><td align=\\"center\\" style=\\"padding:32px 12px\\">\\n<!--[if mso]><table role=\\"presentation\\" width=\\"680\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\"><tr><td><![endif]-->\\n<table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" bgcolor=\\"#ffffff\\" style=\\"width:100%;max-width:680px;table-layout:fixed;background:#ffffff;border-radius:20px;border-spacing:0;overflow-wrap:anywhere;word-break:break-word;text-align:left\\"><tbody>\\n<tr><td class=\\"email-content\\" style=\\"padding:32px;border-top:4px solid #3182f6;border-radius:20px 20px 0 0\\">\\n<p style=\\"margin:0;color:#1b64da;font-size:20px;font-weight:800;letter-spacing:-0.6px\\">BISTelligence</p>\\n<p style=\\"margin:2px 0 28px;color:#636c78;font-size:11px;letter-spacing:0.6px\\">News Signal Desk</p>\\n<p style=\\"margin:0 0 10px;color:#1b64da;font-size:11px;font-weight:700;letter-spacing:1.6px\\">NEWS BRIEFING</p>\\n<h1 class=\\"email-title\\" style=\\"margin:0 0 24px;color:#191f28;font-size:30px;font-weight:800;line-height:1.4;letter-spacing:-0.8px\\">\\n반도체 뉴스 브리핑 · 2026-09-22 (형식 예시)</h1><table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" style=\\"border-collapse:collapse;border-spacing:0;text-align:left;mso-table-lspace:0pt;mso-table-rspace:0pt\\"><tbody><tr><td class=\\"email-card\\" bgcolor=\\"#e8f3ff\\" style=\\"padding:24px;background:#e8f3ff;border-radius:16px\\"><h2 style=\\"margin:0 0 12px;color:#1b64da;font-size:14px;font-weight:700\\">핵심 요약</h2><table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" style=\\"border-collapse:collapse;border-spacing:0;text-align:left;mso-table-lspace:0pt;mso-table-rspace:0pt\\"><tbody><tr><td width=\\"20\\" valign=\\"top\\" aria-hidden=\\"true\\" style=\\"width:20px;padding:4px 0;color:#1b64da;font-size:15px;line-height:1.8\\">•</td><td valign=\\"top\\" style=\\"padding:4px 0;color:#191f28;font-size:15px;line-height:1.8\\">HBM 공급 확대와 반도체 장비 생산거점 변화가 이번 보고서의 주요 내용입니다.</td></tr></tbody></table></td></tr></tbody></table><table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" style=\\"border-collapse:collapse;border-spacing:0;text-align:left;mso-table-lspace:0pt;mso-table-rspace:0pt\\"><tbody><tr><td height=\\"20\\" style=\\"height:20px;font-size:0;line-height:0\\"></td></tr><tr><td class=\\"email-card\\" bgcolor=\\"#f9fafb\\" style=\\"padding:24px;background:#f9fafb;border-radius:16px\\"><p style=\\"margin:0 0 12px;color:#1b64da;font-size:12px;font-weight:700\\"><span style=\\"display:inline-block;padding:4px 10px;background:#e8f3ff;border-radius:8px\\">주요 이슈 1</span></p><h2 style=\\"margin:0 0 20px;color:#191f28;font-size:21px;font-weight:700;line-height:1.45;letter-spacing:-0.5px\\">HBM 공급 확대</h2><p style=\\"margin:0 0 6px;color:#191f28;font-size:13px;font-weight:700\\">주요 내용</p><p style=\\"margin:0;color:#4e5968;font-size:15px;line-height:1.8\\">HBM 공급 확대 계획이 발표됐습니다. 공급 일정과 생산 여력에 관한 내용을 함께 정리했습니다.</p><table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" style=\\"border-collapse:collapse;border-spacing:0;text-align:left;mso-table-lspace:0pt;mso-table-rspace:0pt\\"><tbody><tr><td style=\\"padding:20px 0 6px;color:#191f28;font-size:13px;line-height:1.7\\"><strong>후속 확인</strong></td></tr><tr><td valign=\\"top\\" style=\\"padding:0 0 4px;color:#4e5968;font-size:14px;line-height:1.8\\">공급 일정 — 세부 일정이 공개되면 후속 내용을 확인합니다.</td></tr></tbody></table><p style=\\"margin:20px 0 0;padding-top:16px;border-top:1px solid #e5e8eb;color:#636c78;font-size:12px;line-height:1.8\\"><a href=\\"https://example.com/article-11\\" style=\\"color:#1b64da;text-decoration:underline;overflow-wrap:anywhere;word-break:break-word\\">원문 1 · example.com</a></p></td></tr></tbody></table><table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" style=\\"border-collapse:collapse;border-spacing:0;text-align:left;mso-table-lspace:0pt;mso-table-rspace:0pt\\"><tbody><tr><td height=\\"20\\" style=\\"height:20px;font-size:0;line-height:0\\"></td></tr><tr><td class=\\"email-card\\" bgcolor=\\"#f9fafb\\" style=\\"padding:24px;background:#f9fafb;border-radius:16px\\"><p style=\\"margin:0 0 12px;color:#1b64da;font-size:12px;font-weight:700\\"><span style=\\"display:inline-block;padding:4px 10px;background:#e8f3ff;border-radius:8px\\">주요 이슈 2</span></p><h2 style=\\"margin:0 0 20px;color:#191f28;font-size:21px;font-weight:700;line-height:1.45;letter-spacing:-0.5px\\">반도체 장비 생산거점 확대</h2><p style=\\"margin:0 0 6px;color:#191f28;font-size:13px;font-weight:700\\">주요 내용</p><p style=\\"margin:0;color:#4e5968;font-size:15px;line-height:1.8\\">반도체 장비 기업이 생산거점 확대 계획을 공개했습니다. 거점의 역할과 대응 시장에 관한 내용을 다룹니다.</p><table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" style=\\"border-collapse:collapse;border-spacing:0;text-align:left;mso-table-lspace:0pt;mso-table-rspace:0pt\\"><tbody><tr><td style=\\"padding:20px 0 6px;color:#191f28;font-size:13px;line-height:1.7\\"><strong>후속 확인</strong></td></tr><tr><td valign=\\"top\\" style=\\"padding:0 0 4px;color:#4e5968;font-size:14px;line-height:1.8\\">거점 운영 계획 — 신규 거점의 가동 일정과 생산 품목을 확인합니다.</td></tr></tbody></table><p style=\\"margin:20px 0 0;padding-top:16px;border-top:1px solid #e5e8eb;color:#636c78;font-size:12px;line-height:1.8\\"><a href=\\"https://example.com/article-22\\" style=\\"color:#1b64da;text-decoration:underline;overflow-wrap:anywhere;word-break:break-word\\">원문 1 · example.com</a></p></td></tr></tbody></table><table role=\\"presentation\\" width=\\"100%\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\" style=\\"border-collapse:collapse;border-spacing:0;text-align:left;mso-table-lspace:0pt;mso-table-rspace:0pt\\"><tbody><tr><td align=\\"center\\" style=\\"padding:28px 0 0\\"><table role=\\"presentation\\" border=\\"0\\" cellpadding=\\"0\\" cellspacing=\\"0\\"><tbody><tr><td align=\\"center\\" bgcolor=\\"#1b64da\\" style=\\"background:#1b64da;border-radius:12px;mso-padding-alt:14px 24px\\"><a href=\\"https://news.example.com/#/reports?reportId=17\\" style=\\"display:inline-block;padding:14px 24px;color:#ffffff;font-size:15px;font-weight:700;text-decoration:none;line-height:1.5\\">보고서 전체 보기</a></td></tr></tbody></table></td></tr></tbody></table></td></tr></tbody></table>\\n<!--[if mso]></td></tr></table><![endif]-->\\n<p style=\\"margin:20px 0 0;color:#636c78;font-size:12px;line-height:1.6\\">BISTelligence · News Signal Desk</p>\\n</td></tr></tbody></table></body></html>\\n"
                                          }
                                        ],
                                        "chunkCount": 1
                                      }
                                    }
                                    """)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "보고서 또는 활성 채널 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "보고서 없음", value = """
                                    {"isSuccess":false,"code":"REPORT404","message":"보고서를 찾을 수 없습니다.","result":{}}
                                    """),
                            @ExampleObject(name = "채널 없음", value = """
                                    {"isSuccess":false,"code":"CHANNEL404","message":"알림 채널을 찾을 수 없습니다.","result":{}}
                                    """)
                    }))
    })
    public ApiResponse<NotificationResDTO.Preview> preview(
            @PathVariable Long reportId,
            @RequestBody NotificationReqDTO.Preview request) {
        return ApiResponse.of(GeneralSuccessCode.OK, deliveryService.preview(reportId, request));
    }

    @PostMapping("/reports/{reportId}/send")
    @Operation(summary = "보고서 발송", description = """
            활성 그룹·수신자·주소에 발송하고 개별 결과를 기록합니다. 수신자·채널별 중복 제거와 배치 처리 규칙을 유지합니다.
            미리보기와 같은 텔레그램 뉴스 카드형·이메일 브리핑형 본문을 사용하며 저장된 근거 있는 importantEvents를 최대 3개 표시합니다.
            텔레그램은 채널 maxLength 안의 메시지 1개이며, 이메일은 핵심 요약과 이벤트별 주요 내용·후속 확인·원문을 제공합니다.
            읽는 관점과 significance는 표시하지 않고, 저장된 관련 watchItems만 사용하며 AI를 다시 호출하지 않습니다.
            본문 확보·관련성 필터와 DAILY에 저장된 finding 선택·순서를 유지하며 public-base-url 설정 시 전체 보고서 링크를 추가합니다.
            """)
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
