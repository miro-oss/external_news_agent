package com.example.be.domain.issues.controller;

import com.example.be.domain.issues.dto.res.IssueResDTO;
import com.example.be.domain.issues.service.IssueQueryService;
import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/issues")
@Tag(name = "이슈", description = "같은 사건을 다룬 기사 묶음 조회 API")
public class IssueController {

    private final IssueQueryService issueQueryService;

    @GetMapping("/{issueId}")
    @Operation(summary = "이슈 상세 조회", description = "대표 분석과 출처가 보존된 관련 기사, "
            + "견해 포함 기사의 논조 분포(toneDistribution)를 조회합니다. 논조는 조회 시점의 최신 분석 기준입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공입니다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "이슈가 존재하지 않는 경우",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = """
                            {"isSuccess":false,"code":"ISSUE404","message":"이슈를 찾을 수 없습니다.","result":{}}
                            """)))
    })
    public ApiResponse<IssueResDTO.Detail> getIssue(
            @Parameter(description = "이슈 ID") @PathVariable Long issueId) {
        return ApiResponse.of(GeneralSuccessCode.OK, issueQueryService.getIssue(issueId));
    }
}
