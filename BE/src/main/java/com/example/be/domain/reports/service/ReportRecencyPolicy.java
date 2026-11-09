package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.collection.config.CollectionPipelineProperties;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.scoring.ArticleRecencyPolicy;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Used only when selecting new report inputs, never while reading frozen historical reports. */
@Component
@RequiredArgsConstructor
public class ReportRecencyPolicy {
    private final CollectionRunItemRepository runItems;
    private final CollectionPipelineProperties properties;
    private final TopicRelevancePolicy relevance;

    public List<Finding> filter(List<Finding> findings) {
        if (findings.stream().noneMatch(finding -> finding.getRun() != null
                && finding.getRun().getStartedAt() != null)) return findings;
        return filter(findings, relevance.relevantTopicIdsByFinding(findings));
    }

    public List<Finding> filterForTopic(List<Finding> findings, Long topicId) {
        return filter(findings, findings.stream().collect(Collectors.toMap(
                Finding::getId, finding -> Set.of(topicId), (left, right) -> left)));
    }

    List<Finding> filter(List<Finding> findings, Map<Long, Set<Long>> topicsByFinding) {
        Map<Long, Map<Long, Topic>> snapshots = new LinkedHashMap<>();
        findings.stream().map(Finding::getRun).filter(run -> run != null && run.getId() != null)
                .forEach(run -> snapshots.computeIfAbsent(run.getId(), runId -> {
                    Map<Long, Topic> topics = new LinkedHashMap<>();
                    runItems.findExecutionItemsByRunId(runId).stream()
                            .filter(item -> item.getTopicSnapshot() != null)
                            .forEach(item -> topics.putIfAbsent(item.getTopicSnapshot().topicId(), item.collectionTopic()));
                    return topics;
                }));
        return findings.stream().filter(finding -> {
            var run = finding.getRun();
            if (run == null || run.getStartedAt() == null) return true;
            OffsetDateTime referenceTime = run.getStartedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime();
            Map<Long, Topic> topics = snapshots.getOrDefault(run.getId(), Map.of());
            Set<Long> topicIds = topicsByFinding.get(finding.getId());
            if (topicIds == null || topicIds.isEmpty()) {
                var originalTopic = finding.getArticle().getTopic();
                if (originalTopic == null || originalTopic.getId() == null) return true;
                topicIds = Set.of(originalTopic.getId());
            }
            return topicIds.stream().anyMatch(topicId -> ArticleRecencyPolicy.isEligible(
                    topics.get(topicId), finding.getArticle().getPublishedAt(), referenceTime,
                    properties.getTechnologyLookbackDays()));
        }).toList();
    }
}
