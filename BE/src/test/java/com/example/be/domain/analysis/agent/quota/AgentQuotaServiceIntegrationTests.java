package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Transactional
@Rollback
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class AgentQuotaServiceIntegrationTests {

    @Autowired
    private AgentQuotaService quotaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reservesAndSettlesAgainstOracleWithTaskUsageQuery() {
        String key = "integration:a3:quota:" + UUID.randomUUID();

        QuotaReservation reservation = quotaService.reserve(
                null, key, AgentTask.REPORT, AgentPlan.FREE);
        quotaService.completeSuccess(reservation, BigDecimal.ZERO);

        assertNotNull(reservation.id());
        Map<String, Object> settled = jdbcTemplate.queryForMap("""
                SELECT status, consumed_units
                FROM agent_quota_reservations
                WHERE idempotency_key = ?
                """, key);
        assertEquals("CONSUMED", settled.get("STATUS"));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) settled.get("CONSUMED_UNITS")));
    }

    @Test
    void expiredReservationIsReleasedAndActualAgentRunUsageRemainsCounted() {
        BigDecimal before = quotaService.usage().free().dailyCallsUsed();
        String key = "integration:a3:reconcile:" + UUID.randomUUID();
        QuotaReservation reservation = quotaService.reserve(
                null, key, AgentTask.REPORT, AgentPlan.FREE);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO agent_runs (
                    idempotency_key, agent_task, target_type, status, llm_plan,
                    input_tokens, output_tokens, cost_usd, credits, started_at, finished_at
                ) VALUES (?, 'REPORT', 'RUN', 'SUCCESS', 'FREE', 1, 1, 0, 0, ?, ?)
                """, key, Timestamp.valueOf(now), Timestamp.valueOf(now));
        jdbcTemplate.update("""
                UPDATE agent_quota_reservations
                SET reserved_at = ?
                WHERE id = ?
                """, Timestamp.valueOf(now.minusHours(1)), reservation.id());

        BigDecimal after = quotaService.usage().free().dailyCallsUsed();

        assertEquals(0, before.add(BigDecimal.ONE).compareTo(after));
        assertEquals("RELEASED", jdbcTemplate.queryForObject(
                "SELECT status FROM agent_quota_reservations WHERE id = ?",
                String.class,
                reservation.id()));
    }
}
