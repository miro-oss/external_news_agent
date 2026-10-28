package com.example.be.domain.reports.keyword;

import com.example.be.domain.analysis.relevance.TopicRelevanceGate;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.reports.coverage.ReportCoverageCapture;
import com.example.be.domain.reports.coverage.ReportCoverageSnapshot;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.service.DailyReportFeedbackExclusions;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Projects only frozen candidates. Final top-N selection must not become the denominator. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportKeywordService {
    private static final Set<String> REASONS = Set.of("NO_SNAPSHOT", "INCOMPLETE_SOURCE", "MISSING_COLLECTION_CONTEXT",
            "INVALID_SNAPSHOT", "COMPANY_SCOPE_UNVERIFIED", "PERSONALIZATION_UNVERIFIED");
    private final ArticleRepository articles;
    private final TopicRelevancePolicy relevance;
    private final DailyReportFeedbackExclusions dailyExclusions;

    public ReportKeywordDTO.View project(NewsReport report, ReportEventFeedbackProjection.View view) {
        return project(report, view, false);
    }

    public ReportKeywordDTO.View projectForDelivery(NewsReport report, ReportEventFeedbackProjection.View view) {
        return project(report, view, true);
    }

    public ReportKeywordDTO.View unavailable(NewsReport report, String reason) {
        var snapshot = report.getReportKeywordSnapshot();
        return new ReportKeywordDTO.View(ReportKeywordDTO.Status.UNAVAILABLE,
                reason != null && REASONS.contains(reason) ? reason : "INVALID_SNAPSHOT", snapshot == null ? null : snapshot.ruleVersion(),
                snapshot == null ? null : snapshot.capturedAt(),
                snapshot == null ? ReportCoverageCapture.periodLabel(report) : snapshot.periodLabel(), null, List.of(), 0);
    }

    private ReportKeywordDTO.View project(NewsReport report, ReportEventFeedbackProjection.View view, boolean delivery) {
        var snapshot = report.getReportKeywordSnapshot();
        if (snapshot == null) return unavailable(report, "NO_SNAPSHOT");
        if (!ReportKeywordSnapshot.RULE_VERSION.equals(snapshot.ruleVersion())) return unavailable(report, "INVALID_SNAPSHOT");
        if (snapshot.unavailableReason() != null) return unavailable(report, snapshot.unavailableReason());
        if (!valid(snapshot)) return unavailable(report, "INVALID_SNAPSHOT");

        Map<Long, Set<Pair>> identities = new HashMap<>();
        snapshot.candidates().forEach(candidate -> identities.computeIfAbsent(candidate.findingId(), ignored -> new HashSet<>())
                .add(new Pair(candidate.issueId(), candidate.topicId())));
        // Some excluded event sources are different representatives of an otherwise eligible candidate.
        var coverage = report.getReportCoverageSnapshot();
        if (coverage != null && ReportCoverageSnapshot.RULE_VERSION.equals(coverage.ruleVersion())) {
            coverage.bindings().stream().filter(binding -> positive(binding.findingId()) && positive(binding.issueId()) && positive(binding.topicId()))
                    .forEach(binding -> identities.computeIfAbsent(binding.findingId(), ignored -> new HashSet<>())
                            .add(new Pair(binding.issueId(), binding.topicId())));
        }
        Set<Long> rejected = new HashSet<>(view.excludedEvidenceFindingIds());
        rejected.addAll(report.getCompanySuppressedFindingIds());
        rejected.addAll(dailyExclusions.forKeywordCandidates(report, snapshot, delivery));
        // An unbound rejected representative could belong to any surviving frozen issue. Do not
        // publish a seemingly complete count until its original identity can be established.
        if (!snapshot.candidates().isEmpty() && !identities.keySet().containsAll(rejected))
            return unavailable(report, "INVALID_SNAPSHOT");
        Set<Pair> rejectedPairs = rejected.stream().flatMap(id -> identities.getOrDefault(id, Set.of()).stream())
                .collect(Collectors.toSet());
        var candidates = snapshot.candidates().stream()
                .filter(candidate -> !rejected.contains(candidate.findingId()))
                .filter(candidate -> !rejectedPairs.contains(new Pair(candidate.issueId(), candidate.topicId()))).toList();
        Set<Long> fullText = OracleInClause.batches(candidates.stream().map(ReportKeywordSnapshot.Candidate::articleId).distinct().toList())
                .stream().flatMap(ids -> articles.findAllById(ids).stream()).filter(Article::hasFullText)
                .map(Article::getId).collect(Collectors.toSet());
        Map<Long, Set<TopicRelevanceGate.Key>> excludedByRun = new HashMap<>();
        candidates.stream().map(ReportKeywordSnapshot.Candidate::runId).distinct()
                .forEach(id -> excludedByRun.put(id, relevance.excludedKeys(id)));
        var permitted = candidates.stream().filter(candidate -> fullText.contains(candidate.articleId()))
                .filter(candidate -> !excludedByRun.getOrDefault(candidate.runId(), Set.of())
                        .contains(new TopicRelevanceGate.Key(candidate.articleId(), candidate.topicId()))).toList();
        Set<Long> issueIds = permitted.stream().map(ReportKeywordSnapshot.Candidate::issueId).collect(Collectors.toSet());
        Map<String, Set<Long>> byKeyword = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        snapshot.definitions().forEach(definition -> {
            byKeyword.putIfAbsent(definition.key(), new HashSet<>());
            labels.putIfAbsent(definition.key(), definition.label());
        });
        permitted.forEach(candidate -> candidate.matchedKeywordKeys().forEach(key -> byKeyword.get(key).add(candidate.issueId())));
        var ordered = byKeyword.keySet().stream().sorted(Comparator
                .<String>comparingInt(key -> byKeyword.get(key).size()).reversed().thenComparing(Comparator.naturalOrder())).toList();
        List<ReportKeywordDTO.Item> items = ordered.stream().limit(6).map(key -> {
            int count = byKeyword.get(key).size();
            Double share = issueIds.isEmpty() ? null : Math.round(count * 1000.0 / issueIds.size()) / 10.0;
            var related = ordered.stream().filter(other -> !other.equals(key)).map(other -> new Cooccurrence(other,
                            (int) byKeyword.get(other).stream().filter(byKeyword.get(key)::contains).count()))
                    .filter(value -> value.count() > 0).sorted(Comparator.comparingInt(Cooccurrence::count).reversed()
                            .thenComparing(Cooccurrence::key)).limit(3)
                    .map(value -> new ReportKeywordDTO.RelatedKeyword(labels.get(value.key()), value.count())).toList();
            return new ReportKeywordDTO.Item(labels.get(key), count, share, related);
        }).toList();
        return new ReportKeywordDTO.View(ReportKeywordDTO.Status.AVAILABLE, null, snapshot.ruleVersion(), snapshot.capturedAt(),
                snapshot.periodLabel(), issueIds.size(), items, Math.max(0, ordered.size() - items.size()));
    }

    private static boolean valid(ReportKeywordSnapshot snapshot) {
        if (snapshot.capturedAt() == null || snapshot.periodLabel() == null || snapshot.periodLabel().isBlank()
                || snapshot.definitions() == null || snapshot.candidates() == null) return false;
        Map<String, String> definitions = new HashMap<>();
        for (var definition : snapshot.definitions()) {
            if (definition == null || definition.key() == null || definition.key().isBlank()
                    || definition.label() == null || definition.label().isBlank()) return false;
            String previous = definitions.putIfAbsent(definition.key(), definition.label());
            if (previous != null && !previous.equals(definition.label())) return false;
        }
        Map<Long, ReportKeywordSnapshot.Candidate> byFinding = new HashMap<>();
        Map<Long, Long> topicByIssue = new HashMap<>();
        for (var candidate : snapshot.candidates()) {
            if (candidate == null || !positive(candidate.findingId()) || !positive(candidate.articleId()) || !positive(candidate.runId())
                    || !positive(candidate.topicId()) || !positive(candidate.issueId()) || candidate.matchedKeywordKeys() == null
                    || !definitions.keySet().containsAll(candidate.matchedKeywordKeys())) return false;
            var previous = byFinding.putIfAbsent(candidate.findingId(), candidate);
            if (previous != null && (!previous.articleId().equals(candidate.articleId()) || !previous.runId().equals(candidate.runId())
                    || !previous.issueId().equals(candidate.issueId()) || !previous.topicId().equals(candidate.topicId()))) return false;
            Long topic = topicByIssue.putIfAbsent(candidate.issueId(), candidate.topicId());
            if (topic != null && !Objects.equals(topic, candidate.topicId())) return false;
        }
        return true;
    }

    private static boolean positive(Long id) { return id != null && id > 0; }
    private record Pair(Long issueId, Long topicId) { }
    private record Cooccurrence(String key, int count) { }
}
