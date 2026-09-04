package com.example.be.domain.reports.repository;

import com.example.be.domain.reports.service.ReportSourceStats;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DailyReportJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public void lockCreation() {
        jdbcTemplate.queryForObject("SELECT id FROM app_settings WHERE id = 1 FOR UPDATE", Long.class);
    }

    /** 날짜 경계는 JVM/DB 세션 시간대가 아닌 ApiTimeZone의 LocalDate를 전달한다. */
    public List<LocalDate> findDueDates(LocalDate from, LocalDate before) {
        return jdbcTemplate.query("""
                SELECT TRUNC(run.started_at) AS report_date
                FROM news_collection_runs run
                WHERE run.started_at >= ? AND run.started_at < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM news_reports report
                      WHERE report.report_scope = 'DAILY'
                        AND report.report_date = TRUNC(run.started_at))
                GROUP BY TRUNC(run.started_at)
                HAVING SUM(CASE WHEN run.status IN ('PENDING', 'RUNNING') THEN 1 ELSE 0 END) = 0
                ORDER BY report_date
                """, (rs, row) -> rs.getDate("report_date").toLocalDate(),
                Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(before.atStartOfDay()));
    }

    public List<Long> findSourceRunIds(LocalDate date) {
        return jdbcTemplate.query("""
                SELECT id FROM news_collection_runs
                WHERE started_at >= ? AND started_at < ?
                  AND status NOT IN ('PENDING', 'RUNNING')
                ORDER BY started_at, id
                """, (rs, row) -> rs.getLong("id"), Timestamp.valueOf(date.atStartOfDay()),
                Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    public ReportSourceStats sourceStats(LocalDate date) {
        // RUN과 동일하게 저장된 고유 기사 수가 아닌 실행별 스캔 수를 합산한다.
        int collected = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(
                    (SELECT SUM(item.scanned_count) FROM news_collection_run_items item WHERE item.run_id = run.id),
                    run.scanned_count)), 0)
                FROM news_collection_runs run
                WHERE run.started_at >= ? AND run.started_at < ?
                """, Integer.class, Timestamp.valueOf(date.atStartOfDay()),
                Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN fetch_status IN ('FULLTEXT_BLOCKED', 'ROBOTS_DISALLOWED')
                                         THEN 1 ELSE 0 END), 0) AS blocked,
                       COALESCE(SUM(CASE WHEN fetch_status = 'FETCH_FAILED' THEN 1 ELSE 0 END), 0) AS failed,
                       COALESCE(SUM(CASE WHEN fetch_status = 'FULLTEXT_BLOCKED' THEN 1 ELSE 0 END), 0) AS paywalled
                FROM news_articles article
                WHERE EXISTS (
                    SELECT 1 FROM news_collection_run_articles observation
                    JOIN news_collection_runs run ON run.id = observation.run_id
                    WHERE observation.article_id = article.id AND run.started_at >= ? AND run.started_at < ?)
                """, (rs, row) -> new ReportSourceStats(
                        collected, rs.getInt("blocked"), rs.getInt("failed"),
                        rs.getInt("paywalled"), 0, 0),
                Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    /** 7일 자동 생성 창 밖의 중단 실행도 운영자가 확인할 수 있게 반환한다. */
    public List<BlockedDate> findBlockedDates(LocalDateTime startedBefore) {
        return jdbcTemplate.query("""
                SELECT TRUNC(run.started_at) AS report_date, COUNT(*) AS pending_count
                FROM news_collection_runs run
                WHERE run.started_at < ? AND run.status IN ('PENDING', 'RUNNING')
                  AND NOT EXISTS (SELECT 1 FROM news_reports report
                      WHERE report.report_scope = 'DAILY' AND report.report_date = TRUNC(run.started_at))
                GROUP BY TRUNC(run.started_at)
                ORDER BY report_date
                FETCH FIRST 20 ROWS ONLY
                """, (rs, row) -> new BlockedDate(rs.getDate("report_date").toLocalDate(),
                        rs.getInt("pending_count")), Timestamp.valueOf(startedBefore));
    }

    public record BlockedDate(LocalDate date, int pendingRunCount) {
    }
}
