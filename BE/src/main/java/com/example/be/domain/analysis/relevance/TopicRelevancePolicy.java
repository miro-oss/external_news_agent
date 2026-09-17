package com.example.be.domain.analysis.relevance;

import com.example.be.domain.analysis.entity.Finding;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Absence means legacy, not a newly approved article. The gate always stores new decisions. */
@Component
@RequiredArgsConstructor
public class TopicRelevancePolicy {
    private final TopicRelevanceStore store;

    public Set<TopicRelevanceGate.Key> excludedKeys(Long runId) {
        return store.findByRun(runId).stream().filter(a -> a.status() != TopicRelevanceStatus.RELEVANT)
                .map(a -> new TopicRelevanceGate.Key(a.articleId(), a.topicId())).collect(Collectors.toSet());
    }

    public Optional<Set<Long>> relevantTopicIds(Long runId, Long articleId) {
        var rows = store.findByRun(runId).stream().filter(a -> a.articleId().equals(articleId)).toList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.stream()
                .filter(a -> a.status() == TopicRelevanceStatus.RELEVANT)
                .map(TopicRelevanceStore.Assessment::topicId).collect(Collectors.toSet()));
    }

    /** Missing key is legacy; an empty set means this finding has only held/rejected contexts. */
    public Map<Long, Set<Long>> relevantTopicIdsByFinding(List<Finding> findings) {
        var byRun = assessments(findings);
        Map<Long, Set<Long>> result = new java.util.HashMap<>();
        for (Finding finding : findings) {
            var rows = articleAssessments(finding, byRun);
            if (!rows.isEmpty()) {
                result.put(finding.getId(), rows.stream().filter(a -> a.status() == TopicRelevanceStatus.RELEVANT)
                        .map(TopicRelevanceStore.Assessment::topicId).collect(Collectors.toSet()));
            }
        }
        return Map.copyOf(result);
    }

    public List<Finding> filterFindings(List<Finding> findings) {
        if (findings.isEmpty()) return List.of();
        Map<Long, List<TopicRelevanceStore.Assessment>> byRun = assessments(findings);
        return findings.stream().filter(f -> permitted(f, byRun)).toList();
    }

    public List<Finding> filterDailyFindings(LocalDate date, List<Finding> findings) {
        if (findings.isEmpty()) return List.of();
        var byRun = assessments(findings);
        var latest = store.latestOnDate(date, findings.stream().map(f -> f.getArticle().getId()).distinct().toList());
        return findings.stream().filter(f -> permitted(f, byRun)).filter(f -> {
            var own = articleAssessments(f, byRun);
            Set<Long> topics = own.stream().filter(a -> a.status() == TopicRelevanceStatus.RELEVANT)
                    .map(TopicRelevanceStore.Assessment::topicId).collect(Collectors.toSet());
            if (topics.isEmpty() && f.getArticle().getTopic() != null) topics.add(f.getArticle().getTopic().getId());
            // A later negative for a different topic cannot invalidate this finding's accepted context.
            return topics.isEmpty() || topics.stream().anyMatch(topicId -> latest.stream().noneMatch(a ->
                    a.articleId().equals(f.getArticle().getId()) && a.topicId().equals(topicId)
                            && (a.startedAt().isAfter(f.getRun().getStartedAt())
                                || (a.startedAt().equals(f.getRun().getStartedAt()) && a.runId() >= f.getRun().getId()))
                            && a.status() != TopicRelevanceStatus.RELEVANT));
        }).toList();
    }

    private Map<Long, List<TopicRelevanceStore.Assessment>> assessments(List<Finding> findings) {
        return findings.stream().map(f -> f.getRun().getId()).distinct()
                .collect(Collectors.toMap(Function.identity(), store::findByRun));
    }

    private boolean permitted(Finding finding, Map<Long, List<TopicRelevanceStore.Assessment>> byRun) {
        var rows = articleAssessments(finding, byRun);
        return rows.isEmpty() || rows.stream().anyMatch(a -> a.status() == TopicRelevanceStatus.RELEVANT);
    }

    private List<TopicRelevanceStore.Assessment> articleAssessments(Finding finding,
            Map<Long, List<TopicRelevanceStore.Assessment>> byRun) {
        return byRun.getOrDefault(finding.getRun().getId(), List.of()).stream()
                .filter(a -> a.articleId().equals(finding.getArticle().getId())).toList();
    }
}
