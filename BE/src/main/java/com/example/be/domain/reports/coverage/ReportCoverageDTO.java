package com.example.be.domain.reports.coverage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Response-only coverage; never becomes report text, prompt input or a feedback revision. */
public final class ReportCoverageDTO {
    private ReportCoverageDTO() { }

    public enum Status { AVAILABLE, UNAVAILABLE }

    @Schema(name = "ReportCoverage", description = "보고서 입력에 고정한 보도량. 현재 이슈 통계와 별개이며 현재 표시 제외만 반영")
    public record View(
            @Schema(nullable = true, example = "report-coverage.v1") String ruleVersion,
            @Schema(nullable = true, description = "입력 확정 시각. 과거 집계 자료가 없으면 null") OffsetDateTime capturedAt,
            @Schema(description = "실행/집계일의 수집 기준 안내") String periodLabel,
            List<EventCoverage> events, List<FindingCoverage> findings) {
        public View { events = List.copyOf(events); findings = List.copyOf(findings); }
        public EventCoverage forSources(List<Long> ids) {
            var key = normalizedIds(ids);
            return events.stream().filter(event -> normalizedIds(event.sourceFindingIds()).equals(key))
                    .findFirst().orElseGet(() -> unavailable(key));
        }
        public FindingCoverage forFinding(Long id) {
            return findings.stream().filter(finding -> Objects.equals(id, finding.findingId())).findFirst()
                    .orElse(new FindingCoverage(id, Status.UNAVAILABLE, null, null, null, List.of()));
        }
        public View withoutFindings() { return new View(ruleVersion, capturedAt, periodLabel, events, List.of()); }
    }

    @Schema(name = "ReportEventCoverage", description = "sourceFindingIds 집합의 고유 기사 합집합. UNAVAILABLE의 숫자는 null")
    public record EventCoverage(List<Long> sourceFindingIds, Status status,
            @Schema(nullable = true, minimum = "0") Integer articleCount,
            @Schema(nullable = true, minimum = "0", description = "원문 발행 사이트 기준 고유 매체 수. 수집 플랫폼·미확인 출처 제외") Integer publisherCount,
            @Schema(nullable = true, minimum = "0") Integer unknownPublisherArticleCount,
            List<Article> representativeArticles) {
        public EventCoverage {
            sourceFindingIds = List.copyOf(sourceFindingIds);
            representativeArticles = List.copyOf(representativeArticles);
        }
    }

    @Schema(name = "ReportFindingCoverage", description = "보고서에 고정한 분석별 이슈 보도량")
    public record FindingCoverage(Long findingId, Status status,
            @Schema(nullable = true, minimum = "0") Integer articleCount,
            @Schema(nullable = true, minimum = "0", description = "원문 발행 사이트 기준 고유 매체 수. 수집 플랫폼·미확인 출처 제외") Integer publisherCount,
            @Schema(nullable = true, minimum = "0") Integer unknownPublisherArticleCount,
            List<Article> representativeArticles) {
        public FindingCoverage { representativeArticles = List.copyOf(representativeArticles); }
    }

    @Schema(name = "ReportCoverageArticle", description = "대표 보도. 요약의 직접 근거와 구분하며 최대 2건")
    public record Article(Long articleId, String title,
            @Schema(nullable = true) String publisher,
            @Schema(nullable = true) OffsetDateTime publishedAt,
            @Schema(nullable = true, description = "안전한 HTTP(S) 원문 URL, 확인할 수 없으면 null") String canonicalUrl) { }

    public static EventCoverage unavailable(List<Long> ids) {
        return new EventCoverage(normalizedIds(ids), Status.UNAVAILABLE, null, null, null, List.of());
    }

    public static List<Long> normalizedIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }
}
