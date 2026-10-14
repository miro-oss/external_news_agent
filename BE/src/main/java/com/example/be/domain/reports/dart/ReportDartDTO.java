package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.DartModels.Company;
import com.example.be.domain.dart.DartModels.FinancialData;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public final class ReportDartDTO {
    private ReportDartDTO() { }

    @Schema(name = "ReportDartResponse", description = "실행 당시 선택한 회사의 보고서별 공시·실적 자료. RUN 외 범위와 회사 미선택은 빈 배열")
    public record Result(Long reportId, List<CompanyAttachment> companies) { }

    @Schema(name = "ReportDartCompanyAttachment")
    public record CompanyAttachment(Company company,
            @Schema(allowableValues = {"PENDING", "READY", "PARTIAL", "FAILED", "DISABLED"}) String status,
            String message,
            @Schema(description = "READY/PARTIAL만 저장 자료 제공. fetchedAt은 실제 자료 확인 시각이며 과거 공시 상태를 소급 보장하지 않음", nullable = true)
            FinancialData data) { }
}
