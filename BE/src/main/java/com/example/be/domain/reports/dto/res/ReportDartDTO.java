package com.example.be.domain.reports.dto.res;

import com.example.be.domain.dart.model.DartModels.Company;
import com.example.be.domain.dart.model.DartModels.FinancialData;
import com.example.be.domain.dart.model.DartModels.Filing;
import com.example.be.domain.reports.dart.ReportIssueCompanyProjection;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public final class ReportDartDTO {
    private ReportDartDTO() { }

    @Schema(name = "ReportDartResponse", description = "명시 선택 회사와 신규 보고서 기사에서 확인하여 고정한 회사의 저장 공시·실적. 현재 표시 가능한 근거만 반환하며 과거 자동 연결은 소급하지 않음")
    public record Result(Long reportId, List<CompanyAttachment> companies) { }

    @Schema(name = "ReportDartCompanyAttachment")
    public record CompanyAttachment(Company company,
            @Schema(allowableValues = {"PENDING", "READY", "PARTIAL", "FAILED", "DISABLED"}) String status,
            String message,
            @Schema(description = "READY/PARTIAL만 저장 자료 제공. fetchedAt은 실제 자료 확인 시각이며 과거 공시 상태를 소급 보장하지 않음", nullable = true)
            FinancialData data,
            @Schema(description = "회사 식별 당시 고정한 기사 근거 중 현재 표시 가능한 연결") List<IssueLink> issueLinks,
            @Schema(description = "저장 최근 공시와 분기 실적 출처 합집합. 기사 접수번호 직접 참조와 회사 참고를 구분") List<FilingRow> filingRows) {
        public CompanyAttachment {
            issueLinks = issueLinks == null ? List.of() : List.copyOf(issueLinks);
            filingRows = filingRows == null ? List.of() : List.copyOf(filingRows);
        }

        public CompanyAttachment(Company company, String status, String message, FinancialData data) {
            this(company, status, message, data, List.of(), ReportIssueCompanyProjection.filingRows(data, List.of()));
        }
    }

    public record IssueLink(long findingId, long articleId, Long issueId, String matchedName,
                            String evidenceText, String evidenceUrl, List<String> rceptNos) {
        public IssueLink { rceptNos = rceptNos == null ? List.of() : List.copyOf(rceptNos); }
    }

    public record FilingRow(Filing filing,
                            @Schema(description = "RECENT: 최근 공시, FINANCIAL_SOURCE: 분기 실적 출처") List<String> roles,
                            @Schema(allowableValues = {"DIRECT_REFERENCE", "COMPANY_REFERENCE"}) String relevance,
                            List<Long> sourceFindingIds) {
        public FilingRow {
            roles = List.copyOf(roles);
            sourceFindingIds = List.copyOf(sourceFindingIds);
        }
    }
}
