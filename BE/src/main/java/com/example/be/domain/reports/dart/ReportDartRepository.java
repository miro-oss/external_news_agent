package com.example.be.domain.reports.dart;

import com.example.be.domain.dart.DartModels.Company;
import com.example.be.domain.dart.DartModels.FinancialData;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.sql.Timestamp;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportDartRepository {
    static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    record Attachment(long reportId, Company company, String status, String message, FinancialData data) { }
    record Job(long reportId, Company company, int attempts) { }
    record Cached(FinancialData data, LocalDateTime cachedAt) { }

    List<Attachment> find(long reportId) {
        return jdbc.query("""
                SELECT report_id, corp_code, corp_name, stock_code, status, message, payload_json
                FROM news_report_dart_attachments WHERE report_id = ? ORDER BY created_at, corp_code
                """, (rs, row) -> new Attachment(rs.getLong("report_id"),
                new Company(rs.getString("corp_code"), rs.getString("corp_name"), rs.getString("stock_code")),
                rs.getString("status"), rs.getString("message"), read(rs.getString("payload_json"))), reportId);
    }

    void enqueue(long reportId, Company company, boolean enabled, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO news_report_dart_attachments
                    (report_id, corp_code, corp_name, stock_code, status, message, next_attempt_at, created_at, updated_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? FROM dual
                WHERE NOT EXISTS (SELECT 1 FROM news_report_dart_attachments WHERE report_id = ? AND corp_code = ?)
                """, reportId, company.corpCode(), company.corpName(), company.stockCode(),
                enabled ? "PENDING" : "DISABLED", enabled ? ReportDartMessages.PENDING : ReportDartMessages.DISABLED,
                ts(now), ts(now), ts(now), reportId, company.corpCode());
    }

    List<Long> missing() {
        return jdbc.query("""
                SELECT report.id FROM news_reports report
                WHERE report.report_scope = 'RUN' AND report.report_status <> 'PENDING' AND report.deleted_at IS NULL
                  AND JSON_EXISTS(report.collection_contexts, '$[*].topics[*].dartCompany.corpCode')
                  AND NOT EXISTS (SELECT 1 FROM news_report_dart_attachments attachment WHERE attachment.report_id = report.id)
                ORDER BY report.generated_at, report.id FETCH FIRST 20 ROWS ONLY
                """, (rs, row) -> rs.getLong(1));
    }

    List<Job> pending(LocalDateTime now) {
        return jdbc.query("""
                SELECT attachment.report_id, attachment.corp_code, attachment.corp_name, attachment.stock_code, attachment.attempts
                FROM news_report_dart_attachments attachment JOIN news_reports report ON report.id = attachment.report_id
                WHERE attachment.status = 'PENDING' AND attachment.next_attempt_at <= ?
                  AND report.deleted_at IS NULL AND report.report_status <> 'PENDING'
                ORDER BY attachment.next_attempt_at, attachment.report_id FETCH FIRST 10 ROWS ONLY
                """, (rs, row) -> new Job(rs.getLong(1), new Company(rs.getString(2), rs.getString(3), rs.getString(4)),
                rs.getInt(5)), ts(now));
    }

    boolean claim(Job job, String token, LocalDateTime now, LocalDateTime expires) {
        return jdbc.update("""
                UPDATE news_report_dart_attachments SET status = 'RUNNING', lease_token = ?, lease_expires_at = ?, updated_at = ?
                WHERE report_id = ? AND corp_code = ? AND status = 'PENDING' AND next_attempt_at <= ? AND attempts = ?
                """, token, ts(expires), ts(now), job.reportId(), job.company().corpCode(), ts(now), job.attempts()) == 1;
    }

    void recover(LocalDateTime now, boolean enabled) {
        jdbc.update("""
                UPDATE news_report_dart_attachments
                SET status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END,
                    message = CASE WHEN attempts + 1 >= ? THEN ? ELSE ? END,
                    attempts = attempts + 1, lease_token = NULL, lease_expires_at = NULL, next_attempt_at = ?, updated_at = ?
                WHERE status = 'RUNNING' AND lease_expires_at <= ?
                """, MAX_ATTEMPTS, MAX_ATTEMPTS, ReportDartMessages.FAILED, ReportDartMessages.PENDING,
                ts(now), ts(now), ts(now));
        if (enabled) {
            jdbc.update("""
                    UPDATE news_report_dart_attachments SET status = 'PENDING', message = ?, next_attempt_at = ?, updated_at = ?
                    WHERE status = 'DISABLED'
                    """, ReportDartMessages.PENDING, ts(now), ts(now));
        } else {
            jdbc.update("""
                    UPDATE news_report_dart_attachments SET status = 'DISABLED', message = ?, updated_at = ?
                    WHERE status = 'PENDING'
                    """, ReportDartMessages.DISABLED, ts(now));
        }
    }

    void retry(long reportId, boolean enabled, LocalDateTime now) {
        jdbc.update("""
                UPDATE news_report_dart_attachments SET status = ?, message = ?, attempts = 0, next_attempt_at = ?, updated_at = ?,
                    lease_token = NULL, lease_expires_at = NULL
                WHERE report_id = ? AND status IN ('FAILED', 'DISABLED')
                """, enabled ? "PENDING" : "DISABLED", enabled ? ReportDartMessages.PENDING : ReportDartMessages.DISABLED,
                ts(now), ts(now), reportId);
    }

    void complete(Job job, String token, FinancialData data, LocalDateTime now) {
        boolean partial = data.quarters() == null || data.quarters().size() < 12
                || data.quarters().stream().anyMatch(q -> q.revenue() == null || q.operatingProfit() == null);
        String json = mapper.writeValueAsString(data);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                UPDATE news_report_dart_attachments SET status = ?, message = ?, payload_json = ?,
                    lease_token = NULL, lease_expires_at = NULL, updated_at = ?
                WHERE report_id = ? AND corp_code = ? AND status = 'RUNNING' AND lease_token = ?
                """);
            statement.setString(1, partial ? "PARTIAL" : "READY");
            statement.setString(2, partial ? ReportDartMessages.PARTIAL : ReportDartMessages.READY);
            statement.setCharacterStream(3, new StringReader(json), json.length());
            statement.setTimestamp(4, ts(now));
            statement.setLong(5, job.reportId());
            statement.setString(6, job.company().corpCode());
            statement.setString(7, token);
            return statement;
        });
    }

    void defer(Job job, String token, LocalDateTime retryAt, LocalDateTime now) {
        jdbc.update("""
                UPDATE news_report_dart_attachments SET status = 'PENDING', message = ?, next_attempt_at = ?, updated_at = ?,
                    lease_token = NULL, lease_expires_at = NULL
                WHERE report_id = ? AND corp_code = ? AND status = 'RUNNING' AND lease_token = ?
                """, ReportDartMessages.PENDING, ts(retryAt), ts(now), job.reportId(), job.company().corpCode(), token);
    }

    void fail(Job job, String token, String status, String message, LocalDateTime retryAt, LocalDateTime now) {
        jdbc.update("""
                UPDATE news_report_dart_attachments SET status = ?, message = ?, attempts = attempts + ?,
                    next_attempt_at = ?, updated_at = ?, lease_token = NULL, lease_expires_at = NULL
                WHERE report_id = ? AND corp_code = ? AND status = 'RUNNING' AND lease_token = ?
                """, status, message, "DISABLED".equals(status) ? 0 : 1,
                ts(retryAt), ts(now), job.reportId(), job.company().corpCode(), token);
    }

    Optional<Cached> cached(String corpCode) {
        return jdbc.query("SELECT payload_json, cached_at FROM news_dart_financial_cache WHERE corp_code = ? AND payload_json IS NOT NULL",
                (rs, row) -> new Cached(read(rs.getString(1)), rs.getTimestamp(2).toLocalDateTime()), corpCode).stream().findFirst();
    }

    boolean claimCache(String corpCode, String token, LocalDateTime now, LocalDateTime expires) {
        // Oracle statement-level uniqueness failures do not invalidate the enclosing JDBC transaction.
        try {
            jdbc.update("INSERT INTO news_dart_financial_cache (corp_code) SELECT ? FROM dual WHERE NOT EXISTS (SELECT 1 FROM news_dart_financial_cache WHERE corp_code = ?)", corpCode, corpCode);
        } catch (DuplicateKeyException alreadyCreated) {
            // Another instance created the same company cache row concurrently.
        }
        return jdbc.update("""
                UPDATE news_dart_financial_cache SET lease_token = ?, lease_expires_at = ?
                WHERE corp_code = ? AND (lease_token IS NULL OR lease_expires_at <= ?)
                """, token, ts(expires), corpCode, ts(now)) == 1;
    }

    boolean saveCache(String corpCode, String token, FinancialData data, LocalDateTime now) {
        String json = mapper.writeValueAsString(data);
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                UPDATE news_dart_financial_cache SET payload_json = ?, cached_at = ?, lease_token = NULL, lease_expires_at = NULL
                WHERE corp_code = ? AND lease_token = ?
                """);
            statement.setCharacterStream(1, new StringReader(json), json.length());
            statement.setTimestamp(2, ts(now));
            statement.setString(3, corpCode);
            statement.setString(4, token);
            return statement;
        }) == 1;
    }

    void releaseCache(String corpCode, String token) {
        jdbc.update("UPDATE news_dart_financial_cache SET lease_token = NULL, lease_expires_at = NULL WHERE corp_code = ? AND lease_token = ?", corpCode, token);
    }

    boolean paused(LocalDateTime now) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT CASE WHEN paused_until > ? THEN 1 ELSE 0 END FROM news_dart_fetch_state WHERE id = 1", Integer.class, ts(now)));
    }

    void pauseUntil(LocalDateTime until) {
        jdbc.update("UPDATE news_dart_fetch_state SET paused_until = CASE WHEN paused_until IS NULL OR paused_until < ? THEN ? ELSE paused_until END WHERE id = 1", ts(until), ts(until));
    }

    private FinancialData read(String json) {
        // The attachment stores the original offset as well as its instant; do not rewrite it to the mapper's UTC zone.
        return json == null ? null : mapper.readerFor(FinancialData.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(json);
    }
    private static Timestamp ts(LocalDateTime time) { return Timestamp.valueOf(time); }
}
