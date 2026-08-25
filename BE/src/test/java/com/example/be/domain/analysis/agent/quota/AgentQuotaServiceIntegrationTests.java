package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.global.config.ApiTimeZone;
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
        /*
         * 기본 시간대로 시각을 만들면 안 된다. usage()가 하루 경계를 LocalDate.now(ApiTimeZone.ZONE)로
         * 잡는데, CI 러너는 UTC라 KST와 날짜가 갈린다. UTC 22:37에 넣은 행은 KST로 이미 다음 날이라
         * 오늘 사용량에 잡히지 않고, 아래 before + 1 검증이 깨진다. KST 00~09시에만 실패했던 이유다.
         */
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
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
