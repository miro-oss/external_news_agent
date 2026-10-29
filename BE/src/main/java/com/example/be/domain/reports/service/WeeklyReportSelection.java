package com.example.be.domain.reports.service;

import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import java.util.List;
import java.util.Set;

/** Remove every dependent section together so a fallback cannot resurrect excluded evidence. */
public final class WeeklyReportSelection {
    private WeeklyReportSelection() { }
    public static WeeklyReportInput withoutFindings(WeeklyReportInput input, Set<Long> excluded) {
        if (excluded.isEmpty()) return input;
        return new WeeklyReportInput(input.reportDate(), input.reportEndDate(), input.sources().stream()
                .map(source -> withoutFindings(source, excluded)).toList(), input.missingReportDates(), input.topicId(), input.topicName());
    }
    public static WeeklyReportInput.DailySource withoutFindings(WeeklyReportInput.DailySource source, Set<Long> excluded) {
        List<Long> allowed = source.reflectedFindingIds().stream().filter(id -> !excluded.contains(id)).toList();
        if (allowed.size() == source.reflectedFindingIds().size()) return source;
        ReportContent content = source.structuredContent();
        if (content != null) {
            Set<Long> initiallyAllowed = Set.copyOf(allowed);
            var events = content.importantEvents().stream()
                    .filter(event -> !event.sourceFindingIds().isEmpty() && initiallyAllowed.containsAll(event.sourceFindingIds())).toList();
            Set<Long> removedSources = content.importantEvents().stream().filter(event -> !events.contains(event))
                    .flatMap(event -> event.sourceFindingIds().stream()).collect(java.util.stream.Collectors.toSet());
            var watches = content.watchItems().stream()
                    .filter(item -> !item.sourceFindingIds().isEmpty() && initiallyAllowed.containsAll(item.sourceFindingIds())
                            && item.sourceFindingIds().stream().noneMatch(removedSources::contains)).toList();
            content.watchItems().stream().filter(item -> !watches.contains(item))
                    .flatMap(item -> item.sourceFindingIds().stream()).forEach(removedSources::add);
            Set<Long> retainedSources = events.stream().flatMap(event -> event.sourceFindingIds().stream())
                    .collect(java.util.stream.Collectors.toSet());
            watches.stream().flatMap(item -> item.sourceFindingIds().stream()).forEach(retainedSources::add);
            // Removed compound events or watches must not leave orphan evidence for later fallback views.
            allowed = allowed.stream().filter(id -> !removedSources.contains(id) || retainedSources.contains(id)).toList();
            content = new ReportContent(events.stream().limit(3).map(ReportContent.ImportantEvent::summaryKo).toList(),
                    events, watches, List.of());
        }
        var evidence = source.evidenceSnapshot();
        if (evidence != null) {
            List<Long> selected = allowed;
            evidence = new ReportComparisonSnapshot(evidence.version(),
                    evidence.issues().stream().filter(issue -> selected.contains(issue.findingId())).toList(), evidence.scopes(), evidence.period(), evidence.sourceConditions());
        }
        return new WeeklyReportInput.DailySource(source.reportId(), source.reportDate(), source.title(), "", content, allowed, evidence,
                source.companySnapshot() == null ? null : source.companySnapshot().reflected(allowed));
    }

}
