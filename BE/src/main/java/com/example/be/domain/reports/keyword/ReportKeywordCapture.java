package com.example.be.domain.reports.keyword;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import com.example.be.domain.companyinterest.CompanyInterestStore;
import com.example.be.domain.reports.coverage.ReportCoverageCapture;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.AgentReportOrchestrator;
import com.example.be.domain.reports.service.DailyReportSelector;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.TopicWeeklyReportSelector;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Captures the complete pre-ranking universe once, without AI calls or mutable topic lookups. */
@Component
@RequiredArgsConstructor
public class ReportKeywordCapture {
    private final FindingRepository findings;
    private final AgentReportOrchestrator orchestrator;
    private final CompanyInterestStore companyInterests;

    public ReportKeywordSnapshot captureRun(NewsReport report, LocalDateTime now) {
        return capture(report, orchestrator.companySelectionCandidates(
                findings.findForReportByRunId(report.getRunId())), now);
    }

    public ReportKeywordSnapshot captureDaily(NewsReport report, DailyReportSelector.Prepared prepared,
                                              LocalDateTime now) {
        return prepared == null ? unavailable(report, now, "NO_SNAPSHOT")
                : capture(report, prepared.candidates(), now);
    }

    public ReportKeywordSnapshot captureTopicWeek(NewsReport report,
            List<TopicWeeklyReportSelector.Prepared> prepared, LocalDateTime now) {
        return prepared == null ? unavailable(report, now, "NO_SNAPSHOT")
                : capture(report, prepared.stream().flatMap(day -> day.candidates().stream()).distinct().toList(), now);
    }

    public ReportKeywordSnapshot mergeWeekly(NewsReport report, List<NewsReport> sources, LocalDateTime now) {
        if (sources.isEmpty() || sources.stream().anyMatch(source -> source.getReportKeywordSnapshot() == null
                || !ReportKeywordSnapshot.RULE_VERSION.equals(source.getReportKeywordSnapshot().ruleVersion())
                || source.getReportKeywordSnapshot().unavailableReason() != null))
            return unavailable(report, now, "INCOMPLETE_SOURCE");

        Map<String, ReportKeywordSnapshot.Definition> definitions = new TreeMap<>();
        Set<Pair> rejected = new HashSet<>();
        List<ReportKeywordSnapshot.Candidate> candidates = new ArrayList<>();
        for (NewsReport source : sources) {
            var snapshot = source.getReportKeywordSnapshot();
            snapshot.definitions().forEach(definition -> definitions.putIfAbsent(definition.key(), definition));
            candidates.addAll(snapshot.candidates());
            var coverage = source.getReportCoverageSnapshot();
            if (!source.getCompanySuppressedFindingIds().isEmpty()) {
                if (coverage == null) return unavailable(report, now, "INCOMPLETE_SOURCE");
                Set<Long> bound = coverage.bindings().stream().map(ReportCoverageSnapshot.Binding::findingId)
                        .collect(Collectors.toSet());
                if (!bound.containsAll(source.getCompanySuppressedFindingIds()))
                    return unavailable(report, now, "INCOMPLETE_SOURCE");
                coverage.bindings().stream().filter(binding -> source.getCompanySuppressedFindingIds().contains(binding.findingId()))
                        .forEach(binding -> rejected.add(new Pair(binding.topicId(), binding.issueId())));
            }
        }
        List<ReportKeywordSnapshot.Candidate> kept = candidates.stream()
                .filter(candidate -> !rejected.contains(new Pair(candidate.topicId(), candidate.issueId())))
                .distinct().toList();
        // Weekly company evaluation only sees selected DAILY inputs. Never expose unassessed pre-top-N
        // candidates under an active criterion, or spend another AI call to complete this chart.
        Set<Long> selected = report.getWeeklyInput() == null ? Set.of()
                : Set.copyOf(report.getWeeklyInput().sourceFindingIds());
        Set<Long> unassessedTopics = kept.stream().filter(candidate -> !selected.contains(candidate.findingId()))
                .map(ReportKeywordSnapshot.Candidate::topicId).collect(Collectors.toSet());
        if (!unassessedTopics.isEmpty()) {
            try {
                if (!companyInterests.activeForTopics(unassessedTopics).isEmpty())
                    return unavailable(report, now, "COMPANY_SCOPE_UNVERIFIED");
            } catch (RuntimeException unavailable) {
                return unavailable(report, now, "COMPANY_SCOPE_UNVERIFIED");
            }
        }
        return snapshot(report, now, null, List.copyOf(definitions.values()), kept);
    }

    private ReportKeywordSnapshot capture(NewsReport report, List<Finding> candidates, LocalDateTime now) {
        var coverage = report.getReportCoverageSnapshot();
        if (coverage == null || !ReportCoverageSnapshot.RULE_VERSION.equals(coverage.ruleVersion()))
            return unavailable(report, now, "NO_SNAPSHOT");
        if (report.getCollectionContexts().isEmpty()
                || report.getCollectionContexts().stream().anyMatch(context -> context.topics().isEmpty()))
            return unavailable(report, now, "MISSING_COLLECTION_CONTEXT");

        Map<Scope, Map<String, ReportKeywordSnapshot.Definition>> byScope = new HashMap<>();
        Map<String, ReportKeywordSnapshot.Definition> definitions = new TreeMap<>();
        for (var context : report.getCollectionContexts()) {
            for (var topic : context.topics()) {
                Map<String, ReportKeywordSnapshot.Definition> keywords = definitions(topic);
                Scope scope = new Scope(context.runId(), topic.topicId());
                var previous = byScope.putIfAbsent(scope, keywords);
                if (previous != null && !previous.keySet().equals(keywords.keySet()))
                    return unavailable(report, now, "MISSING_COLLECTION_CONTEXT");
                keywords.forEach(definitions::putIfAbsent);
            }
        }
        Map<Long, List<ReportCoverageSnapshot.Binding>> bindings = coverage.bindings().stream()
                .collect(Collectors.groupingBy(ReportCoverageSnapshot.Binding::findingId));
        List<ReportKeywordSnapshot.Candidate> copied = new ArrayList<>();
        for (Finding finding : candidates) {
            if (!ReportFindings.hasFullText(finding) || !AnalysisSource.isLlmDerived(finding.getAnalysisSource())
                    || !FindingEvidencePolicy.hasSupportedEvidence(finding)) continue;
            var identity = bindings.getOrDefault(finding.getId(), List.of());
            if (identity.size() != 1 || finding.getRun() == null)
                return unavailable(report, now, "INVALID_SNAPSHOT");
            var binding = identity.getFirst();
            Long runId = finding.getRun().getId();
            var keywords = byScope.get(new Scope(runId, binding.topicId()));
            if (keywords == null) return unavailable(report, now, "MISSING_COLLECTION_CONTEXT");
            var article = finding.getArticle();
            var matched = keywords.keySet().stream().filter(keyword ->
                    ReportKeywordMatcher.matches(keyword, article.getTitle(), article.getBody())).sorted().toList();
            copied.add(new ReportKeywordSnapshot.Candidate(finding.getId(), article.getId(), runId,
                    binding.topicId(), binding.issueId(), matched));
        }
        return snapshot(report, now, null, List.copyOf(definitions.values()), copied.stream().distinct().toList());
    }

    private static Map<String, ReportKeywordSnapshot.Definition> definitions(CollectionTopicSnapshot topic) {
        Set<String> excluded = topic.excludedKeywords().stream().map(ReportKeywordMatcher::key).collect(Collectors.toSet());
        Map<String, ReportKeywordSnapshot.Definition> result = new TreeMap<>();
        Stream.concat(topic.requiredKeywords().stream(), topic.optionalKeywords().stream()).forEach(value -> {
            String key = ReportKeywordMatcher.key(value);
            if (!key.isBlank() && !excluded.contains(key)) result.putIfAbsent(key,
                    new ReportKeywordSnapshot.Definition(key, ReportKeywordMatcher.label(value)));
        });
        return result;
    }

    private static ReportKeywordSnapshot unavailable(NewsReport report, LocalDateTime now, String reason) {
        return snapshot(report, now, reason, List.of(), List.of());
    }

    private static ReportKeywordSnapshot snapshot(NewsReport report, LocalDateTime now, String reason,
            List<ReportKeywordSnapshot.Definition> definitions, List<ReportKeywordSnapshot.Candidate> candidates) {
        return new ReportKeywordSnapshot(ReportKeywordSnapshot.RULE_VERSION,
                now.atZone(ApiTimeZone.ZONE).toOffsetDateTime(), ReportCoverageCapture.periodLabel(report),
                reason, definitions, candidates);
    }

    private record Scope(Long runId, Long topicId) { }
    private record Pair(Long topicId, Long issueId) { }
}
