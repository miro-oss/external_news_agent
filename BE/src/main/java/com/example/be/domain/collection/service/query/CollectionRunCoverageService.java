package com.example.be.domain.collection.service.query;

import com.example.be.domain.analysis.config.AnalysisSelectionProperties;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.FindingEvidencePolicy;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CollectionRunCoverageService {

    private static final int RATE_SCALE = 4;

    private final CollectionRunArticleRepository observationRepository;
    private final CollectionRunRepository runRepository;
    private final IssueArticleRepository issueArticleRepository;
    private final FindingRepository findingRepository;
    private final NewsReportRepository reportRepository;
    private final AnalysisSelectionProperties selectionProperties;

    public CollectionRunCoverage calculate(Long runId) {
        Set<ArticleTopic> observations = observations(runId);
        Set<Long> articleIds = observations.stream()
                .map(ArticleTopic::articleId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<IssueArticleRepository.CoverageMembership> memberships = OracleInClause.batches(articleIds).stream()
                .flatMap(ids -> issueArticleRepository.findCoverageMembershipsByArticleIds(ids).stream())
                .toList();

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
                .filter(Objects::nonNull)
                .filter(finding -> AnalysisSource.isLlmDerived(finding.getAnalysisSource()))
                .toList();
        int analyzedCount = analyzedFindings.size();
        NewsReport report = reportRepository.findByRunId(runId).orElse(null);
        boolean reportReady = report != null && report.getReportStatus() != ReportStatus.PENDING;
        int reflectedCount = reflectedCount(report, reportReady, analyzedFindings);
        int excludedCount = excludedCount(report, reportReady, analyzedFindings);

        int issueCount = issueIds.size();
        int issueLimit = selectionProperties.getIssueLimitPerRun();
        int targetCount = runRepository.findById(runId)
                .map(run -> run.getAnalysisTargetIssueCount() == null
                        ? Math.min(issueCount, issueLimit)
                        : run.getAnalysisTargetIssueCount())
                .orElse(Math.min(issueCount, issueLimit));
        return new CollectionRunCoverage(
                observations.size(),
                assignedCount,
                rate(assignedCount, observations.size()),
                issueCount,
                targetCount,
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
        return Collections.unmodifiableSet(result);
    }

    private Map<Long, Long> representatives(Set<Long> issueIds) {
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new LinkedHashMap<>();
        OracleInClause.batches(issueIds).stream()
                .flatMap(ids -> issueArticleRepository.findRepresentativesByIssueIds(ids).stream())
                .forEach(representative -> result.put(
                        representative.getIssueId(), representative.getArticleId()));
        return Collections.unmodifiableMap(result);
    }

    private int reflectedCount(NewsReport report, boolean reportReady, List<Finding> findings) {
        if (!reportReady) {
            return 0;
        }
        if (!report.isCoverageRecorded()) {
            return (int) findings.stream().filter(FindingEvidencePolicy::hasSupportedEvidence).count();
        }
        Set<Long> reflectedIds = Set.copyOf(report.getReflectedFindingIds());
        return (int) findings.stream().filter(finding -> reflectedIds.contains(finding.getId())).count();
    }

    private int excludedCount(NewsReport report, boolean reportReady, List<Finding> findings) {
        if (!reportReady) {
            return 0;
        }
        if (!report.isCoverageRecorded()) {
            return (int) findings.stream()
                    .filter(finding -> !FindingEvidencePolicy.hasSupportedEvidence(finding))
                    .count();
        }
        Set<Long> excludedIds = Set.copyOf(report.getExcludedFindingIds());
        return (int) findings.stream().filter(finding -> excludedIds.contains(finding.getId())).count();
    }

    private Map<Long, Finding> findings(Long runId) {
        Map<Long, Finding> result = new LinkedHashMap<>();
        findingRepository.findForReportByRunId(runId)
                .forEach(finding -> result.putIfAbsent(finding.getArticle().getId(), finding));
        return Collections.unmodifiableMap(result);
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
