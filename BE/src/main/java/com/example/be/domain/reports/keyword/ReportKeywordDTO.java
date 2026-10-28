package com.example.be.domain.reports.keyword;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/** A shared, already filtered reading model for the web, email and PDF. */
public final class ReportKeywordDTO {
    private ReportKeywordDTO() { }

    public enum Status { AVAILABLE, UNAVAILABLE }

    @Schema(name = "ReportKeywordStatistics", description = "선정 전 유효 고유 이슈의 등록 키워드 비중. 기사 반복 횟수와 다르며 비중 합은 100%를 넘을 수 있음")
    public record View(
            Status status,
            @Schema(nullable = true, allowableValues = {"NO_SNAPSHOT", "INCOMPLETE_SOURCE", "MISSING_COLLECTION_CONTEXT", "INVALID_SNAPSHOT", "COMPANY_SCOPE_UNVERIFIED", "PERSONALIZATION_UNVERIFIED"})
            String unavailableReason,
            @Schema(nullable = true, example = "report-keywords.v1") String ruleVersion,
            @Schema(nullable = true, description = "집계 입력 고정 시각. 현재 조회 시각이 아님") OffsetDateTime capturedAt,
            String periodLabel,
            @Schema(nullable = true, minimum = "0", description = "최종 상위 개수 선정 전 유효 고유 이슈 수. UNAVAILABLE이면 null") Integer denominatorIssueCount,
            @Schema(description = "관련 이슈 수 내림차순·정규화 키 오름차순 최대 6개") List<Item> items,
            @Schema(minimum = "0", description = "6개 제한으로 표시하지 않은 등록 키워드 수") int omittedKeywordCount) {
        public View { items = List.copyOf(items); }
    }

    @Schema(name = "ReportKeywordItem")
    public record Item(String keyword, @Schema(minimum = "0") int issueCount,
                       @Schema(nullable = true, minimum = "0", maximum = "100", description = "고유 이슈 비중(%), 소수 첫째 자리. 분모 0이면 null") Double sharePercent,
                       @Schema(description = "동일 이슈에서 함께 확인된 등록 키워드 최대 3개. 인과·의미 유사도 아님") List<RelatedKeyword> relatedKeywords) {
        public Item { relatedKeywords = List.copyOf(relatedKeywords); }
    }

    public record RelatedKeyword(String keyword, @Schema(minimum = "1", description = "두 키워드가 함께 확인된 고유 이슈 수") int issueCount) { }
}
