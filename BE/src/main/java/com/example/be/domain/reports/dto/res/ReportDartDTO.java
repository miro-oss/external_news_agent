package com.example.be.domain.reports.dto.res;

import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public final class ReportDartDTO {
    private ReportDartDTO() { }

    @Schema(name = "ReportDartResponse", description = "RUN 접수 당시 선택한 회사 또는 주제별 WEEKLY 생성 요청 당시 연결한 회사의 공시·실적 자료. 전체 DAILY/WEEKLY·회사 미선택·회사 스냅샷 없는 이전 보고서는 빈 배열")
    public record Result(Long reportId, List<CompanyAttachment> companies) { }

    @Schema(name = "ReportDartCompanyAttachment")
    public record CompanyAttachment(Company company,
            @Schema(allowableValues = {"PENDING", "READY", "PARTIAL", "FAILED", "DISABLED"}) String status,
            String message,
            @Schema(description = "READY/PARTIAL만 저장 자료 제공. fetchedAt은 실제 자료 확인 시각이며 과거 공시 상태를 소급 보장하지 않음", nullable = true)
            FinancialData data) { }
}
