package com.example.be.domain.collection.service.query;

import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportEvidencePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CollectionRunCoverageService {

    private static final int RATE_SCALE = 4;

    private final CollectionRunArticleRepository observationRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final FindingRepository findingRepository;
    private final NewsReportRepository reportRepository;
    private final AnalysisSelectionProperties selectionProperties;

    public CollectionRunCoverage calculate(Long runId) {
        Set<ArticleTopic> observations = observations(runId);
        Set<Long> articleIds = observations.stream()
                .map(ArticleTopic::articleId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<IssueArticleRepository.CoverageMembership> memberships = articleIds.isEmpty()
                ? List.of()
                : issueArticleRepository.findCoverageMembershipsByArticleIds(articleIds);

        Map<ArticleTopic, Set<Long>> issueIdsByObservation = new LinkedHashMap<>();
        memberships.forEach(membership -> issueIdsByObservation
                .computeIfAbsent(
                        new ArticleTopic(membership.getArticleId(), membership.getTopicId()),
                        ignored -> new LinkedHashSet<>())
                .add(membership.getIssueId()));
        Set<Long> issueIds = new LinkedHashSet<>();
        int assignedCount = 0;
        for (ArticleTopic observation : observations) {
            Set<Long> assignedIssues = issueIdsByObservation.getOrDefault(observation, Set.of());
            if (!assignedIssues.isEmpty()) {
                assignedCount++;
                issueIds.addAll(assignedIssues);
            }
        }

        Map<Long, Long> representativeArticleByIssue = representatives(issueIds);
        Map<Long, Finding> findingByArticle = findings(runId);
        List<Finding> analyzedFindings = representativeArticleByIssue.values().stream()
                .map(findingByArticle::get)
                .filter(java.util.Objects::nonNull)
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .toList();
        int analyzedCount = analyzedFindings.size();
        boolean reportReady = reportRepository.findByRunId(runId)
                .map(report -> report.getReportStatus() != ReportStatus.PENDING)
                .orElse(false);
        int reflectedCount = reportReady
                ? (int) analyzedFindings.stream().filter(ReportEvidencePolicy::hasSupportedEvidence).count()
                : 0;
        int excludedCount = reportReady
                ? (int) analyzedFindings.stream()
                        .filter(finding -> !ReportEvidencePolicy.hasSupportedEvidence(finding))
                        .count()
                : 0;

        int issueCount = issueIds.size();
        int issueLimit = selectionProperties.getIssueLimitPerRun();
        return new CollectionRunCoverage(
                observations.size(),
                assignedCount,
                rate(assignedCount, observations.size()),
                issueCount,
                Math.min(issueCount, issueLimit),
                analyzedCount,
                rate(analyzedCount, issueCount),
                reflectedCount,
                excludedCount,
                rate(reflectedCount + excludedCount, analyzedCount),
                issueLimit);
    }

    private Set<ArticleTopic> observations(Long runId) {
        Set<ArticleTopic> result = new LinkedHashSet<>();
        observationRepository.findCoverageObservationsByRunId(runId).forEach(observation -> result.add(
                new ArticleTopic(observation.getArticleId(), observation.getTopicId())));
        return Set.copyOf(result);
    }

    private Map<Long, Long> representatives(Set<Long> issueIds) {
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new LinkedHashMap<>();
        issueArticleRepository.findRepresentativesByIssueIds(issueIds)
                .forEach(representative -> result.put(
                        representative.getIssueId(), representative.getArticleId()));
        return Map.copyOf(result);
    }

    private Map<Long, Finding> findings(Long runId) {
        Map<Long, Finding> result = new LinkedHashMap<>();
        findingRepository.findForReportByRunId(runId)
                .forEach(finding -> result.putIfAbsent(finding.getArticle().getId(), finding));
        return Map.copyOf(result);
    }

    private BigDecimal rate(int numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private record ArticleTopic(Long articleId, Long topicId) {
    }
}
