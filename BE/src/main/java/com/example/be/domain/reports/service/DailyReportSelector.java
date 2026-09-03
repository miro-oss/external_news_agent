package com.example.be.domain.reports.service;

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

@Component
@RequiredArgsConstructor
public class DailyReportSelector {

    private final FindingRepository findingRepository;
    private final IssueArticleRepository membershipRepository;

    @Transactional(readOnly = true)
    public List<Finding> select(LocalDate date, int limit) {
        List<Finding> candidates = findingRepository.findDailyReportCandidates(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());
        List<Long> articleIds = candidates.stream().map(f -> f.getArticle().getId()).distinct().toList();
        List<IssueArticle> memberships = OracleInClause.batches(articleIds).stream()
                .flatMap(ids -> membershipRepository.findByArticleIds(ids).stream()).toList();
        return select(candidates, memberships, limit);
    }

    List<Finding> select(List<Finding> candidates, List<IssueArticle> memberships, int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("일일 보고서 이슈 상한은 1~50이어야 합니다.");
        }
        Map<Long, NewsIssue> issueByArticle = new LinkedHashMap<>();
        memberships.stream().filter(m -> m.getIssue().getArticleCount() > 0)
                .filter(m -> m.getIssue().getTopic().getId().equals(m.getArticle().getTopic().getId()))
                .forEach(m -> issueByArticle.putIfAbsent(m.getArticle().getId(), m.getIssue()));
        Map<Long, Finding> latestByIssue = new LinkedHashMap<>();
        candidates.stream().sorted(Comparator
                        .comparing((Finding f) -> f.getRun().getStartedAt()).reversed()
                        .thenComparing(Finding::getId, Comparator.reverseOrder()))
                .forEach(f -> {
                    NewsIssue issue = issueByArticle.get(f.getArticle().getId());
                    if (issue != null) {
                        latestByIssue.putIfAbsent(issue.getId(), f);
                    }
                });
        // 최신 분석의 근거가 사라졌다면 과거의 확정 주장을 되살리지 않는다.
        return latestByIssue.values().stream()
                .filter(f -> AnalysisSource.isLlmDerived(f.getAnalysisSource()))
                .filter(FindingEvidencePolicy::hasSupportedEvidence)
                .sorted(Comparator.comparing((Finding f) -> {
                    BigDecimal score = issueByArticle.get(f.getArticle().getId()).getImportanceScore();
                    return score == null ? BigDecimal.ZERO : score;
                }).reversed().thenComparing(f -> issueByArticle.get(f.getArticle().getId()).getId()))
                .limit(limit).toList();
    }
}
