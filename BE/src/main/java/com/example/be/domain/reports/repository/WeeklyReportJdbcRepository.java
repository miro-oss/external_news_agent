package com.example.be.domain.reports.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDate;

@Repository
@RequiredArgsConstructor
public class WeeklyReportJdbcRepository {
    private final JdbcTemplate jdbcTemplate;

    /** Wait for active collection and daily generation, including overdue pending days. */
    public boolean hasUnfinishedInputs(LocalDate monday, LocalDate today) {
        Timestamp from = Timestamp.valueOf(monday.atStartOfDay());
        Timestamp before = Timestamp.valueOf(monday.plusWeeks(1).atStartOfDay());
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT run.id FROM news_collection_runs run
                    WHERE run.started_at >= ? AND run.started_at < ?
                      AND (run.status IN ('PENDING', 'RUNNING') OR (
                          run.started_at >= ? AND NOT EXISTS (
                              SELECT 1 FROM news_reports report
                              WHERE report.report_scope = 'DAILY'
                                AND report.report_date = TRUNC(run.started_at))))
                    UNION ALL
                    SELECT report.id FROM news_reports report
                    WHERE report.report_scope = 'DAILY' AND report.report_status = 'PENDING'
                      AND report.report_date >= ? AND report.report_date < ?
                )
                """, Integer.class, from, before, Timestamp.valueOf(today.minusDays(7).atStartOfDay()), from, before);
        return count != null && count > 0;
    }
}
