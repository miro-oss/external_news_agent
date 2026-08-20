package com.example.be.domain.analysis.agent.quota;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

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
    private AgentQuotaJdbcRepository repository;

    @Test
    void reservesAndSettlesAgainstOracleWithTaskUsageQuery() {
        String key = "integration:a3:quota:free-report";

        QuotaReservation reservation = quotaService.reserve(
                null, key, AgentTask.REPORT, AgentPlan.FREE);
        quotaService.completeSuccess(reservation, BigDecimal.ZERO);

        assertNotNull(reservation.id());
        assertEquals(reservation, repository.findByIdempotencyKey(key).orElseThrow());
    }
}
