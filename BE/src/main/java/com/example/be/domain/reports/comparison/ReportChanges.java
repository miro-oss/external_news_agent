package com.example.be.domain.reports.comparison;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(name = "ReportChanges", description = "일일 보고서 간 저장된 변화와 양쪽 근거. 조회는 LLM을 호출하지 않음")
public record ReportChanges(long reportId, LocalDate reportDate, Long baseReportId, LocalDate baseReportDate,
                            Status status, String message, boolean scopeChanged, List<String> notes,
                            List<Item> items) {
    public ReportChanges { notes = List.copyOf(notes); items = List.copyOf(items); }

    public enum Status {
        PENDING("보고서 변화 비교를 준비하고 있습니다."),
        RUNNING("지난 보고서와 달라진 점을 확인하고 있습니다."),
        READY("지난 보고서와의 비교가 완료되었습니다."),
        FAILED("변화 비교를 완료하지 못했습니다. 보고서 본문은 확인할 수 있습니다."),
        NO_BASELINE("비교할 이전 일일 보고서가 없습니다."),
        UNAVAILABLE("비교 시점의 저장 자료가 없어 변화 비교를 제공할 수 없습니다."),
        NOT_APPLICABLE("일일 통합 보고서에서 변화 비교를 제공합니다.");
        public final String message;
        Status(String message) { this.message = message; }
    }
    public enum Type { NEWLY_INCLUDED, UPDATED, REFUTATION, UNCHANGED, UNDETERMINED }
    public record Item(String id, Type type, String title, String summary, Side previous, Side current) { }
    public record Side(long issueId, long topicId, String title, String summary, List<Claim> claims) {
        public Side { claims = List.copyOf(claims); }
    }
    public record Claim(String id, String text, List<Evidence> evidence) {
        public Claim { evidence = List.copyOf(evidence); }
    }
    public record Evidence(long findingId, long articleId, long runId, String articleTitle,
                           String canonicalUrl, int sentenceIndex, String text) { }

    public ReportChanges withStatus(Status value) {
        return new ReportChanges(reportId, reportDate, baseReportId, baseReportDate, value, value.message,
                scopeChanged, notes, value == Status.READY ? items : List.of());
    }
}
