package com.example.be.domain.analysis.agent.dto;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.WeeklyReportInput;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Only immutable, already generated DAILY content crosses the weekly Agent boundary. */
public record AgentWeeklyReportRequest(
        String idempotencyKey, AgentPlan plan, Long reportId,
        LocalDate reportDate, LocalDate reportEndDate, List<DailySource> sources,
        List<LocalDate> missingReportDates, List<String> sourceNotes) {
    public record DailySource(Long reportId, LocalDate reportDate, String title,
                              ReportContent structuredContent, List<Long> reflectedFindingIds,
                              Map<Long, Long> issueIdsByFinding) { }

    public static AgentWeeklyReportRequest from(String key, AgentPlan plan, Long reportId,
                                                WeeklyReportInput input) {
        return new AgentWeeklyReportRequest(key, plan, reportId, input.reportDate(), input.reportEndDate(),
                input.sources().stream().map(source -> new DailySource(source.reportId(), source.reportDate(),
                        source.title(), source.structuredContent(), source.reflectedFindingIds(), issueIds(source))).toList(),
                input.missingReportDates(), input.sourceNotes());
    }
    private static Map<Long, Long> issueIds(WeeklyReportInput.DailySource source) {
        if (source.evidenceSnapshot() == null) return Map.of();
        return source.evidenceSnapshot().issues().stream()
                .filter(issue -> source.reflectedFindingIds().contains(issue.findingId()) && issue.side() != null)
                .collect(Collectors.toMap(issue -> issue.findingId(), issue -> issue.side().issueId(), (a, b) -> a));
    }
}
