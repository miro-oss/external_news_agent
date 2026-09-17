package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.reports.comparison.ReportComparisonSnapshot;
import lombok.extern.slf4j.Slf4j;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportSelector {

    private final FindingRepository findingRepository;
    private final IssueArticleRepository membershipRepository;
    private final TopicRelevancePolicy relevancePolicy;

    @Transactional(readOnly = true)
    public List<Finding> select(LocalDate date, int limit) {
        return selectWithStats(date, limit).findings();
    }

    @Transactional(readOnly = true)
    public Selection selectWithStats(LocalDate date, int limit) {
        List<Finding> candidates = findingRepository.findDailyReportCandidates(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        List<Long> articleIds = candidates.stream().map(f -> f.getArticle().getId()).distinct().toList();
        List<IssueArticle> memberships = OracleInClause.batches(articleIds).stream()
                .flatMap(ids -> membershipRepository.findByArticleIds(ids).stream()).toList();
        return selectWithStats(candidates, memberships, limit, date);
    }

    List<Finding> select(List<Finding> candidates, List<IssueArticle> memberships, int limit) {
        return selectWithStats(candidates, memberships, limit).findings();
    }

    Selection selectWithStats(List<Finding> candidates, List<IssueArticle> memberships, int limit) {
        return selectWithStats(candidates, memberships, limit, null);
    }

    private Selection selectWithStats(List<Finding> candidates, List<IssueArticle> memberships, int limit,
                                      LocalDate date) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("일일 보고서 이슈 상한은 1~50이어야 합니다.");
        }
        Map<Long, List<NewsIssue>> issuesByArticle = memberships.stream()
                .filter(m -> m.getIssue().getArticleCount() > 0)
                .collect(Collectors.groupingBy(m -> m.getArticle().getId(),
                        Collectors.mapping(IssueArticle::getIssue, Collectors.toList())));
        Map<Long, Set<Long>> assessedTopics = relevancePolicy.relevantTopicIdsByFinding(candidates);
        Map<Long, NewsIssue> issueByFinding = new LinkedHashMap<>();
        for (Finding finding : candidates) {
            Set<Long> topics = assessedTopics.get(finding.getId());
            // Keep a rejected latest finding in the ordering until the relevance filter runs below.
            if (topics == null || topics.isEmpty()) topics = Set.of(finding.getArticle().getTopic().getId());
            Set<Long> matchingTopics = topics;
            issuesByArticle.getOrDefault(finding.getArticle().getId(), List.of()).stream()
                    .filter(issue -> matchingTopics.contains(issue.getTopic().getId()))
                    .min(Comparator.comparing(NewsIssue::getId))
                    .ifPresent(issue -> issueByFinding.put(finding.getId(), issue));
        }
        Map<Long, Finding> latestByIssue = new LinkedHashMap<>();
        candidates.stream().sorted(Comparator
                        .comparing((Finding f) -> f.getRun().getStartedAt()).reversed()
                        .thenComparing(Finding::getId, Comparator.reverseOrder()))
                .forEach(f -> {
                    NewsIssue issue = issueByFinding.get(f.getId());
                    if (issue != null) {
                        latestByIssue.putIfAbsent(issue.getId(), f);
                    }
                });
        // Select the latest observation first: filtering earlier could revive an older rejected issue.
        List<Finding> latest = List.copyOf(latestByIssue.values());
        List<Finding> relevant = date == null ? relevancePolicy.filterFindings(latest)
                : relevancePolicy.filterDailyFindings(date, latest);
        int stubExcluded = (int) relevant.stream()
                .filter(f -> f.getAnalysisSource() == AnalysisSource.STUB).count();
        int evidenceExcluded = (int) relevant.stream()
                .filter(f -> AnalysisSource.isLlmDerived(f.getAnalysisSource()))
                .filter(f -> !ReportFindings.hasFullText(f) || !FindingEvidencePolicy.hasSupportedEvidence(f)).count();
        // DAILY는 유효한 이슈에 속한 최신 분석만 집계한다. 레거시 finding을 독립 이슈로 만들지 않는다.
        List<Finding> selected = relevant.stream()
                .filter(ReportFindings::hasFullText)
                .filter(f -> AnalysisSource.isLlmDerived(f.getAnalysisSource()))
                .filter(FindingEvidencePolicy::hasSupportedEvidence)
                .sorted(Comparator.comparing((Finding f) -> {
                    BigDecimal score = issueByFinding.get(f.getId()).getImportanceScore();
                    return score == null ? BigDecimal.ZERO : score;
                }).reversed().thenComparing(f -> issueByFinding.get(f.getId()).getId()))
                .limit(limit).toList();
        ReportComparisonSnapshot snapshot = null;
        try {
            snapshot = ReportComparisonSnapshot.captureByFinding(selected, issueByFinding);
        } catch (RuntimeException exception) {
            log.warn("일일 보고서 비교 입력을 보존할 수 없어 비교를 제공하지 않습니다.", exception);
        }
        return new Selection(selected, stubExcluded, evidenceExcluded, snapshot);
    }

    public record Selection(List<Finding> findings, int stubExcluded, int evidenceExcluded,
                            ReportComparisonSnapshot comparisonSnapshot) {
        public Selection(List<Finding> findings, int stubExcluded, int evidenceExcluded) {
            this(findings, stubExcluded, evidenceExcluded, null);
        }
        public ReportSourceStats applyTo(ReportSourceStats sourceStats) {
            return new ReportSourceStats(sourceStats.collected(), sourceStats.blocked(), sourceStats.failed(),
                    sourceStats.paywalled(), stubExcluded, evidenceExcluded);
        }
    }
}
