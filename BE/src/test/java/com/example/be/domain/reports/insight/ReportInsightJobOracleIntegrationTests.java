package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportPersistenceService;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportInsightJobOracleIntegrationTests {
    @Autowired NewsReportRepository reports;
    @Autowired CollectionRunRepository runs;
    @Autowired ReportInsightJobRepository jobs;
    @Autowired ReportInsightJobPersistence persistence;
    @Autowired ReportPersistenceService reportPersistence;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    final List<Long> created = new ArrayList<>();
    final List<Long> createdRuns = new ArrayList<>();

    @AfterEach void removeOwnedFixtures() {
        for (var id : created) jdbc.update("DELETE FROM news_report_insight_jobs WHERE report_id = ?", id);
        for (var id : created) jdbc.update("DELETE FROM news_reports WHERE id = ?", id);
        for (var id : createdRuns) jdbc.update("DELETE FROM news_collection_runs WHERE id = ?", id);
    }

    private NewsReport report(boolean requested, ReportStatus status) {
        var run = runs.saveAndFlush(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).startedAt(LocalDateTime.now(ApiTimeZone.ZONE)).build());
        createdRuns.add(run.getId());
        var report = reports.saveAndFlush(NewsReport.builder().reportScope(ReportScope.RUN).run(run)
                .title("자동 관점 분석 통합 검증").markdownBody("검증 자료").modelName("test")
                .automaticInsightsRequested(requested).reportStatus(status).generatedAt(LocalDateTime.now(ApiTimeZone.ZONE)).build());
        created.add(report.getId());
        return report;
    }

    @Test void completionPersistsMarkerAndAllFourJobsAfterCommitAndIgnoresLateCompletion() {
        var pending = report(false, ReportStatus.PENDING);
        reportPersistence.complete(pending.getId(), new ReportDocument("완료", "검증된 보고서", "fallback"), LocalDateTime.now(ApiTimeZone.ZONE));
        assertTrue(reports.findById(pending.getId()).orElseThrow().isAutomaticInsightsRequested());
        assertEquals("FALLBACK", jdbc.queryForObject("SELECT report_status FROM news_reports WHERE id = ?", String.class, pending.getId()));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM news_report_insight_jobs WHERE report_id = ?", Integer.class, pending.getId()));
        persistence.enqueue(pending.getId());
        reportPersistence.complete(pending.getId(), new ReportDocument("뒤늦은 완료", "대체 본문", "fallback"), LocalDateTime.now(ApiTimeZone.ZONE));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM news_report_insight_jobs WHERE report_id = ?", Integer.class, pending.getId()));
        assertEquals("검증된 보고서", reports.findById(pending.getId()).orElseThrow().getMarkdownBody());
    }

    @Test void rolledBackCompletionHasNeitherRequestedMarkerNorJobs() {
        var pending = report(false, ReportStatus.PENDING);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            reportPersistence.complete(pending.getId(), new ReportDocument("롤백", "검증", "fallback"), LocalDateTime.now(ApiTimeZone.ZONE));
            transaction.setRollbackOnly();
        });
        var reloaded = reports.findById(pending.getId()).orElseThrow();
        assertEquals(ReportStatus.PENDING, reloaded.getReportStatus());
        assertFalse(reloaded.isAutomaticInsightsRequested());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM news_report_insight_jobs WHERE report_id = ?", Integer.class, pending.getId()));
    }

    @Test void onlyDurableNewCompletionIntentIsRecoveredAndPartialRegistrationIsIdempotent() {
        var legacy = report(false, ReportStatus.GENERATED);
        var requested = report(true, ReportStatus.GENERATED);
        var hidden = report(true, ReportStatus.GENERATED);
        jdbc.update("UPDATE news_reports SET deleted_at = SYSTIMESTAMP WHERE id = ?", hidden.getId());
        var pending = report(true, ReportStatus.PENDING);
        jobs.enqueue(requested.getId(), Audience.CHIP_MAKER, LocalDateTime.now(ApiTimeZone.ZONE));
        var missing = jobs.missingJobs();
        assertTrue(missing.contains(requested.getId()));
        assertFalse(missing.contains(legacy.getId()));
        assertFalse(missing.contains(hidden.getId()));
        assertFalse(missing.contains(pending.getId()));
        assertTrue(jobs.isPending(requested.getId(), Audience.IT_INFRA));
        persistence.enqueue(requested.getId());
        persistence.enqueue(requested.getId());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM news_report_insight_jobs WHERE report_id = ?", Integer.class, requested.getId()));
        assertFalse(jobs.missingJobs().contains(requested.getId()));
        assertFalse(jobs.isPending(legacy.getId(), Audience.CHIP_MAKER));
    }

    @Test void concurrentClaimsHaveOneOwnerAndStaleRunningIsNeverRepeatedOrPublishedLate() throws Exception {
        var report = report(true, ReportStatus.GENERATED);
        persistence.enqueue(report.getId());
        var job = new ReportInsightJobRepository.Job(report.getId(), Audience.CHIP_MAKER);
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { latch.await(); return persistence.claim(job); });
            var second = pool.submit(() -> { latch.await(); return persistence.claim(job); });
            latch.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals("RUNNING", status(job));
        assertTrue(jobs.isPending(job.reportId(), job.audience()));
        // Claims and expiry cutoffs use the application time zone, including on UTC CI hosts.
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        jobs.expireRunning(now.minusMinutes(1), now);
        assertEquals("RUNNING", status(job));
        jobs.expireRunning(now.plusMinutes(1), now);
        assertEquals("FAILED", status(job));
        assertFalse(jobs.isPending(job.reportId(), job.audience()));
        assertFalse(persistence.claim(job));
        persistence.finish(job, true);
        assertEquals("FAILED", status(job));
        assertTrue(jobs.isPending(job.reportId(), Audience.IT_INFRA));
        assertFalse(jobs.missingJobs().contains(report.getId()));
    }

    private String status(ReportInsightJobRepository.Job job) {
        return jdbc.queryForObject("SELECT status FROM news_report_insight_jobs WHERE report_id = ? AND audience = ?",
                String.class, job.reportId(), job.audience().name());
    }
}
