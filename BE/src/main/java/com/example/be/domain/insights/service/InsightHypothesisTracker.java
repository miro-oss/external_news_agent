package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.cluster.EntityDocumentFrequencyFilter;
import com.example.be.domain.collection.cluster.IssueClusteringProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.insights.entity.NewsInsight;
import com.example.be.domain.insights.repository.NewsInsightRepository;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InsightHypothesisTracker {

    private final FindingRepository findingRepository;
    private final NewsInsightRepository insightRepository;
    private final NewsWatchRepository watchRepository;
    private final NewsIssueRepository issueRepository;
    private final IssueClusteringProperties clusteringProperties;
    private final AgentProperties agentProperties;
    private final InsightEntityNormalizer entityNormalizer;

    @Transactional
    public int track(Long runId) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        List<Long> activeIssueIds = watchRepository.findActiveIssueIdsByWatchType(
                WatchType.HYPOTHESIS, now);
        if (activeIssueIds.isEmpty()) {
            return 0;
        }

        Map<Long, NewsIssue> issuesById = OracleInClause.batches(activeIssueIds).stream()
                .flatMap(batch -> issueRepository.findAllById(batch).stream())
                .collect(Collectors.toMap(NewsIssue::getId, Function.identity()));
        LocalDateTime trackingSince = now.minusDays(agentProperties.getInsightHistory().getDays());
        List<NewsInsight> insights = latestInsights(activeIssueIds, trackingSince);
        if (insights.isEmpty()) {
            return 0;
        }

        List<Finding> findings = findingRepository.findForReportByRunId(runId).stream()
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .toList();
        Map<Long, Set<String>> findingEntitiesById = findings.stream()
                .collect(Collectors.toMap(
                        Finding::getId,
                        finding -> finding.getEntities() == null
                                ? Set.of()
                                : entityNormalizer.normalize(finding.getEntities().allNames())));
        Map<Long, Set<String>> commonEntitiesByTopic = commonEntitiesByTopic(
                findings, findingEntitiesById);

        int linked = 0;
        for (Finding finding : findings) {
            Long topicId = finding.getArticle().getTopic().getId();
            Set<String> findingEntities = new HashSet<>(
                    findingEntitiesById.getOrDefault(finding.getId(), Set.of()));
            findingEntities.removeAll(commonEntitiesByTopic.getOrDefault(topicId, Set.of()));
            if (findingEntities.size() < clusteringProperties.getEntityOverlapThreshold()) {
                continue;
            }
            Long articleId = finding.getArticle().getId();
            LocalDateTime articleObservedAt = articleObservedAt(finding.getArticle());
            for (NewsInsight insight : insights) {
                NewsIssue issue = issuesById.get(insight.getTargetId());
                if (issue == null
                        || !issue.getTopic().getId().equals(topicId)
                        || articleObservedAt == null
                        || !insight.getCreatedAt().isBefore(articleObservedAt)
                        || insight.getInputArticleIds().contains(articleId)
                        || insight.getRelatedArticleIds().contains(articleId)) {
                    continue;
                }
                Set<String> watchEntities = entityNormalizer.normalize(insight.getWatchEntities());
                watchEntities = new HashSet<>(watchEntities);
                watchEntities.removeAll(commonEntitiesByTopic.getOrDefault(topicId, Set.of()));
                if (intersectionSize(watchEntities, findingEntities)
                        < clusteringProperties.getEntityOverlapThreshold()) {
                    continue;
                }
                insight.addRelatedArticleId(articleId);
                linked++;
            }
        }
        return linked;
    }

    private List<NewsInsight> latestInsights(List<Long> activeIssueIds,
                                             LocalDateTime trackingSince) {
        List<NewsInsight> candidates = new ArrayList<>();
        for (List<Long> batch : OracleInClause.batches(activeIssueIds)) {
            candidates.addAll(insightRepository
                    .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
                            AgentTargetType.ISSUE, batch, trackingSince));
        }
        candidates.sort(Comparator.comparing(NewsInsight::getCreatedAt).reversed()
                .thenComparing(NewsInsight::getId,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        Map<IssueAudienceKey, NewsInsight> latestByAudience = new LinkedHashMap<>();
        for (NewsInsight candidate : candidates) {
            latestByAudience.putIfAbsent(
                    new IssueAudienceKey(candidate.getTargetId(), candidate.getAudience()),
                    candidate);
        }
        return List.copyOf(latestByAudience.values());
    }

    private Map<Long, Set<String>> commonEntitiesByTopic(
            List<Finding> findings,
            Map<Long, Set<String>> findingEntitiesById) {
        Map<Long, List<Set<String>>> documentsByTopic = findings.stream()
                .collect(Collectors.groupingBy(
                        finding -> finding.getArticle().getTopic().getId(),
                        Collectors.mapping(
                                finding -> findingEntitiesById.getOrDefault(finding.getId(), Set.of()),
                                Collectors.toList())));
        return documentsByTopic.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> EntityDocumentFrequencyFilter.commonValues(
                                entry.getValue(),
                                clusteringProperties.getCommonEntityMinArticles(),
                                EntityDocumentFrequencyFilter.MIN_COMMON_ENTITY_DOCUMENT_FREQUENCY,
                                clusteringProperties.getCommonEntityDocumentRatio())));
    }

    private LocalDateTime articleObservedAt(Article article) {
        if (article.getPublishedAt() != null) {
            return article.getPublishedAt()
                    .atZoneSameInstant(ApiTimeZone.ZONE)
                    .toLocalDateTime();
        }
        return article.getCollectedAt();
    }

    private int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        return (int) smaller.stream().filter(larger::contains).count();
    }

    private record IssueAudienceKey(Long issueId, Audience audience) {
    }
}
