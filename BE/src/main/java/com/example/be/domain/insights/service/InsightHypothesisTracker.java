package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.agent.investigation.InvestigationQueryNormalizer;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.cluster.IssueClusteringProperties;
import com.example.be.domain.insights.entity.NewsInsight;
import com.example.be.domain.insights.repository.NewsInsightRepository;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
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

    @Transactional
    public int track(Long runId) {
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        List<Long> activeIssueIds = watchRepository.findActiveIssueIdsByWatchType(
                WatchType.HYPOTHESIS, now);
        if (activeIssueIds.isEmpty()) {
            return 0;
        }

        Map<Long, NewsIssue> issuesById = issueRepository.findAllById(activeIssueIds).stream()
                .collect(Collectors.toMap(NewsIssue::getId, Function.identity()));
        LocalDateTime trackingSince = now.minusDays(agentProperties.getInsightHistory().getDays());
        List<NewsInsight> insights = insightRepository
                .findByTargetTypeAndTargetIdInAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
                        AgentTargetType.ISSUE, activeIssueIds, trackingSince);
        if (insights.isEmpty()) {
            return 0;
        }

        int linked = 0;
        for (Finding finding : findingRepository.findForReportByRunId(runId)) {
            if (!AnalysisSource.isLlmDerived(finding.getAnalysisSource())) {
                continue;
            }
            Set<String> findingEntities = finding.getEntities() == null
                    ? Set.of()
                    : normalized(finding.getEntities().allNames());
            if (findingEntities.size() < clusteringProperties.getEntityOverlapThreshold()) {
                continue;
            }
            Long topicId = finding.getArticle().getTopic().getId();
            Long articleId = finding.getArticle().getId();
            for (NewsInsight insight : insights) {
                NewsIssue issue = issuesById.get(insight.getTargetId());
                if (issue == null
                        || !issue.getTopic().getId().equals(topicId)
                        || !insight.getCreatedAt().isBefore(finding.getAnalyzedAt())
                        || insight.getInputArticleIds().contains(articleId)
                        || insight.getRelatedArticleIds().contains(articleId)) {
                    continue;
                }
                Set<String> watchEntities = normalized(insight.getWatchEntities());
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

    private Set<String> normalized(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .map(InvestigationQueryNormalizer::normalizeEntity)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = left.size() <= right.size() ? right : left;
        return (int) smaller.stream().filter(larger::contains).count();
    }
}
