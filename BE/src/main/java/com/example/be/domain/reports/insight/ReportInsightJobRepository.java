package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ReportInsightJobRepository {
    private final JdbcTemplate jdbc;

    /** The caller holds the report lock, so repeated completion delivery cannot duplicate jobs. */
    public void enqueue(long reportId, Audience audience, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO news_report_insight_jobs (report_id, audience, status, created_at)
                SELECT ?, ?, 'PENDING', ? FROM dual
                WHERE NOT EXISTS (SELECT 1 FROM news_report_insight_jobs WHERE report_id = ? AND audience = ?)
                """, reportId, audience.name(), Timestamp.valueOf(now), reportId, audience.name());
    }

    public List<Long> missingJobs() {
        return jdbc.query("""
                SELECT report.id FROM news_reports report
                WHERE report.insight_auto_requested_yn = 'Y' AND report.report_status <> 'PENDING'
                  AND report.deleted_at IS NULL
                  AND (SELECT COUNT(*) FROM news_report_insight_jobs job WHERE job.report_id = report.id) < 4
                ORDER BY report.generated_at, report.id FETCH FIRST 10 ROWS ONLY
                """, (rs, row) -> rs.getLong(1));
    }

    public List<Job> pending() {
        return jdbc.query("""
                SELECT report_id, audience FROM news_report_insight_jobs WHERE status = 'PENDING'
                ORDER BY created_at, report_id, audience FETCH FIRST 20 ROWS ONLY
                """, (rs, row) -> new Job(rs.getLong(1), Audience.valueOf(rs.getString(2))));
    }

    /** Claim commits before any LLM request; only the winning instance can issue a provider call. */
    public boolean claim(Job job, LocalDateTime now) {
        return jdbc.update("""
                UPDATE news_report_insight_jobs SET status = 'RUNNING', started_at = ?
                WHERE report_id = ? AND audience = ? AND status = 'PENDING'
                """, Timestamp.valueOf(now), job.reportId(), job.audience().name()) == 1;
    }

    public void finish(Job job, boolean succeeded, LocalDateTime now) {
        jdbc.update("""
                UPDATE news_report_insight_jobs SET status = ?, finished_at = ?
                WHERE report_id = ? AND audience = ? AND status = 'RUNNING'
                """, succeeded ? "SUCCEEDED" : "FAILED", Timestamp.valueOf(now), job.reportId(), job.audience().name());
    }

    /** Unknown outcomes are terminal: restarting must never repeat a potentially paid provider call. */
    public void expireRunning(LocalDateTime before, LocalDateTime now) {
        jdbc.update("""
                UPDATE news_report_insight_jobs SET status = 'FAILED', finished_at = ?
                WHERE status = 'RUNNING' AND started_at < ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(before));
    }

    public boolean isPending(long reportId, Audience audience) {
        return !jdbc.query("""
                SELECT report.id FROM news_reports report
                LEFT JOIN news_report_insight_jobs job ON job.report_id = report.id AND job.audience = ?
                WHERE report.id = ? AND report.insight_auto_requested_yn = 'Y'
                  AND report.report_status <> 'PENDING' AND report.deleted_at IS NULL
                  AND (job.status IS NULL OR job.status IN ('PENDING', 'RUNNING'))
                """, (rs, row) -> rs.getLong(1), audience.name(), reportId).isEmpty();
    }

    public record Job(long reportId, Audience audience) { }
}
