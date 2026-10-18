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
            개인 기준 적용 전 공용 보고서의 텔레그램 뉴스 카드형·이메일 브리핑형 본문을 반환합니다. DB와 발송 이력을 변경하지 않고 수신자 전용 링크를 발급하지 않습니다.
            미리보기 응답은 본문만 제공합니다. 이메일의 상세 PDF는 실제 발송 시 최종 수신자별 표시 기준으로 생성합니다.
            로컬 보고서에서 의견을 제출한 이벤트는 의견 종류·검토 상태·판정과 무관하게 즉시 제외합니다. DAILY 의견은 저장 근거가 연결된 같은 기간 WEEKLY 전달에도 적용합니다.
            제외 이벤트의 요약·관찰 항목·전용 출처와 공유 근거의 인사이트도 제외하며, 공용 보고서와 검토 기록은 유지합니다. 회사 관심 기준 활성화와는 별도의 전달 규칙입니다.
            저장된 importantEvents 중 sourceFindingIds가 비어 있지 않고 모두 표시 가능한 finding인 이벤트를 저장 순서대로 이메일은 최대 5개, 텔레그램은 최대 3개 사용합니다. 유효한 이벤트가 상한보다 적으면 있는 만큼 표시합니다.
            텔레그램은 제목·요약·확인할 점·근거 원문을 채널 maxLength 안의 HTML 메시지 1개로 구성합니다.
            긴 URL은 원문 링크 수를 2개에서 1개 또는 0개로 줄여 카드 주요 내용과 가능한 전체 보고서 링크를 유지하며, 극소 길이에서만 일반 텍스트로 안전하게 축약합니다.
            이메일은 executiveSummary를 우선한 핵심 요약과 이벤트별 주요 내용·후속 확인·원문을 제공합니다.
            후속 확인은 모든 근거가 표시 가능하고 해당 이벤트와 근거가 연결된 저장 watchItems만 최대 2개 사용합니다.
            이벤트별 원문 링크는 해당 sourceFindingIds에 연결된 안전한 URL을 최대 2개 표시합니다.
            insightAudience를 선택하면 현재 입력·버전과 일치하는 저장 관점 인사이트를 이메일·텔레그램에 짧게 포함합니다. 생략/null이면 기존 본문을 유지합니다.
            최대 2개 overview를 표시합니다. 이메일은 원문 인용·해석 표식과 별도 전제 줄을 생략하고 해석 문장만 표시하며, 텔레그램은 해석·전제를 함께 표시합니다.
            overview가 비어 있으면 근거 있는 이슈별 판단 사유를 최대 2개 사용합니다.
            근거가 모두 표시 가능한 항목만 포함하며 headline과 significance는 표시하지 않습니다. 후속 확인이나 인사이트를 새로 생성하지 않습니다.
            선택한 저장 인사이트가 없으면 COMMON404, 준비 중이면 COMMON409, 잘못된 관점이면 AUDIENCE400을 반환합니다.
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "지원하지 않는 관점입니다. (AUDIENCE400)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일한 리포트 관점 인사이트 생성 요청이 진행 중입니다. 잠시 후 다시 확인해주세요. (COMMON409)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "보고서, 활성 채널 또는 선택한 저장 인사이트 없음",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "보고서 없음", value = """
                                    {"isSuccess":false,"code":"REPORT404","message":"보고서를 찾을 수 없습니다.","result":{}}
                                    """),
                            @ExampleObject(name = "채널 없음", value = """
                                    {"isSuccess":false,"code":"CHANNEL404","message":"알림 채널을 찾을 수 없습니다.","result":{}}
                                    """),
                            @ExampleObject(name = "저장 인사이트 없음", value = """
                                    {"isSuccess":false,"code":"COMMON404","message":"저장된 리포트 관점 인사이트가 없습니다.","result":{}}
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
            이메일은 짧은 HTML 본문과 한글 글꼴을 포함한 상세 PDF를 함께 전달합니다. PDF는 핵심 요약·중요 이벤트·관찰 항목과 선택한 insightAudience 하나의 종합 판단·다음 확인 항목을 담습니다. 중요 이벤트는 제목과 요약만 표시하며 날짜별 분석 근거와 significance 설명은 제외합니다. 기타 분석·수집 상태와 관점 분석의 먼저 살펴볼 이슈·우선순위·중요도 줄·평가 표·조건부 해석은 제외합니다.
            PDF의 회사 실적은 저장된 자료만 사용하며 정적 그래프 아래에 분기별 수치 표를 제공합니다. KRW 매출액·영업이익은 억원 단위로 소수 둘째 자리까지 반올림하고, 0이 아닌 작은 금액은 범위로 표시하여 0·자료 없음과 구분합니다. 공시 목록·표의 근거 공시·관점 분석의 원문 근거 부록은 표시하지 않습니다.
            중요 이벤트·관찰 항목 아래에는 기사 원문 URL을 그대로 표시합니다. 공개 웹 배포 없이 첨부파일을 읽을 수 있습니다.
            관점 분석의 원문 인용과 해석·미확인 가정·반증 조건·관찰 항목이 한 문자열에 있으면 인용문 뒤에 문단 간격을 넣어 구분하며, 인용문과 설명 내용은 보존합니다.
            자동 이메일에도 PDF를 첨부하며 관점 분석은 기존 수동 선택 규칙을 따릅니다. PDF 생성 실패 시 첨부 없이 메일만 전송하지 않습니다. 기사 전문은 포함하지 않습니다.
            로컬 보고서에서 의견을 제출한 이벤트는 의견 종류·검토 상태·판정과 무관하게 이후 새 발송에서 제외합니다. DAILY 의견은 저장 근거가 연결된 같은 기간 WEEKLY 전달에도 적용합니다.
            제외 이벤트의 요약·관찰 항목·전용 출처와 공유 근거의 인사이트도 제외합니다. 회사 관심 기준 활성화와는 별도이며 이미 전송한 내용은 변경하지 않습니다.
            텔레그램 뉴스 카드형·이메일 브리핑형 본문에 저장된 개인 정책 판정을 적용하며 근거 있는 importantEvents를 저장 순서대로 이메일은 최대 5개, 텔레그램은 최대 3개 표시합니다. 유효한 이벤트가 상한보다 적으면 있는 만큼 표시합니다.
            텔레그램은 채널 maxLength 안의 메시지 1개이며, 이메일은 핵심 요약과 이벤트별 주요 내용·후속 확인·원문을 제공합니다.
            insightAudience 선택 시 저장 관점 인사이트를 최대 2개 포함합니다. 이메일은 원문 인용·해석 표식과 별도 전제 줄을 생략하고 해석 문장만 표시하며, 텔레그램은 해석·전제를 함께 표시합니다.
            overview가 비어 있으면 근거 있는 이슈별 판단 사유를 최대 2개 사용합니다.
            짧은 메시지 본문에는 headline과 significance를 표시하지 않고 수신자에게 허용된 근거가 모두 남은 인사이트만 포함합니다. AI를 다시 호출하지 않습니다.
            insightAudience 생략/null 및 자동 발송은 기존 본문을 유지합니다. 선택한 저장 인사이트 없음은 COMMON404, 준비 중은 COMMON409, 잘못된 관점은 AUDIENCE400입니다.
            같은 idempotencyKey로 재요청하면 인사이트를 다시 조회하거나 재발송하지 않고 기존 결과를 반환합니다.
            본문 확보·관련성 필터와 DAILY에 저장된 finding 선택·순서를 유지하며 public-base-url 설정 시 전체 보고서 링크를 추가합니다.
            수신자별 제외 항목은 요약·근거에서도 제거하며 공용 보고서는 변경하지 않습니다. 개인화 준비 중에는 외부 발송 없이 COMMON409를 반환합니다.
            public-base-url 설정 시 수신자·보고서에 묶인 30일 유효 피드백 링크를 추가합니다. 발송 시 AI 호출은 없습니다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발송을 완료했습니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "발송 대상 없음 또는 지원하지 않는 관점입니다. (AUDIENCE400)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "보고서 또는 저장된 리포트 관점 인사이트가 없습니다. (COMMON404)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "개인화 또는 선택한 리포트 관점 인사이트 준비 중입니다. 외부 발송 없이 재시도할 수 있습니다."),
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
