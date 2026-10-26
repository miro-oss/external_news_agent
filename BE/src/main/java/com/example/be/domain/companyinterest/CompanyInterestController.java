package com.example.be.domain.companyinterest;

import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.example.be.domain.companyinterest.CompanyInterestModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/news/company-interests")
@Tag(name = "회사 관심 기준", description = "의견 검토 초안과 회사 공통 보고서 선정 기준")
public class CompanyInterestController {
    private final CompanyInterestService service;

    @GetMapping
    @Operation(summary = "회사 관심 기준 조회", description = "로컬 PoC API. 최신 생성순 criteria를 반환하며 AI 호출·DB 쓰기는 없습니다. "
            + "topics는 의견 제출 당시 이벤트의 주제 선택지이며 comment는 원래 의견 전체입니다. "
            + "DRAFT는 적용 확인 대기, ACTIVE는 향후 선정에 사용, REVOKED는 사용 중지입니다. "
            + "lastApplication은 현재 버전으로 실제 평가한 완료 보고서의 기록이며 활성화만으로 생성되지 않습니다. "
            + "실패·모의 평가는 반영으로 표시하지 않으며 suppressedCount=0도 구분합니다. 날짜는 ISO8601 오프셋 포함. "
            + "200 COMMON200: 성공입니다. 500 COMMON500: 서버 내부 오류입니다.")
    public ResponseEntity<ApiResponse<Criteria>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.OK, service.list()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "회사 관심 기준 수정·활성화·중지", description = "로컬 PoC API. 양수 id/version, trim 후 1~500자 instruction, "
            + "DRAFT/ACTIVE/REVOKED status를 받습니다. topicId는 원래 이벤트 topics 중 하나이며 ACTIVE에서 필수, 나머지는 null 허용합니다. "
            + "문구·주제·상태를 원자적으로 변경하고 버전을 올립니다. 현재 버전의 동일 요청은 변경 없이 반환합니다. "
            + "오래된 버전 또는 주제별 활성 20개 초과는 409입니다. ACTIVE 응답은 활성화 성공이며 실제 보고서 반영 완료가 아닙니다. "
            + "200 COMMON200: 성공입니다. 400 COMMON400: 입력값 검증 실패입니다. "
            + "404 COMMON404: 리소스를 찾을 수 없습니다. 409 COMMON409: 상태 충돌이 발생했습니다.")
    public ResponseEntity<ApiResponse<Criterion>> update(@PathVariable long id, @RequestBody Update request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(GeneralSuccessCode.OK, service.update(id, request)));
    }
}
