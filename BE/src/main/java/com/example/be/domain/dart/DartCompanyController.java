package com.example.be.domain.dart;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/dart")
@Tag(name = "DART", description = "공시 회사 검색")
public class DartCompanyController {
    private final DartCompanyService companies;

    @GetMapping("/companies")
    @Operation(summary = "DART 공시 회사 검색", description = "동기화한 법인명·종목코드를 검색합니다. 조회 시 외부 DART를 호출하지 않습니다. READY/PREPARING/DISABLED/FAILED 상태와 공통 페이징을 반환합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공입니다.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "READY", value = """
                                    {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"status":"READY","message":"공시 회사를 검색했습니다.","content":[{"corpCode":"00126380","corpName":"삼성전자","stockCode":"005930"}],"page":0,"size":20,"totalElements":1,"totalPages":1,"hasNext":false}}
                                    """),
                            @ExampleObject(name = "DISABLED", value = """
                                    {"isSuccess":true,"code":"COMMON200","message":"성공입니다.","result":{"status":"DISABLED","message":"DART API 키가 설정되지 않았습니다.","content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"hasNext":false}}
                                    """)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "query·page·size 입력 오류",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "query", value = """
                                    {"isSuccess":false,"code":"COMMON400","message":"query는 2자 이상 100자 이하여야 합니다.","result":{}}
                                    """),
                            @ExampleObject(name = "page", value = """
                                    {"isSuccess":false,"code":"COMMON400","message":"page는 0 이상이어야 합니다.","result":{}}
                                    """),
                            @ExampleObject(name = "size", value = """
                                    {"isSuccess":false,"code":"COMMON400","message":"size는 1 이상 100 이하여야 합니다.","result":{}}
                                    """)
                    }))
    })
    public ApiResponse<DartCompanyService.SearchResult> search(
            @Parameter(description = "법인명 또는 종목코드, trim 후 2~100자", required = true)
            @RequestParam(required = false) String query,
            @Parameter(description = "0부터 시작하는 페이지") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "1~100, 기본 20") @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(GeneralSuccessCode.OK, companies.search(query, page, size));
    }
}
