package com.example.be.domain.reports.dart;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.dart.DartFinancialService;
import com.example.be.domain.dart.DartModels.Company;
import com.example.be.domain.dart.DartModels.FinancialData;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportCollectionContext;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.global.config.ApiTimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.example.be.domain.reports.dart.DartReportFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {"news.agent.enabled=false", "news.dart.api-key=isolated-oracle-fixture"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportDartOracleIntegrationTests {
    static final Company COMPANY = new Company("99993201", "공시 첨부 통합검증 회사", "");
    @Autowired NewsReportRepository reports;
    @Autowired CollectionRunRepository runs;
    @Autowired ReportDartRepository repository;
    @Autowired ReportDartPersistence persistence;
    @Autowired ReportDartService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DartFinancialService financials;
    final List<Long> reportIds = new ArrayList<>();
    final List<Long> runIds = new ArrayList<>();

    @AfterEach void removeOwnedFixtures() {
        for (var id : reportIds) jdbc.update("DELETE FROM news_reports WHERE id = ?", id);
        for (var id : runIds) jdbc.update("DELETE FROM news_collection_runs WHERE id = ?", id);
        jdbc.update("DELETE FROM news_dart_financial_cache WHERE corp_code = ?", COMPANY.corpCode());
    }

    private NewsReport create(boolean selected, ReportStatus status) {
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        var run = runs.saveAndFlush(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).startedAt(now).build());
        runIds.add(run.getId());
        var value = reports.saveAndFlush(NewsReport.builder().run(run).reportScope(ReportScope.RUN)
                .title("DART 첨부 통합검증").markdownBody("검증 보고서").modelName("test").reportStatus(status).generatedAt(now)
                .collectionContexts(List.of(new ReportCollectionContext(run.getId(), List.of(topic(1L, selected ? COMPANY : null)))))
                .build());
        reportIds.add(value.getId());
        return value;
    }

    @Test void snapshotIntentRecoversMissedCompletionAndEnqueueIsIdempotent() {
        var eligible = create(true, ReportStatus.GENERATED);
        var legacy = create(false, ReportStatus.GENERATED);
        var pending = create(true, ReportStatus.PENDING);
        var missing = repository.missing();
        assertTrue(missing.contains(eligible.getId()));
        assertFalse(missing.contains(legacy.getId()));
        assertFalse(missing.contains(pending.getId()));
        persistence.enqueue(eligible.getId());
        persistence.enqueue(eligible.getId());
        assertEquals(1, repository.find(eligible.getId()).size());
        assertFalse(repository.missing().contains(eligible.getId()));
        assertEquals("PENDING", service.get(eligible.getId()).companies().getFirst().status());
        assertTrue(service.get(legacy.getId()).companies().isEmpty());
        verifyNoInteractions(financials);
    }

    @Test void largeFinancialSnapshotIsImmutableWhenSharedCacheRefreshesOrUserRetries() {
        var report = create(true, ReportStatus.GENERATED);
        persistence.enqueue(report.getId());
        var job = new ReportDartRepository.Job(report.getId(), COMPANY, 0);
        assertTrue(persistence.claim(job, "first"));
        assertTrue(persistence.cache(COMPANY.corpCode(), "first").owner());
        var original = new FinancialData(data().fsDiv(), data().currency(), data().fetchedAt(), data().quarters(),
                data().disclosures(), List.of("근거 주석을 정확히 보존합니다. ".repeat(4000)));
        persistence.complete(job, "first", original, true);
        assertEquals(original, repository.find(report.getId()).getFirst().data());
        assertEquals("READY", repository.find(report.getId()).getFirst().status());
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        assertTrue(repository.claimCache(COMPANY.corpCode(), "correction", now, now.plusMinutes(30)));
        assertTrue(repository.saveCache(COMPANY.corpCode(), "correction", data(), now));
        persistence.retry(report.getId());
        persistence.complete(job, "first", data(), false);
        assertEquals(original, service.get(report.getId()).companies().getFirst().data());
        assertEquals(data(), persistence.cache(COMPANY.corpCode(), "other-report").data());
        verifyNoInteractions(financials);
    }

    @Test void concurrentReportAndCompanyClaimsHaveOnlyOneOwner() throws Exception {
        var report = create(true, ReportStatus.GENERATED);
        persistence.enqueue(report.getId());
        var job = new ReportDartRepository.Job(report.getId(), COMPANY, 0);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return persistence.claim(job, "one"); });
            var second = pool.submit(() -> { start.await(); return persistence.claim(job, "two"); });
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            var cacheStart = new CountDownLatch(1);
            var firstCache = pool.submit(() -> { cacheStart.await(); return persistence.cache(COMPANY.corpCode(), "cache-one").owner(); });
            var secondCache = pool.submit(() -> { cacheStart.await(); return persistence.cache(COMPANY.corpCode(), "cache-two").owner(); });
            cacheStart.countDown();
            assertNotEquals(firstCache.get(10, TimeUnit.SECONDS), secondCache.get(10, TimeUnit.SECONDS));
        }
        assertEquals("PENDING", service.get(report.getId()).companies().getFirst().status());
        verifyNoInteractions(financials);
    }

    @Test void interruptedWorkIsBoundedAndLateOwnerCannotOverwriteRecoveredAttempt() {
        var report = create(true, ReportStatus.GENERATED);
        persistence.enqueue(report.getId());
        var job = new ReportDartRepository.Job(report.getId(), COMPANY, 0);
        assertTrue(persistence.claim(job, "expired"));
        var now = LocalDateTime.now(ApiTimeZone.ZONE);
        repository.recover(now.plusMinutes(31), true);
        assertEquals("PENDING", repository.find(report.getId()).getFirst().status());
        persistence.complete(job, "expired", data(), false);
        assertNull(repository.find(report.getId()).getFirst().data());
        jdbc.update("UPDATE news_report_dart_attachments SET status = 'RUNNING', attempts = 2, lease_token = 'last', lease_expires_at = ? WHERE report_id = ?",
                java.sql.Timestamp.valueOf(now.minusMinutes(1)), report.getId());
        persistence.recover();
        assertEquals("FAILED", repository.find(report.getId()).getFirst().status());
        persistence.retry(report.getId());
        persistence.retry(report.getId());
        assertEquals("PENDING", repository.find(report.getId()).getFirst().status());
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM news_report_dart_attachments WHERE report_id = ?", Integer.class, report.getId()));
    }

    @Test void absentKeyDisablesPendingWorkAndConfiguredKeyRecoversIt() {
        var report = create(true, ReportStatus.GENERATED);
        persistence.enqueue(report.getId());
        repository.recover(LocalDateTime.now(ApiTimeZone.ZONE), false);
        assertEquals("DISABLED", repository.find(report.getId()).getFirst().status());
        persistence.recover();
        assertEquals("PENDING", repository.find(report.getId()).getFirst().status());
    }
}
