package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.global.database.OracleInClause;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads only the criteria actually recorded with a completed selection, never current settings. */
@Repository
@RequiredArgsConstructor
public class ReportComparisonCriteria {
    private final JdbcTemplate jdbc;

    public Set<AppliedCriterion> applied(NewsReport report) {
        Set<Long> ids = new HashSet<>(report.getSourceReportIds());
        ids.add(report.getId());
        return OracleInClause.batches(ids).stream().flatMap(batch -> jdbc.query("""
                SELECT DISTINCT criterion_id, criterion_version, topic_id_snapshot
                FROM news_company_interest_apps WHERE report_id IN (
                """ + String.join(",", java.util.Collections.nCopies(batch.size(), "?")) + ")",
                (rs, row) -> new AppliedCriterion(rs.getLong(1), rs.getLong(2), rs.getLong(3)), batch.toArray()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Equal unions can hide a policy change halfway through one of the compared weeks. */
    public boolean hasMixedSourceCriteria(NewsReport report) {
        if (report.getSourceReportIds().size() < 2) return false;
        java.util.Map<Long, Set<AppliedCriterion>> byReport = new java.util.HashMap<>();
        report.getSourceReportIds().forEach(id -> byReport.put(id, new HashSet<>()));
        OracleInClause.batches(report.getSourceReportIds()).forEach(batch -> jdbc.query("""
                SELECT report_id, criterion_id, criterion_version, topic_id_snapshot
                FROM news_company_interest_apps WHERE report_id IN (
                """ + String.join(",", java.util.Collections.nCopies(batch.size(), "?")) + ")",
                (rs, row) -> {
                    byReport.get(rs.getLong(1)).add(new AppliedCriterion(rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                    return true;
                }, batch.toArray()));
        return byReport.values().stream().distinct().limit(2).count() > 1;
    }

    public record AppliedCriterion(long id, long version, long topicId) { }
}
