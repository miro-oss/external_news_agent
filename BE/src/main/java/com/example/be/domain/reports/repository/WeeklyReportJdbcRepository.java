package com.example.be.domain.reports.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class WeeklyReportJdbcRepository {
    private final JdbcTemplate jdbcTemplate;

    /** Keyset paging skips weeks already reserved, including hidden completed reports. */
    public List<LocalDate> findMissingWeeks(LocalDate beforeMonday, int limit) {
        return jdbcTemplate.query("""
                SELECT week_start FROM (
                    SELECT DISTINCT TRUNC(d.report_date, 'IW') AS week_start
                    FROM news_reports d
                    WHERE d.report_scope = 'DAILY' AND d.deleted_at IS NULL
                      AND d.report_status <> 'PENDING' AND d.report_date < ?
                      AND NOT EXISTS (
                          SELECT 1 FROM news_reports w
                          WHERE w.report_scope = 'WEEKLY'
                            AND w.report_date = TRUNC(d.report_date, 'IW'))
                    ORDER BY week_start DESC
                ) WHERE ROWNUM <= ?
                """, (rs, row) -> rs.getDate("week_start").toLocalDate(),
                java.sql.Date.valueOf(beforeMonday), limit);
    }

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
