package com.example.be.domain.reports;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.notifications.entity.DeliveryBatch;
import com.example.be.domain.notifications.service.NotificationDeliveryPlanService;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.exception.ReportException;
import com.example.be.domain.reports.repository.DailyReportJdbcRepository;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.DailyReportPersistenceService;
import com.example.be.domain.reports.service.ReportCommandService;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportPersistenceService;
import com.example.be.domain.reports.service.ReportQueryService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportManagementOracleIntegrationTests {

    @Autowired private NewsReportRepository reports;
    @Autowired private CollectionRunRepository runs;
    @Autowired private ReportCommandService commands;
    @Autowired private ReportQueryService queries;
    @Autowired private ReportPersistenceService persistence;
    @Autowired private DailyReportPersistenceService dailyPersistence;
    @Autowired private DailyReportJdbcRepository dailyRepository;
    @Autowired private NotificationDeliveryPlanService deliveryPlans;
    @Autowired private EntityManager entityManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void softDeletionHidesEveryPublicReadAndPreservesReferencesAndRunReservation() {
        LocalDateTime now = LocalDateTime.of(2098, 2, 3, 10, 0);
        NewsReport earlier = report(run(now.minusHours(1)), now.minusHours(1), ReportStatus.FALLBACK);
        NewsReport latest = report(run(now), now, ReportStatus.FALLBACK);
        latest.getRun().attachReport(latest.getId());
        String batchId = UUID.randomUUID().toString();
        entityManager.persist(DeliveryBatch.builder().id(batchId).report(latest)
                .requestedAt(now).completedAt(now).build());
        entityManager.flush();
        assertEquals(latest.getId(), queries.getLatest(false).getId());

        assertTrue(commands.deleteReport(latest.getId()).deleted());
        entityManager.flush();
        entityManager.clear();

        NewsReport hidden = reports.findById(latest.getId()).orElseThrow();
        assertNotNull(hidden.getDeletedAt());
        assertEquals("보존할 본문", hidden.getMarkdownBody());
        assertEquals(latest.getId(), entityManager.find(DeliveryBatch.class, batchId).getReport().getId());
        assertEquals(latest.getId(), runs.findById(latest.getRunId()).orElseThrow().getReportId());
        assertEquals(earlier.getId(), queries.getLatest(false).getId());
        assertEquals(earlier.getId(), queries.getLatest(false, ReportScope.RUN).getId());
        var page = queries.getReports(now.toLocalDate().toString(), now.toLocalDate().toString(), 0, 20);
        assertEquals(1, page.getTotalElements());
        assertEquals(List.of(earlier.getId()), page.getContent().stream().map(value -> value.getId()).toList());
        assertThrows(ReportException.class, () -> queries.getReport(latest.getId(), false));
        assertThrows(ReportException.class, () -> deliveryPlans.requireReport(latest.getId()));
        var retry = persistence.reserve(latest.getRunId(), now.plusHours(1), false);
        assertFalse(retry.owner());
        assertEquals(latest.getId(), retry.reportId());
        LocalDateTime deletedAt = hidden.getDeletedAt();
        assertTrue(commands.deleteReport(latest.getId()).deleted());
        assertEquals(deletedAt, reports.findById(latest.getId()).orElseThrow().getDeletedAt());
    }

    @Test
    void dailySourceCountUsesCompletedReportsAndRemainsStableAfterDeletionOrLateGeneration() throws Exception {
        LocalDate date = LocalDate.of(1997, 2, 4);
        LocalDateTime now = date.plusDays(1).atStartOfDay();
        NewsReport first = report(run(date.atTime(8, 0)), date.atTime(8, 5), ReportStatus.FALLBACK);
        NewsReport hidden = report(run(date.atTime(9, 0)), date.atTime(9, 5), ReportStatus.FALLBACK);
        commands.deleteReport(hidden.getId());
        NewsReport pending = report(run(date.atTime(10, 0)), date.atTime(10, 5), ReportStatus.PENDING);
        NewsReport late = report(run(date.atTime(11, 0)), now.plusHours(1), ReportStatus.FALLBACK);
        CollectionRun withoutReport = run(date.atTime(12, 0));
        List<Long> sourceRunIds = List.of(first.getRunId(), hidden.getRunId(), pending.getRunId(),
                late.getRunId(), withoutReport.getId());
        var daily = dailyPersistence.reserve(date, sourceRunIds, List.of(), now);
        persistence.complete(daily.reportId(), new ReportDocument("일일 보고서", "저장된 일일 본문", "fallback"), now);
        entityManager.flush();
        entityManager.clear();

        assertEquals(2L, queries.getReport(daily.reportId(), false).getSourceReportCount());
        assertEquals(2L, queries.getLatest(false, ReportScope.DAILY).getSourceReportCount());
        assertEquals(2L, queries.getReports(null, null, 0, 20, ReportScope.DAILY)
                .getContent().stream().filter(value -> value.getId().equals(daily.reportId()))
                .findFirst().orElseThrow().getSourceReportCount());
        assertNull(queries.getReport(first.getId(), false).getSourceReportCount());

        // V43 이전 행처럼 원본 수가 없는 상태에서 실제 마이그레이션 SQL의 백필도 검증한다.
        jdbcTemplate.update("UPDATE news_reports SET source_report_count = NULL WHERE id = ?", daily.reportId());
        entityManager.clear();
        assertNull(reports.findById(daily.reportId()).orElseThrow().getSourceReportCount());
        String migration = new org.springframework.core.io.ClassPathResource(
                "db/migration/V43__report_visibility_and_source_count.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int updateStart = migration.indexOf("UPDATE news_reports daily");
        jdbcTemplate.execute(migration.substring(updateStart, migration.indexOf(';', updateStart)));
        entityManager.clear();
        assertEquals(2L, queries.getReport(daily.reportId(), false).getSourceReportCount());

        commands.deleteReport(first.getId());
        persistence.complete(pending.getId(), new ReportDocument("늦은 보고서", "늦은 본문", "fallback"), now.plusHours(2));
        assertEquals(2L, queries.getReport(daily.reportId(), false).getSourceReportCount());

        commands.deleteReport(daily.reportId());
        entityManager.flush();
        entityManager.clear();
        assertNull(queries.getLatest(false, ReportScope.DAILY));
        assertEquals(0, queries.getReports(null, null, 0, 20, ReportScope.DAILY).getTotalElements());
        assertThrows(ReportException.class, () -> queries.getReport(daily.reportId(), false));
        assertFalse(dailyPersistence.reserve(date, sourceRunIds, List.of(), now.plusDays(1)).owner());
        assertFalse(dailyRepository.findDueDates(date, date.plusDays(1)).contains(date));
    }

    private CollectionRun run(LocalDateTime startedAt) {
        return runs.save(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).startedAt(startedAt).build());
    }

    private NewsReport report(CollectionRun run, LocalDateTime generatedAt, ReportStatus status) {
        return reports.saveAndFlush(NewsReport.builder().run(run).title("수집 보고서")
                .markdownBody("보존할 본문").modelName("fallback").reportStatus(status)
                .generatedAt(generatedAt).build());
    }
}
