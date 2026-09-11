package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportComparisonRepository {
    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();

    public void captureInput(NewsReport report, ReportComparisonSnapshot snapshot, LocalDateTime now) {
        String json = JSON.writeValueAsString(snapshot.withScopes(report.getCollectionContexts()));
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO news_report_comparison_inputs
                    (report_id, snapshot_json, input_hash, captured_at, usable_yn) VALUES (?, ?, ?, ?, 'Y')
                    """);
            statement.setLong(1, report.getId());
            statement.setCharacterStream(2, new StringReader(json), json.length());
            statement.setString(3, hash(json));
            statement.setTimestamp(4, Timestamp.valueOf(now));
            return statement;
        });
    }

    public Optional<ReportComparisonSnapshot> findInput(long reportId) {
        return jdbc.query("""
                SELECT input.snapshot_json FROM news_report_comparison_inputs input
                JOIN news_reports report ON report.id = input.report_id
                WHERE input.report_id = ? AND input.usable_yn = 'Y' AND report.comparison_input_usable_yn = 'Y'
                """, (rs, row) -> JSON.readValue(rs.getString(1), ReportComparisonSnapshot.class), reportId)
                .stream().findFirst();
    }

    public void invalidateInput(long reportId) {
        jdbc.update("UPDATE news_report_comparison_inputs SET usable_yn = 'N' WHERE report_id = ?", reportId);
    }

    public Optional<Job> find(long reportId) {
        return jdbc.query("SELECT * FROM news_report_comparisons WHERE report_id = ?", (rs, row) -> {
            String work = rs.getString("work_json");
            return new Job(reportId, ReportChanges.Status.valueOf(rs.getString("status")),
                    work == null ? null : JSON.readValue(work, ComparisonWork.class),
                    JSON.readValue(rs.getString("result_json"), ReportChanges.class));
        }, reportId).stream().findFirst();
    }

    public void insert(ReportChanges result, ComparisonWork work, LocalDateTime now) {
        String workJson = work == null ? null : JSON.writeValueAsString(work);
        String resultJson = JSON.writeValueAsString(result);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO news_report_comparisons
                    (report_id, base_report_id, status, work_json, result_json, input_hash, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setLong(1, result.reportId());
            statement.setObject(2, result.baseReportId());
            statement.setString(3, result.status().name());
            if (workJson == null) statement.setNull(4, java.sql.Types.CLOB);
            else statement.setCharacterStream(4, new StringReader(workJson), workJson.length());
            statement.setCharacterStream(5, new StringReader(resultJson), resultJson.length());
            statement.setString(6, workJson == null ? null : hash(workJson));
            statement.setTimestamp(7, Timestamp.valueOf(now));
            return statement;
        });
    }

    public List<Long> missingJobs() {
        return jdbc.query("""
                SELECT report.id FROM news_reports report
                JOIN news_report_comparison_inputs input ON input.report_id = report.id
                WHERE report.report_scope = 'DAILY' AND report.report_status <> 'PENDING'
                  AND report.deleted_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM news_report_comparisons job WHERE job.report_id = report.id)
                ORDER BY report.report_date, report.id FETCH FIRST 10 ROWS ONLY
                """, (rs, row) -> rs.getLong(1));
    }

    public List<Long> pending() {
        return jdbc.query("""
                SELECT report_id FROM news_report_comparisons WHERE status = 'PENDING'
                ORDER BY created_at, report_id FETCH FIRST 5 ROWS ONLY
                """, (rs, row) -> rs.getLong(1));
    }

    /** Single compare-and-set statement: the losing instance must never call a provider. */
    public boolean claim(long reportId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE news_report_comparisons SET status = 'RUNNING', started_at = ?
                WHERE report_id = ? AND status = 'PENDING'
                """, Timestamp.valueOf(now), reportId) == 1;
    }

    public void expireRunning(LocalDateTime before, LocalDateTime now) {
        jdbc.update("""
                UPDATE news_report_comparisons SET status = 'FAILED', finished_at = ?
                WHERE status = 'RUNNING' AND started_at < ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(before));
    }

    public void finish(long reportId, ReportChanges result, LocalDateTime now) {
        String json = JSON.writeValueAsString(result);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    UPDATE news_report_comparisons SET status = ?, result_json = ?, finished_at = ?
                    WHERE report_id = ? AND status = 'RUNNING'
                    """);
            statement.setString(1, result.status().name());
            statement.setCharacterStream(2, new StringReader(json), json.length());
            statement.setTimestamp(3, Timestamp.valueOf(now));
            statement.setLong(4, reportId);
            return statement;
        });
    }

    /** A merge requires both the directional relation and its precise recorded merge history. */
    public List<ComparisonWork.IdentityLink> mergeParents(long currentIssueId, LocalDateTime before) {
        return jdbc.query("""
                SELECT DISTINCT relation.from_issue_id, relation.to_issue_id
                FROM news_issue_relations relation
                JOIN news_issues previous ON previous.id = relation.from_issue_id
                JOIN news_issues current_issue ON current_issue.id = relation.to_issue_id
                WHERE relation.to_issue_id = ? AND relation.relation_type = 'UPDATES'
                  AND relation.created_at <= ? AND previous.article_count = 0 AND previous.status = 'RETRACTED'
                  AND previous.topic_id = current_issue.topic_id
                  AND EXISTS (SELECT 1 FROM news_issue_status_history history
                      WHERE history.issue_id = previous.id AND history.to_status = 'RETRACTED'
                        AND history.reason = '이슈 병합: #' || TO_CHAR(relation.to_issue_id)
                        AND history.changed_at <= ?)
                FETCH FIRST 101 ROWS ONLY
                """, (rs, row) -> new ComparisonWork.IdentityLink(rs.getLong(1), rs.getLong(2), "MERGED"),
                currentIssueId, Timestamp.valueOf(before), Timestamp.valueOf(before));
    }

    public List<ComparisonWork.IdentityLink> refutedIssues(long currentIssueId, LocalDateTime before) {
        return jdbc.query("""
                SELECT relation.to_issue_id, relation.from_issue_id
                FROM news_issue_relations relation
                JOIN news_issues previous ON previous.id = relation.to_issue_id
                JOIN news_issues current_issue ON current_issue.id = relation.from_issue_id
                WHERE relation.from_issue_id = ? AND relation.relation_type = 'REFUTES'
                  AND relation.created_at <= ? AND previous.topic_id = current_issue.topic_id
                FETCH FIRST 101 ROWS ONLY
                """, (rs, row) -> new ComparisonWork.IdentityLink(rs.getLong(1), rs.getLong(2), "REFUTES"),
                currentIssueId, Timestamp.valueOf(before));
    }

    public record Job(long reportId, ReportChanges.Status status, ComparisonWork work, ReportChanges result) { }

    static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
