package com.example.be.domain.reports.entity;

import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reservation-time copies of completed DAILY reports; recovery never reads mutable findings or reports. */
public record WeeklyReportInput(
        LocalDate reportDate,
        LocalDate reportEndDate,
        List<DailySource> sources,
        List<LocalDate> missingReportDates) {
    public WeeklyReportInput {
        sources = List.copyOf(sources);
        missingReportDates = List.copyOf(missingReportDates);
    }

    public record DailySource(Long reportId, LocalDate reportDate, String title, String markdownBody,
                              ReportContent structuredContent, List<Long> reflectedFindingIds, ReportComparisonSnapshot evidenceSnapshot) {
        public DailySource { reflectedFindingIds = List.copyOf(reflectedFindingIds); }
        public DailySource(Long reportId, LocalDate reportDate, String title, String markdownBody,
                           ReportContent structuredContent, List<Long> reflectedFindingIds) {
            this(reportId, reportDate, title, markdownBody, structuredContent, reflectedFindingIds, null);
        }
        public static DailySource from(NewsReport report) {
            return new DailySource(report.getId(), report.getReportDate(), report.getTitle(),
                    report.getMarkdownBody(), report.getStructuredContent(), report.getReflectedFindingIds());
        }
    }

    public String title() { return reportDate + " ~ " + reportEndDate + " 주간 통합 뉴스 보고서"; }

    public List<Long> sourceFindingIds() {
        return sources.stream().flatMap(source -> source.reflectedFindingIds().stream()).distinct().toList();
    }

    public List<String> sourceNotes() {
        List<String> notes = new ArrayList<>();
        notes.add(reportDate + " ~ " + reportEndDate + " 한국 시간 월요일부터 일요일까지의 일일 통합 보고서 "
                + sources.size() + "개를 바탕으로 작성했습니다.");
        notes.add("일일 통합 보고서에 선정된 내용과 저장된 근거만 사용하므로 해당 기간의 모든 기사를 포함하지 않습니다.");
        if (!missingReportDates.isEmpty()) {
            notes.add("일일 통합 보고서가 없는 날짜: " + String.join(", ",
                    missingReportDates.stream().map(LocalDate::toString).toList()) + ". 해당 날짜의 내용은 포함하지 않았습니다.");
        }
        if (sources.stream().anyMatch(source -> source.structuredContent() == null)) {
            notes.add("구조화 내용이 저장되지 않은 과거 일일 보고서는 원본 목록에만 포함하고 주간 요약 근거에서 제외했습니다.");
        }
        sources.stream().filter(source -> source.structuredContent() != null)
                .flatMap(source -> source.structuredContent().sourceNotes().stream()).distinct().forEach(notes::add);
        return List.copyOf(notes);
    }
}
