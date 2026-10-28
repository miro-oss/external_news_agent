package com.example.be.domain.reports.coverage;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only projection of frozen evidence. Current membership and current representative are never read. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportCoverageService {
    private final ArticleRepository articles;
    private final TopicRelevancePolicy relevance;

    public ReportCoverageDTO.View project(NewsReport report, ReportEventFeedbackProjection.View view) {
        var snapshot = report.getReportCoverageSnapshot();
        var allowed = view.findings().stream().map(Finding::getId).collect(Collectors.toSet());
        Set<Long> rejected = new HashSet<>(view.excludedEvidenceFindingIds());
        rejected.addAll(report.getCompanySuppressedFindingIds());
        List<Long> originalIds = report.getReportScope() == ReportScope.RUN && snapshot != null
                ? snapshot.bindings().stream().map(ReportCoverageSnapshot.Binding::findingId).toList()
                : report.getReflectedFindingIds();
        originalIds.stream().filter(id -> !allowed.contains(id)).forEach(rejected::add);
        Map<Long, Set<Pair>> bindings = new HashMap<>();
        Set<Pair> rejectedPairs = new HashSet<>();
        if (snapshot != null) {
            snapshot.bindings().forEach(binding -> {
                var pair = new Pair(binding.issueId(), binding.topicId());
                bindings.computeIfAbsent(binding.findingId(), ignored -> new HashSet<>()).add(pair);
                if (rejected.contains(binding.findingId())) rejectedPairs.add(pair);
            });
        }
        Set<Pair> requestedPairs = allowed.stream().filter(id -> !rejected.contains(id))
                .flatMap(id -> bindings.getOrDefault(id, Set.of()).stream()).collect(Collectors.toSet());
        requestedPairs.removeAll(rejectedPairs);
        List<ReportCoverageSnapshot.Observation> candidates = snapshot == null ? List.of()
                : snapshot.observations().stream().filter(o -> requestedPairs.contains(new Pair(o.issueId(), o.topicId()))).toList();
        Set<Long> fullText = OracleInClause.batches(candidates.stream().map(ReportCoverageSnapshot.Observation::articleId).distinct().toList())
                .stream().flatMap(ids -> articles.findAllById(ids).stream()).filter(Article::hasFullText)
                .map(Article::getId).collect(Collectors.toSet());
        Map<Long, Set<TopicRelevanceGate.Key>> excludedByRun = new HashMap<>();
        candidates.stream().map(ReportCoverageSnapshot.Observation::runId).distinct()
                .forEach(id -> excludedByRun.put(id, relevance.excludedKeys(id)));
        List<ReportCoverageSnapshot.Observation> permitted = candidates.stream()
                .filter(o -> fullText.contains(o.articleId()))
                .filter(o -> !excludedByRun.getOrDefault(o.runId(), Set.of())
                        .contains(new TopicRelevanceGate.Key(o.articleId(), o.topicId()))).toList();
        var content = view.readingContent().structuredContent();
        List<ReportCoverageDTO.EventCoverage> events = content == null ? List.of()
                : content.importantEvents().stream().map(event -> summarize(event.sourceFindingIds(), snapshot,
                        allowed, rejected, bindings, rejectedPairs, permitted)).toList();
        List<ReportCoverageDTO.FindingCoverage> findings = view.findings().stream().map(finding -> {
            var summary = summarize(List.of(finding.getId()), snapshot, allowed, rejected, bindings, rejectedPairs, permitted);
            return new ReportCoverageDTO.FindingCoverage(finding.getId(), summary.status(), summary.articleCount(),
                    summary.publisherCount(), summary.unknownPublisherArticleCount(), summary.representativeArticles());
        }).toList();
        return new ReportCoverageDTO.View(snapshot == null ? null : snapshot.ruleVersion(),
                snapshot == null ? null : snapshot.capturedAt(), snapshot == null ? periodLabel(report) : snapshot.periodLabel(), events, findings);
    }

    private ReportCoverageDTO.EventCoverage summarize(List<Long> sourceIds, ReportCoverageSnapshot snapshot,
            Set<Long> allowed, Set<Long> rejected, Map<Long, Set<Pair>> bindings, Set<Pair> rejectedPairs,
            List<ReportCoverageSnapshot.Observation> observations) {
        var ids = ReportCoverageDTO.normalizedIds(sourceIds);
        if (snapshot == null || !ReportCoverageSnapshot.RULE_VERSION.equals(snapshot.ruleVersion())
                || ids.isEmpty() || !allowed.containsAll(ids)
                || ids.stream().anyMatch(id -> !bindings.containsKey(id))) return ReportCoverageDTO.unavailable(ids);
        Set<Pair> pairs = ids.stream().filter(id -> !rejected.contains(id))
                .flatMap(id -> bindings.get(id).stream()).collect(Collectors.toSet());
        pairs.removeAll(rejectedPairs);
        var byArticle = new LinkedHashMap<Long, ReportCoverageSnapshot.Observation>();
        observations.stream().filter(o -> pairs.contains(new Pair(o.issueId(), o.topicId())))
                .sorted(Comparator.comparing(ReportCoverageSnapshot.Observation::observationId))
                .forEach(o -> byArticle.putIfAbsent(o.articleId(), o));
        var members = List.copyOf(byArticle.values());
        int publishers = (int) members.stream().map(ReportCoverageSnapshot.Observation::publisherKey)
                .filter(Objects::nonNull).distinct().count();
        int unknown = (int) members.stream().filter(o -> o.publisherKey() == null).count();
        return new ReportCoverageDTO.EventCoverage(ids, ReportCoverageDTO.Status.AVAILABLE, members.size(),
                publishers, unknown, representatives(members));
    }

    private static List<ReportCoverageDTO.Article> representatives(List<ReportCoverageSnapshot.Observation> members) {
        var sorted = members.stream().filter(o -> o.title() != null && !o.title().isBlank())
                .sorted(Comparator.comparing(ReportCoverageSnapshot.Observation::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(ReportCoverageSnapshot.Observation::articleId)).toList();
        List<ReportCoverageSnapshot.Observation> selected = new ArrayList<>();
        for (var candidate : sorted) {
            if (selected.isEmpty() || selected.stream().allMatch(existing ->
                    candidate.publisherKey() != null && !candidate.publisherKey().equals(existing.publisherKey())
                    && (candidate.contentGroupId() == null || !candidate.contentGroupId().equals(existing.contentGroupId())))) {
                selected.add(candidate);
                if (selected.size() == 2) break;
            }
        }
        // One representative is sufficient when remaining coverage is the same publisher or syndicated copy.
        return selected.stream().map(o -> new ReportCoverageDTO.Article(o.articleId(), o.title(),
                o.publisherKey() == null ? null : o.publisher(), o.publishedAt(), safeUrl(o.canonicalUrl()))).toList();
    }

    static String safeUrl(String value) {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) return null;
        try {
            URI uri = URI.create(value.trim());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null ? uri.toASCIIString() : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    public static String periodLabel(NewsReport report) {
        if (report.getReportScope() == ReportScope.RUN) return "해당 실행 수집 기준";
        if (report.getReportEndDate() != null) return report.getReportDate() + " ~ " + report.getReportEndDate() + " 수집 기준";
        return report.getReportDate() == null ? "보고서 수집 범위 기준" : report.getReportDate() + " 수집 기준";
    }

    private record Pair(Long issueId, Long topicId) { }
}
