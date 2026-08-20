package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AgentQuotaJdbcRepository {

    private static final String FIND_SQL = """
            SELECT id, collection_run_id, idempotency_key, agent_task, llm_plan, reserved_units
            FROM agent_quota_reservations
            WHERE idempotency_key = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO agent_quota_reservations (
                collection_run_id, idempotency_key, agent_task, llm_plan,
                reserved_units, status, reserved_at
            ) VALUES (?, ?, ?, ?, ?, 'RESERVED', ?)
            """;
    private static final String RESERVATION_USAGE_SQL = """
            SELECT COALESCE(SUM(
                CASE status
                    WHEN 'RESERVED' THEN reserved_units
                    WHEN 'CONSUMED' THEN consumed_units
                    ELSE 0
                END
            ), 0)
            FROM agent_quota_reservations
            WHERE llm_plan = ? AND reserved_at >= ? AND reserved_at < ?
            """;
    private static final String RESERVATION_TASK_USAGE_SQL = RESERVATION_USAGE_SQL
            + " AND agent_task = ?";
    private static final String LEGACY_PAID_USAGE_SQL = """
            SELECT COALESCE(SUM(COALESCE(run.credits, 0)), 0)
            FROM agent_runs run
            WHERE run.llm_plan = 'PAID'
              AND run.started_at >= ? AND run.started_at < ?
              AND NOT EXISTS (
                  SELECT 1 FROM agent_quota_reservations reservation
                  WHERE reservation.idempotency_key = run.idempotency_key
              )
            """;
    private static final String LEGACY_FREE_USAGE_SQL = """
            SELECT COUNT(*)
            FROM agent_runs run
            WHERE run.llm_plan = 'FREE'
              AND run.started_at >= ? AND run.started_at < ?
              AND NOT EXISTS (
                  SELECT 1 FROM agent_quota_reservations reservation
                  WHERE reservation.idempotency_key = run.idempotency_key
              )
            """;
    private static final String LEGACY_PAID_TASK_USAGE_SQL = """
            SELECT COALESCE(SUM(COALESCE(run.credits, 0)), 0)
            FROM agent_runs run
            WHERE run.llm_plan = 'PAID'
              AND run.started_at >= ? AND run.started_at < ?
              AND run.agent_task = ?
              AND NOT EXISTS (
                  SELECT 1 FROM agent_quota_reservations reservation
                  WHERE reservation.idempotency_key = run.idempotency_key
              )
            """;

    private final JdbcTemplate jdbcTemplate;

    public void lockSingletonSettings() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM app_settings WHERE id = 1 FOR UPDATE", Long.class);
        if (id == null) {
            throw new IllegalStateException("app_settings 단일 행이 없습니다.");
        }
    }

    public Optional<QuotaReservation> findByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query(FIND_SQL, (rs, rowNum) -> new QuotaReservation(
                rs.getLong("id"),
                nullableLong(rs.getObject("collection_run_id")),
                rs.getString("idempotency_key"),
                AgentTask.valueOf(rs.getString("agent_task")),
                AgentPlan.valueOf(rs.getString("llm_plan")),
                rs.getBigDecimal("reserved_units")), idempotencyKey).stream().findFirst();
    }

    public void insert(Long collectionRunId,
                       String idempotencyKey,
                       AgentTask task,
                       AgentPlan plan,
                       BigDecimal units,
                       LocalDateTime reservedAt) {
        jdbcTemplate.update(
                INSERT_SQL,
                collectionRunId,
                idempotencyKey,
                task.name(),
                plan.name(),
                units,
                Timestamp.valueOf(reservedAt));
    }

    public BigDecimal usage(AgentPlan plan, LocalDateTime from, LocalDateTime to) {
        Timestamp start = Timestamp.valueOf(from);
        Timestamp end = Timestamp.valueOf(to);
        BigDecimal reserved = jdbcTemplate.queryForObject(
                RESERVATION_USAGE_SQL,
                BigDecimal.class,
                plan.name(),
                start,
                end);
        BigDecimal legacy = jdbcTemplate.queryForObject(
                plan == AgentPlan.PAID ? LEGACY_PAID_USAGE_SQL : LEGACY_FREE_USAGE_SQL,
                BigDecimal.class,
                start,
                end);
        return nonNull(reserved).add(nonNull(legacy));
    }

    public BigDecimal usage(AgentPlan plan,
                            LocalDateTime from,
                            LocalDateTime to,
                            AgentTask task) {
        Timestamp start = Timestamp.valueOf(from);
        Timestamp end = Timestamp.valueOf(to);
        BigDecimal reserved = jdbcTemplate.queryForObject(
                RESERVATION_TASK_USAGE_SQL,
                BigDecimal.class,
                plan.name(),
                start,
                end,
                task.name());
        BigDecimal legacy = plan == AgentPlan.PAID
                ? jdbcTemplate.queryForObject(
                        LEGACY_PAID_TASK_USAGE_SQL,
                        BigDecimal.class,
                        start,
                        end,
                        task.name())
                : BigDecimal.ZERO;
        return nonNull(reserved).add(nonNull(legacy));
    }

    public int countRunAnalysis(Long runId, AgentPlan plan) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM agent_quota_reservations
                WHERE collection_run_id = ?
                  AND llm_plan = ?
                  AND agent_task = 'ANALYZE'
                  AND status <> 'RELEASED'
                """, Integer.class, runId, plan.name());
        return count == null ? 0 : count;
    }

    public void consume(QuotaReservation reservation,
                        BigDecimal consumedUnits,
                        LocalDateTime finishedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_quota_reservations
                SET status = 'CONSUMED', consumed_units = ?, finished_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """, consumedUnits, Timestamp.valueOf(finishedAt), reservation.id());
        requireUpdated(updated, reservation);
    }

    public void release(QuotaReservation reservation, LocalDateTime finishedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_quota_reservations
                SET status = 'RELEASED', consumed_units = 0, finished_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """, Timestamp.valueOf(finishedAt), reservation.id());
        requireUpdated(updated, reservation);
    }

    private void requireUpdated(int updated, QuotaReservation reservation) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "quota 예약이 이미 종료되었거나 없습니다. id=" + reservation.id());
        }
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
