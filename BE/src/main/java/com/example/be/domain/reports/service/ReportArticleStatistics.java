package com.example.be.domain.reports.service;

import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository.ReportArticleObservation;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.global.database.OracleInClause;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class ReportArticleStatistics {
    private ReportArticleStatistics() {}

    /** Collection totals retain the same scope in the API, email and PDF. */
    public static ReportResDTO.ArticleStats forReport(NewsReport report, CollectionRunArticleRepository repository) {
        List<Long> runIds = report.getReportScope() != ReportScope.RUN ? report.getSourceRunIds()
                : report.getRunId() == null ? List.of() : List.of(report.getRunId());
        Set<Long> allowedArticles = report.getTopicId() == null || report.getWeeklyInput() == null ? null
                : report.getWeeklyInput().sources().stream().filter(source -> source.evidenceSnapshot() != null)
                        .flatMap(source -> source.evidenceSnapshot().issues().stream())
                        .flatMap(issue -> issue.side().claims().stream()).flatMap(claim -> claim.evidence().stream())
                        .map(evidence -> evidence.articleId()).collect(Collectors.toSet());
        return count(OracleInClause.batches(runIds).stream()
                .flatMap(ids -> repository.findReportArticleObservations(ids).stream())
                .filter(observation -> allowedArticles == null || allowedArticles.contains(observation.getArticleId())).toList());
    }

    static ReportResDTO.ArticleStats count(List<ReportArticleObservation> observations) {
        Set<Long> articles = new HashSet<>();
        Set<Long> newArticles = new HashSet<>();
        for (ReportArticleObservation observation : observations) {
            articles.add(observation.getArticleId());
            if (observation.getChangeType() == ChangeType.NEW) newArticles.add(observation.getArticleId());
        }
        return ReportResDTO.ArticleStats.builder().totalCount(articles.size()).newCount(newArticles.size())
                .existingCount(articles.size() - newArticles.size()).build();
    }
}
