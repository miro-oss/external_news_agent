package com.example.be.domain.analysis.agent.investigation;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class IssueInvestigationJdbcRepository {

    private static final int MAX_REASON_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;

    public IssueInvestigationState reserve(Long runId,
                                           Long issueId,
                                           String idempotencyKey,
                                           String triggerReason,
                                           int evidenceCount,
                                           LocalDateTime startedAt) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO news_issue_investigations (
                        collection_run_id, issue_id, idempotency_key, status, trigger_reason,
                        next_step, evidence_count_before, evidence_count_current,
                        added_article_count, started_at
                    ) VALUES (?, ?, ?, 'IN_PROGRESS', ?, 1, ?, ?, 0, ?)
            """, runId, issueId, idempotencyKey, truncate(triggerReason),
                    evidenceCount, evidenceCount, Timestamp.valueOf(startedAt));
        } catch (DuplicateKeyException ignored) {
            // 같은 run/issue 재개는 기존 상태를 그대로 사용한다.
        }
        return findByRunIdAndIssueId(runId, issueId).orElseThrow();
    }

    public Optional<IssueInvestigationState> findByRunIdAndIssueId(Long runId, Long issueId) {
        return jdbcTemplate.query("""
                SELECT id, collection_run_id, issue_id, idempotency_key, status, trigger_reason,
                       next_step, in_flight_step, evidence_count_before, evidence_count_current,
                       added_article_count, first_action_reason, rejection_reason, termination_reason
                FROM news_issue_investigations
                WHERE collection_run_id = ? AND issue_id = ?
                """, this::state, runId, issueId).stream().findFirst();
    }

    public boolean investigatedToday(Long issueId,
                                     LocalDateTime dayStart,
                                     LocalDateTime dayEnd,
                                     Long excludingRunId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM news_issue_investigations
                WHERE issue_id = ?
                  AND started_at >= ? AND started_at < ?
                  AND collection_run_id <> ?
                """, Integer.class, issueId, Timestamp.valueOf(dayStart),
                Timestamp.valueOf(dayEnd), excludingRunId);
        return count != null && count > 0;
    }

    public boolean markInFlight(Long id, int step) {
        return jdbcTemplate.update("""
                UPDATE news_issue_investigations
                SET in_flight_step = ?
                WHERE id = ? AND status = 'IN_PROGRESS'
                  AND in_flight_step IS NULL AND next_step = ?
                """, step, id, step) == 1;
    }

    public void completeStep(Long id,
                             int step,
                             int evidenceCount,
                             int addedArticleCount,
                             String actionReason,
                             String rejectionReason) {
        int updated = jdbcTemplate.update("""
                UPDATE news_issue_investigations
                SET next_step = ?, in_flight_step = NULL,
                    evidence_count_current = ?,
                    added_article_count = added_article_count + ?,
                    first_action_reason = COALESCE(first_action_reason, ?),
                    rejection_reason = COALESCE(?, rejection_reason)
                WHERE id = ? AND status = 'IN_PROGRESS' AND in_flight_step = ?
                """, step + 1, evidenceCount, addedArticleCount,
                truncate(actionReason), truncate(rejectionReason), id, step);
        if (updated != 1) {
            throw new IllegalStateException("조사 step 상태가 이미 변경됐습니다. id=" + id + " step=" + step);
        }
    }

    public void finish(Long id, String terminationReason, LocalDateTime finishedAt) {
        String status = "FAILED".equals(terminationReason) ? "FAILED" : "COMPLETED";
        jdbcTemplate.update("""
                UPDATE news_issue_investigations
                SET status = ?, termination_reason = ?, in_flight_step = NULL, finished_at = ?
                WHERE id = ? AND status = 'IN_PROGRESS'
                """, status, terminationReason, Timestamp.valueOf(finishedAt), id);
    }

    public Optional<InvestigationTrace> findTrace(Long runId, Long issueId) {
        return jdbcTemplate.query("""
                SELECT termination_reason, next_step, added_article_count,
                       evidence_count_before, evidence_count_current,
                       first_action_reason, rejection_reason
                FROM news_issue_investigations
                WHERE collection_run_id = ? AND issue_id = ? AND status <> 'IN_PROGRESS'
                """, (rs, rowNum) -> trace(rs), runId, issueId).stream().findFirst();
    }

    public Map<Long, InvestigationTrace> findTraces(Long runId) {
        Map<Long, InvestigationTrace> traces = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT issue_id, termination_reason, next_step, added_article_count,
                       evidence_count_before, evidence_count_current,
                       first_action_reason, rejection_reason
                FROM news_issue_investigations
                WHERE collection_run_id = ? AND status <> 'IN_PROGRESS'
                ORDER BY id ASC
                """, (rs, rowNum) -> Map.entry(
                rs.getLong("issue_id"), trace(rs)), runId).forEach(entry ->
                traces.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(traces);
    }

    private IssueInvestigationState state(ResultSet rs, int rowNum) throws SQLException {
        Object inFlight = rs.getObject("in_flight_step");
        return new IssueInvestigationState(
                rs.getLong("id"),
                rs.getLong("collection_run_id"),
                rs.getLong("issue_id"),
                rs.getString("idempotency_key"),
                rs.getString("status"),
                rs.getString("trigger_reason"),
                rs.getInt("next_step"),
                inFlight == null ? null : ((Number) inFlight).intValue(),
                rs.getInt("evidence_count_before"),
                rs.getInt("evidence_count_current"),
                rs.getInt("added_article_count"),
                rs.getString("first_action_reason"),
                rs.getString("rejection_reason"),
                rs.getString("termination_reason"));
    }

    private InvestigationTrace trace(ResultSet rs) throws SQLException {
        return new InvestigationTrace(
                rs.getString("termination_reason"),
                Math.max(0, rs.getInt("next_step") - 1),
                rs.getInt("added_article_count"),
                Math.max(0, rs.getInt("evidence_count_current")
                        - rs.getInt("evidence_count_before")),
                rs.getString("first_action_reason"),
                rs.getString("rejection_reason"));
    }

    private String truncate(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= MAX_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_REASON_LENGTH));
    }
}
