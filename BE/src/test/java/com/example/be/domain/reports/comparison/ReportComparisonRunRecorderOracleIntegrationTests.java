package com.example.be.domain.reports.comparison;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentReportChangesRequest;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaJdbcRepository;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "news.agent.enabled=false")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportComparisonRunRecorderOracleIntegrationTests {
    @Autowired ReportComparisonRunRecorder recorder;
    @Autowired AgentQuotaService quota;
    @Autowired AgentQuotaJdbcRepository reservations;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AgentRunJdbcRepository runs;
    private final String key = "integration:comparison-atomic:" + UUID.randomUUID();

    @AfterEach void removeOwnedRows() {
        jdbc.update("DELETE FROM agent_runs WHERE idempotency_key = ?", key);
        jdbc.update("DELETE FROM agent_quota_reservations WHERE idempotency_key = ?", key);
    }

    @Test void overCapFailureCommitsAuditAndReleaseAndCountsActualCostOnce() {
        var started = LocalDateTime.now(ApiTimeZone.ZONE);
        var since = started.toLocalDate().atStartOfDay();
        var until = since.plusDays(1);
        var before = reservations.analysisUsage(since, until);
        var reservation = reserve();
        var actual = reservation.reservedUnits().add(BigDecimal.ONE);

        recorder.failure(request(), failure(actual), started, reservation);

        assertEquals("RELEASED", status());
        assertEquals(1, auditCount());
        assertEquals(0, before.add(actual).compareTo(reservations.analysisUsage(since, until)));
    }

    @Test void auditWriteFailureRollsBackAndKeepsReservationCharged() {
        var reservation = reserve();
        doThrow(new DataAccessResourceFailureException("injected audit failure"))
                .when(runs).insertIfAbsent(any());

        assertThrows(DataAccessResourceFailureException.class, () -> recorder.failure(request(),
                failure(reservation.reservedUnits().add(BigDecimal.ONE)), LocalDateTime.now(ApiTimeZone.ZONE), reservation));

        assertEquals("RESERVED", status());
        assertEquals(0, auditCount());
        assertEquals(0, reservation.reservedUnits().compareTo(reservations.findByIdempotencyKey(key).orElseThrow().reservedUnits()));
    }

    @Test void settlementFailureRollsBackTheAuditTogetherWithReservationChanges() {
        var reservation = reserve();
        var absentReservation = new QuotaReservation(-1L, null, key, AgentTask.REPORT_CHANGES,
                AgentPlan.PAID, reservation.reservedUnits());

        var exception = assertThrows(InvalidDataAccessApiUsageException.class, () -> recorder.failure(request(),
                failure(reservation.reservedUnits().add(BigDecimal.ONE)), LocalDateTime.now(ApiTimeZone.ZONE), absentReservation));

        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertEquals("RESERVED", status());
        assertEquals(0, auditCount());
    }

    @Test void duplicateAuditDoesNotReleaseUnrecordedOverCapUsage() {
        var reservation = reserve();
        var started = LocalDateTime.now(ApiTimeZone.ZONE);
        jdbc.update("""
                INSERT INTO agent_runs (idempotency_key, agent_task, target_type, target_id,
                    status, llm_plan, credits, started_at, finished_at)
                VALUES (?, 'REPORT_CHANGES', 'REPORT', 101, 'FAILED', 'PAID', 1, ?, ?)
                """, key, java.sql.Timestamp.valueOf(started), java.sql.Timestamp.valueOf(started));

        assertThrows(IllegalStateException.class, () -> recorder.failure(request(),
                failure(reservation.reservedUnits().add(BigDecimal.ONE)), started, reservation));

        assertEquals("RESERVED", status());
        assertEquals(1, auditCount());
    }

    private QuotaReservation reserve() { return quota.reserve(null, key, AgentTask.REPORT_CHANGES, AgentPlan.PAID); }
    private AgentReportChangesRequest request() { return new AgentReportChangesRequest(key, AgentPlan.PAID, 101, 100, List.of()); }
    private AgentClientException failure(BigDecimal credits) {
        return new AgentClientException("SCHEMA_VIOLATION", "invalid comparison output", null,
                new AgentClientException.Usage(10L, 5L, new BigDecimal("0.001"), credits));
    }
    private String status() {
        return jdbc.queryForObject("SELECT status FROM agent_quota_reservations WHERE idempotency_key = ?", String.class, key);
    }
    private int auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE idempotency_key = ?", Integer.class, key);
    }
}
