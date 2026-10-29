package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.keyword.ReportKeywordDTO;
import com.example.be.domain.reports.keyword.ReportKeywordService;
import com.example.be.domain.reports.keyword.ReportKeywordSnapshot;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportKeywordChangesService {
    private final ReportKeywordService keywords;
    private final ReportComparisonCriteria criteria;

    public ReportChanges.KeywordChanges project(NewsReport previous, NewsReport current, ComparisonWork work,
            ReportEventFeedbackProjection.View previousView, ReportEventFeedbackProjection.View currentView,
            boolean delivery) {
        if (previous == null) return unavailable("NO_BASELINE");
        if (work == null || work.previous() == null || work.current() == null
                || previous.getReportKeywordSnapshot() == null || current.getReportKeywordSnapshot() == null)
            return unavailable("NO_SNAPSHOT");
        String incompatible = incompatible(work.previous(), work.current());
        if (incompatible != null) return unavailable(incompatible);
        var priorSnapshot = previous.getReportKeywordSnapshot();
        var currentSnapshot = current.getReportKeywordSnapshot();
        if ("COMPANY_SCOPE_UNVERIFIED".equals(priorSnapshot.unavailableReason())
                || "COMPANY_SCOPE_UNVERIFIED".equals(currentSnapshot.unavailableReason()))
            return unavailable("CONDITIONS_UNVERIFIED");
        if (!coversCandidates(work.previous(), priorSnapshot) || !coversCandidates(work.current(), currentSnapshot))
            return unavailable("CONDITIONS_UNVERIFIED");
        if (!Objects.equals(priorSnapshot.ruleVersion(), currentSnapshot.ruleVersion())
                || !definitions(priorSnapshot).equals(definitions(currentSnapshot))) return unavailable("CONDITIONS_CHANGED");
        // A failed read is not proof that neither report used company criteria.
        if (!previous.isCompanySelectionRecorded() || !current.isCompanySelectionRecorded())
            return unavailable("CONDITIONS_UNVERIFIED");
        if (criteria.hasMixedSourceCriteria(previous) || criteria.hasMixedSourceCriteria(current)
                || !criteria.applied(previous).equals(criteria.applied(current))) return unavailable("CONDITIONS_CHANGED");
        var prior = keywords.aggregate(previous, previousView, delivery);
        var now = keywords.aggregate(current, currentView, delivery);
        if (prior.view().status() != ReportKeywordDTO.Status.AVAILABLE || now.view().status() != ReportKeywordDTO.Status.AVAILABLE)
            return unavailable("NO_SNAPSHOT");
        // An exclusion can change the universe without appearing in the final top-N report view.
        if (prior.filtered() || now.filtered()) return unavailable("FILTERED_INPUT");
        int before = prior.view().denominatorIssueCount();
        int after = now.view().denominatorIssueCount();
        if (before == 0 || after == 0) return new ReportChanges.KeywordChanges(ReportChanges.KeywordStatus.UNAVAILABLE,
                "EMPTY_SAMPLE", message("EMPTY_SAMPLE"), before, after, true, List.of());
        if (!prior.counts().keySet().equals(now.counts().keySet())) return unavailable("CONDITIONS_CHANGED");
        var items = now.counts().keySet().stream().map(key -> {
            var old = prior.counts().get(key);
            var value = now.counts().get(key);
            // Round the difference of the published one-decimal percentages; never divide by the old count.
            double delta = Math.round((value.sharePercent() - old.sharePercent()) * 10.0) / 10.0;
            return new ReportChanges.KeywordChange(value.label(), old.count(), value.count(),
                    old.sharePercent(), value.sharePercent(), delta);
        }).sorted(Comparator.<ReportChanges.KeywordChange>comparingDouble(item -> Math.abs(item.changePercentagePoints()))
                .reversed().thenComparing(Comparator.comparingInt(ReportChanges.KeywordChange::currentIssueCount).reversed())
                .thenComparing(ReportChanges.KeywordChange::keyword)).limit(6).toList();
        boolean lowSample = Math.min(before, after) < 5;
        String unit = current.getReportScope() == ReportScope.RUN ? "실행" : "기간";
        return new ReportChanges.KeywordChanges(ReportChanges.KeywordStatus.AVAILABLE, null,
                lowSample ? "표본이 5개 미만인 " + unit + "이 있어 작은 건수 차이에도 비중이 크게 달라질 수 있습니다."
                        : "같은 조건으로 수집한 두 " + unit + "의 고유 이슈 비중을 비교했습니다. 시장 전체의 증감을 뜻하지 않습니다.",
                before, after, lowSample, items);
    }

    private static boolean coversCandidates(ReportComparisonSnapshot conditions, ReportKeywordSnapshot snapshot) {
        if (snapshot.candidates() == null) return false;
        Set<RunTopic> covered = conditions.sourceConditions().stream()
                .filter(source -> source.runId() != null && source.topicId() != null)
                .map(source -> new RunTopic(source.runId(), source.topicId())).collect(Collectors.toSet());
        return snapshot.candidates().stream().allMatch(candidate -> candidate.runId() != null && candidate.topicId() != null
                && covered.contains(new RunTopic(candidate.runId(), candidate.topicId())));
    }

    private static Set<ReportKeywordSnapshot.Definition> definitions(ReportKeywordSnapshot snapshot) {
        return snapshot.definitions() == null ? Set.of() : Set.copyOf(snapshot.definitions());
    }

    static String incompatible(ReportComparisonSnapshot previous, ReportComparisonSnapshot current) {
        var before = previous.period();
        var after = current.period();
        if (before == null || after == null || previous.sourceConditions() == null || current.sourceConditions() == null
                || previous.sourceConditions().isEmpty() || current.sourceConditions().isEmpty()) return "CONDITIONS_UNVERIFIED";
        if (!previous.hasKnownScopes() || !current.hasKnownScopes()) return "CONDITIONS_UNVERIFIED";
        if (before.scope() != after.scope() || !Objects.equals(before.topicId(), after.topicId())
                || !Objects.equals(before.durationDays(), after.durationDays())
                || !previous.scopesByTopic().equals(current.scopesByTopic())) return "CONDITIONS_CHANGED";
        if (previous.scopesByTopic().values().stream().anyMatch(values -> values.size() != 1)
                || current.scopesByTopic().values().stream().anyMatch(values -> values.size() != 1)) return "CONDITIONS_CHANGED";
        if (before.missingDates() == null || after.missingDates() == null) return "CONDITIONS_UNVERIFIED";
        if (!before.missingDates().isEmpty() || !after.missingDates().isEmpty()) return "INCOMPLETE_PERIOD";
        if (after.scope() == ReportScope.RUN) {
            if (before.runStartedAt() == null || after.runStartedAt() == null || before.runId() == null || after.runId() == null)
                return "CONDITIONS_UNVERIFIED";
        } else if (before.durationDays() == null || before.durationDays() < 1 || before.from() == null || before.through() == null
                || after.from() == null || after.through() == null) return "CONDITIONS_UNVERIFIED";
        var conditions = java.util.stream.Stream.concat(previous.sourceConditions().stream(), current.sourceConditions().stream()).toList();
        if (conditions.stream().anyMatch(condition -> condition.runId() == null || condition.topicId() == null
                || condition.sourceId() == null || condition.fingerprint() == null || condition.fingerprint().isBlank()))
            return "CONDITIONS_UNVERIFIED";
        if (conditions.stream().anyMatch(condition -> !condition.complete())) return "INCOMPLETE_PERIOD";
        var oldSources = sources(previous);
        var newSources = sources(current);
        if (oldSources.values().stream().anyMatch(values -> values.size() != 1)
                || newSources.values().stream().anyMatch(values -> values.size() != 1)
                || !oldSources.equals(newSources)) return "CONDITIONS_CHANGED";
        if (!oldSources.keySet().equals(previous.scopesByTopic().keySet())
                || !newSources.keySet().equals(current.scopesByTopic().keySet())) return "CONDITIONS_UNVERIFIED";
        return null;
    }

    private static Map<Long, Set<Set<Source>>> sources(ReportComparisonSnapshot snapshot) {
        Map<RunTopic, Set<Source>> byRun = new HashMap<>();
        snapshot.sourceConditions().forEach(condition -> byRun.computeIfAbsent(new RunTopic(condition.runId(), condition.topicId()),
                ignored -> new HashSet<>()).add(new Source(condition.sourceId(), condition.fingerprint())));
        return byRun.entrySet().stream().collect(Collectors.groupingBy(entry -> entry.getKey().topicId(),
                Collectors.mapping(entry -> Set.copyOf(entry.getValue()), Collectors.toSet())));
    }

    public static ReportChanges.KeywordChanges unavailable(String reason) {
        return new ReportChanges.KeywordChanges(ReportChanges.KeywordStatus.UNAVAILABLE, reason, message(reason),
                null, null, false, List.of());
    }

    private static String message(String reason) {
        return switch (reason) {
            case "NO_BASELINE" -> "비교할 이전 보고서가 없습니다.";
            case "NO_SNAPSHOT" -> "두 기간의 저장된 키워드 집계 자료를 확인할 수 없습니다.";
            case "CONDITIONS_CHANGED" -> "키워드·수집 조건이나 집계 기간이 달라 증감 수치를 비교하지 않았습니다.";
            case "CONDITIONS_UNVERIFIED" -> "두 기간의 수집 조건을 확인할 자료가 없어 증감 수치를 제공하지 않습니다.";
            case "INCOMPLETE_PERIOD" -> "입력이 없는 날짜나 완료되지 않은 수집이 있어 증감 수치를 제공하지 않습니다.";
            case "EMPTY_SAMPLE" -> "유효 이슈가 없는 기간이 있어 비중 차이를 계산하지 않았습니다.";
            case "PERSONALIZATION_UNVERIFIED" -> "이전 보고서의 개인 기준 적용을 확인할 수 없어 변화 비교를 생략했습니다.";
            case "FILTERED_INPUT" -> "제외된 근거나 표시할 수 없는 자료가 있어 변화 비교를 생략했습니다.";
            default -> "변화 비교가 준비되면 키워드 변화를 확인할 수 있습니다.";
        };
    }

    private record Source(long sourceId, String fingerprint) { }
    private record RunTopic(long runId, long topicId) { }
}
