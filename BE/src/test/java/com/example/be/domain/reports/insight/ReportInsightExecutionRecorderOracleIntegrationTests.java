package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.client.AgentClientException;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightRequest;
import com.example.be.domain.analysis.agent.entity.*;
import com.example.be.domain.analysis.agent.quota.*;
import com.example.be.domain.analysis.agent.repository.AgentRunJdbcRepository;
import com.example.be.domain.analysis.agent.service.ReportInsightAuditContext;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.example.be.domain.reports.insight.ReportInsightTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "news.agent.enabled=false")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportInsightExecutionRecorderOracleIntegrationTests {
    @Autowired ReportInsightExecutionRecorder recorder;
    @Autowired AgentQuotaService quota;
    @Autowired AgentQuotaJdbcRepository reservations;
    @Autowired JdbcTemplate jdbc;
    @Autowired NewsReportRepository reports;
    @Autowired NewsReportInsightRepository insights;
    @MockitoSpyBean AgentRunJdbcRepository runs;
    @MockitoSpyBean ReportInsightPersistenceService persistence;
    private final String key = "integration:report-insight:" + UUID.randomUUID();
    private Long reportId;

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM agent_runs WHERE idempotency_key LIKE ?", key + "%");
        jdbc.update("DELETE FROM agent_quota_reservations WHERE idempotency_key LIKE ?", key + "%");
        if (reportId != null) {
            jdbc.update("DELETE FROM news_report_insights WHERE report_id = ?", reportId);
            jdbc.update("DELETE FROM news_reports WHERE id = ?", reportId);
        }
    }
    @Test void overCapFailureCommitsReportAuditAndReleaseTogetherAndCountsActualCredits() {
        var since = now().toLocalDate().atStartOfDay();
        var until = since.plusDays(1);
        var before = reservations.usage(AgentPlan.PAID, since, until, AgentTask.INSIGHT);
        var reservation = quota.reserveInsight(null, key, AgentPlan.PAID);
        var actual = reservation.reservedUnits().add(BigDecimal.ONE);
        recorder.failure(null, requestForKey(), failure(actual), audit(), now(), reservation);
        assertEquals("RELEASED", status());
        assertEquals("REPORT", jdbc.queryForObject("SELECT target_type FROM agent_runs WHERE idempotency_key=?", String.class, key));
        assertEquals("INSIGHT", jdbc.queryForObject("SELECT agent_task FROM agent_runs WHERE idempotency_key=?", String.class, key));
        assertEquals(0, before.add(actual).compareTo(reservations.usage(AgentPlan.PAID, since, until, AgentTask.INSIGHT)));
    }
    @Test void auditFailureKeepsReservationChargedInsteadOfLosingKnownExposure() {
        var reservation = quota.reserveInsight(null, key, AgentPlan.PAID);
        doThrow(new DataAccessResourceFailureException("injected audit failure")).when(runs).insertIfAbsent(any());
        assertThrows(DataAccessResourceFailureException.class, () -> recorder.failure(null, requestForKey(),
                failure(reservation.reservedUnits().add(BigDecimal.ONE)), audit(), now(), reservation));
        assertEquals("RESERVED", status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE idempotency_key=?", Integer.class, key));
    }
    @Test void consumedFailureRetriesWithNewKeyAndKeepsOriginalAuditAndCharge() {
        var original = quota.reserveReportInsight(null, key, AgentPlan.PAID);
        recorder.failure(null, requestForKey(), failure(BigDecimal.ONE), audit(), now(), original);
        var retry = quota.reserveReportInsight(null, key, AgentPlan.PAID);
        assertEquals(key + ":retry:1", retry.idempotencyKey());
        assertEquals("CONSUMED", status());
        assertEquals(0, BigDecimal.ONE.compareTo(jdbc.queryForObject(
                "SELECT consumed_units FROM agent_quota_reservations WHERE idempotency_key=?", BigDecimal.class, key)));
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM agent_quota_reservations WHERE idempotency_key=?",
                String.class, retry.idempotencyKey()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE idempotency_key=? AND status='FAILED'", Integer.class, key));
    }
    @Test void successAuditFailureRollsBackCacheInsertAndLeavesReservation() {
        var report = reports.saveAndFlush(NewsReport.builder().title("원자적 관점 저장")
                .reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2500, 1, 1).plusDays(new Random().nextInt(100000)))
                .markdownBody("본문").modelName("mock").reportStatus(ReportStatus.MOCK).generatedAt(now()).build());
        reportId = report.getId();
        var reservation = quota.reserveInsight(null, key, AgentPlan.PAID);
        doAnswer(call -> List.of(insights.saveAndFlush(NewsReportInsight.builder().reportId(reportId)
                .audience(Audience.CHIP_MAKER).inputHash("a".repeat(64)).promptVersion(ReportInsightService.PROMPT_VERSION)
                .rubricVersion(ReportInsightService.RUBRIC_VERSION).payloadJson("{}").inputFindingCount(2).createdAt(now()).build())))
                .when(persistence).saveGenerated(any(), any());
        doThrow(new DataAccessResourceFailureException("injected audit failure")).when(runs).insertIfAbsent(any());
        var original = snapshot("a".repeat(64));
        var snapshot = new ReportInsightSnapshotAssembler.Snapshot(reportId, null, original.inputHash(), original.report(), original.findings());
        assertThrows(DataAccessResourceFailureException.class, () -> recorder.success(snapshot, requestForKey(), response(), audit(), now(), reservation));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM news_report_insights WHERE report_id=?", Integer.class, reportId));
        assertEquals("RESERVED", status());
    }
    private AgentReportInsightRequest requestForKey() {
        var request = request();
        return new AgentReportInsightRequest(key, request.plan(), request.audiences(), request.report(), request.findings());
    }
    private AgentClientException failure(BigDecimal credits) {
        return new AgentClientException("SCHEMA_VIOLATION", "invalid output", null,
                new AgentClientException.Usage(10L, 5L, BigDecimal.ONE, credits));
    }
    private ReportInsightAuditContext audit() { return new ReportInsightAuditContext(1, "REPORT_INSIGHT_OBSERVATION",
            "a".repeat(64), ReportInsightService.PROMPT_VERSION, ReportInsightService.RUBRIC_VERSION, 2, 1, 0, true); }
    private LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
    private String status() { return jdbc.queryForObject("SELECT status FROM agent_quota_reservations WHERE idempotency_key=?", String.class, key); }
}
