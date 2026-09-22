package com.example.be.domain.reports.service;

import com.example.be.domain.reports.entity.ReportContent;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.entity.WeeklyReportInput;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Source-preserving fallback groups repeated evidence and keeps dated observations without new claims. */
@Component
public class WeeklyReportGenerator {
    public static final String MODEL_NAME = "safe-weekly-report-v1";

    public ReportDocument generate(WeeklyReportInput input) {
        List<EventGroup> groups = new ArrayList<>();
        Map<String, WatchGroup> watches = new LinkedHashMap<>();
        for (var source : input.sources()) {
            ReportContent content = source.structuredContent();
            if (content == null) continue;
            Set<Long> allowedIds = Set.copyOf(source.reflectedFindingIds());
            for (var event : content.importantEvents()) {
                List<Long> ids = allowedIds.containsAll(event.sourceFindingIds()) ? event.sourceFindingIds().stream().distinct().toList() : List.of();
                if (ids.isEmpty() || blank(event.title()) || blank(event.summaryKo())) continue;
                String key = normalized(event.title()) + "|" + normalized(event.summaryKo());
                Set<Long> issueIds = source.evidenceSnapshot() == null ? Set.of() : source.evidenceSnapshot().issues().stream()
                        .filter(issue -> ids.contains(issue.findingId()) && issue.side() != null && issue.side().issueId() > 0)
                        .map(issue -> issue.side().issueId())
                        .collect(java.util.stream.Collectors.toSet());
                List<EventGroup> matches = groups.stream().filter(group -> group.matches(key, ids, issueIds)).toList();
                if (issueIds.isEmpty() && conflictingKnownIdentities(matches)) matches = List.of();
                EventGroup group = matches.isEmpty() ? new EventGroup() : matches.getFirst();
                if (matches.isEmpty()) groups.add(group);
                for (int index = 1; index < matches.size(); index++) {
                    group.merge(matches.get(index));
                    groups.remove(matches.get(index));
                }
                group.add(source.reportDate(), event, key, ids, issueIds);
            }
            for (var item : content.watchItems()) {
                List<Long> ids = allowedIds.containsAll(item.sourceFindingIds()) ? item.sourceFindingIds().stream().distinct().toList() : List.of();
                if (ids.isEmpty() || blank(item.topic()) || blank(item.reason())) continue;
                watches.computeIfAbsent(normalized(item.topic()) + "|" + normalized(item.reason()),
                        key -> new WatchGroup(item.topic(), item.reason())).ids.addAll(ids);
            }
        }
        List<EventGroup> selected = groups.stream().sorted(Comparator
                .comparingInt(EventGroup::dayCount).reversed()
                .thenComparing(EventGroup::latestDate, Comparator.reverseOrder())).limit(5).toList();
        List<ReportContent.ImportantEvent> events = selected.stream().map(EventGroup::toEvent).toList();
        List<String> summaries = selected.stream().limit(3).map(EventGroup::latestSummary).toList();
        if (summaries.isEmpty()) {
            summaries = List.of("이 기간의 일일 통합 보고서에서 기사 근거가 연결된 주요 이슈를 확인하지 못했습니다.");
        }
        List<ReportContent.WatchItem> watchItems = watches.values().stream().limit(5)
                .map(item -> new ReportContent.WatchItem(item.topic, item.reason, List.copyOf(item.ids))).toList();
        List<String> notes = new ArrayList<>(input.sourceNotes());
        notes.add("자동 요약을 사용할 수 없어 일일 보고서의 검증된 내용을 근거와 날짜별로 묶었습니다.");
        if (groups.size() > selected.size()) {
            notes.add("주요 이슈 " + selected.size() + "개를 표시했습니다. 나머지 내용은 연결된 일일 통합 보고서에서 확인할 수 있습니다.");
        }
        ReportContent content = new ReportContent(summaries, events, watchItems, notes);
        LinkedHashSet<Long> reflected = new LinkedHashSet<>();
        events.forEach(event -> reflected.addAll(event.sourceFindingIds()));
        watchItems.forEach(item -> reflected.addAll(item.sourceFindingIds()));
        return new ReportDocument(input.title(), markdown(input, content), MODEL_NAME,
                null, null, null, null, null, null, ReportStatus.FALLBACK,
                List.copyOf(reflected), List.of(), content);
    }

    private String markdown(WeeklyReportInput input, ReportContent content) {
        StringBuilder body = new StringBuilder("# ").append(ReportMarkdown.text(input.title()))
                .append("\n\n## 이번 주 핵심\n\n");
        content.executiveSummary().forEach(summary -> body.append("- ").append(ReportMarkdown.text(summary)).append("\n"));
        body.append("\n## 주요 이슈와 주간 흐름\n");
        content.importantEvents().forEach(event -> body.append("\n### ").append(ReportMarkdown.text(event.title()))
                .append("\n\n").append(ReportMarkdown.text(event.summaryKo())).append("\n\n")
                .append(ReportMarkdown.text(event.significance())).append("\n"));
        if (!content.watchItems().isEmpty()) {
            body.append("\n## 후속 관찰 항목\n\n");
            content.watchItems().forEach(item -> body.append("- ").append(ReportMarkdown.text(item.topic()))
                    .append(": ").append(ReportMarkdown.text(item.reason())).append("\n"));
        }
        body.append("\n## 일일 통합 보고서 출처\n\n");
        input.sources().forEach(source -> body.append("- ").append(source.reportDate())
                .append(" · 보고서 #").append(source.reportId()).append("\n"));
        body.append("\n## 수집 및 출처 참고\n\n");
        content.sourceNotes().forEach(note -> body.append("- ").append(ReportMarkdown.text(note)).append("\n"));
        return body.toString();
    }

    private static boolean conflictingKnownIdentities(List<EventGroup> groups) {
        List<Set<Long>> known = groups.stream().map(group -> group.issueIds).filter(ids -> !ids.isEmpty()).toList();
        for (int left = 0; left < known.size(); left++) {
            for (int right = left + 1; right < known.size(); right++) {
                if (java.util.Collections.disjoint(known.get(left), known.get(right))) return true;
            }
        }
        return false;
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String normalized(String text) { return text.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT); }

    private record Observation(LocalDate date, String summary) { }

    private static class EventGroup {
        private String title;
        private String significance;
        private final Set<String> keys = new LinkedHashSet<>();
        private final Set<Long> ids = new LinkedHashSet<>();
        private final Set<Long> issueIds = new LinkedHashSet<>();
        private final List<Observation> observations = new ArrayList<>();

        boolean matches(String key, List<Long> candidates, Set<Long> issues) {
            if (!issueIds.isEmpty() && !issues.isEmpty()) return issues.stream().anyMatch(issueIds::contains);
            if (candidates.stream().anyMatch(ids::contains)) return true;
            // An unidentified legacy row must not bridge two different saved identities by generic wording.
            return issueIds.isEmpty() && issues.isEmpty() && keys.contains(key);
        }
        void add(LocalDate date, ReportContent.ImportantEvent event, String key, List<Long> references, Set<Long> issues) {
            title = event.title();
            significance = event.significance();
            keys.add(key);
            ids.addAll(references);
            issueIds.addAll(issues);
            var observation = new Observation(date, event.summaryKo());
            if (!observations.contains(observation)) observations.add(observation);
        }
        void merge(EventGroup other) {
            keys.addAll(other.keys);
            ids.addAll(other.ids);
            issueIds.addAll(other.issueIds);
            other.observations.stream().filter(value -> !observations.contains(value)).forEach(observations::add);
        }
        int dayCount() { return (int) observations.stream().map(Observation::date).distinct().count(); }
        LocalDate latestDate() { return observations.stream().map(Observation::date).max(LocalDate::compareTo).orElseThrow(); }
        String latestSummary() { return observations.stream().max(Comparator.comparing(Observation::date)).orElseThrow().summary(); }
        ReportContent.ImportantEvent toEvent() {
            String summary = observations.stream().sorted(Comparator.comparing(Observation::date))
                    .map(observation -> observation.date() + ": " + observation.summary())
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            return new ReportContent.ImportantEvent(title, summary,
                    blank(significance) ? "일일 통합 보고서 " + dayCount() + "일분에 포함된 이슈입니다." : significance,
                    List.copyOf(ids));
        }
    }

    private static class WatchGroup {
        private final String topic;
        private final String reason;
        private final Set<Long> ids = new LinkedHashSet<>();
        WatchGroup(String topic, String reason) { this.topic = topic; this.reason = reason; }
    }
}
