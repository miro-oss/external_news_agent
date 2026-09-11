package com.example.be.domain.reports.comparison;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.example.be.domain.reports.comparison.ComparisonFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportComparisonOracleIntegrationTests {
    @Autowired NewsReportRepository reports;
    @Autowired ReportComparisonRepository comparisons;
    @Autowired ReportComparisonPersistence persistence;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private final List<Long> created = new ArrayList<>();
    private final LocalDate date = LocalDate.of(2196, 1, 10);

    @AfterEach void removeOwnedFixtures() {
        for (Long id : created.reversed()) jdbc.update("DELETE FROM news_report_comparisons WHERE report_id = ?", id);
        for (Long id : created.reversed()) jdbc.update("DELETE FROM news_report_comparison_inputs WHERE report_id = ?", id);
        for (Long id : created.reversed()) jdbc.update("DELETE FROM news_reports WHERE id = ?", id);
    }

    private NewsReport report(LocalDate reportDate, ReportStatus status, ReportComparisonSnapshot input) {
        return new TransactionTemplate(transactionManager).execute(transaction -> {
            var value = reports.saveAndFlush(NewsReport.builder().reportScope(ReportScope.DAILY)
                    .reportDate(reportDate).reportStatus(status).title("비교 통합 검증 보고서")
                    .markdownBody("검증 자료").modelName("comparison-test").generatedAt(reportDate.plusDays(1).atStartOfDay())
                    .reflectedFindingIds(input.issues().stream().map(ReportComparisonSnapshot.Issue::findingId).toList())
                    .collectionContexts(input.scopes()).coverageRecorded(true).build());
            comparisons.captureInput(value, input, LocalDateTime.now());
            created.add(value.getId());
            return value;
        });
    }

    @Test void immutableClobAndHashRoundTripAndRecoveredReportInputBecomesUnavailable() {
        String originalText = "실제 보존할 긴 원문 문장 ".repeat(4000);
        var original = snapshot(side(1, 10, originalText));
        var report = report(date, ReportStatus.GENERATED, original);
        assertEquals(originalText, comparisons.findInput(report.getId()).orElseThrow()
                .issues().getFirst().side().claims().getFirst().evidence().getFirst().text());
        String hash = jdbc.queryForObject("SELECT input_hash FROM news_report_comparison_inputs WHERE report_id = ?", String.class, report.getId());
        assertEquals(64, hash.length());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> comparisons.captureInput(report, snapshot(side(1, 10, "바뀐 내용")), LocalDateTime.now()));
        assertEquals(originalText, comparisons.findInput(report.getId()).orElseThrow().issues().getFirst().side().summary());
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                reports.findByIdForUpdate(report.getId()).orElseThrow().invalidateComparisonInput());
        assertTrue(comparisons.findInput(report.getId()).isEmpty());
    }

    @Test void baselineUsesVisibleCompletedPriorDateAndNeverMovesAfterEnqueue() {
        var baseline = report(date.minusDays(3), ReportStatus.GENERATED, snapshot(side(1, 10, "과거")));
        var hidden = report(date.minusDays(2), ReportStatus.GENERATED, snapshot(side(1, 20, "숨긴 과거")));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                reports.findByIdForUpdate(hidden.getId()).orElseThrow().hide(LocalDateTime.now()));
        var pending = report(date.minusDays(1), ReportStatus.PENDING, snapshot(side(1, 30, "생성 중")));
        var current = report(date, ReportStatus.GENERATED, snapshot(side(1, 40, "현재")));
        assertTrue(comparisons.missingJobs().contains(current.getId()));
        persistence.enqueue(current.getId());
        var job = comparisons.find(current.getId()).orElseThrow();
        assertEquals(baseline.getId(), job.result().baseReportId());
        assertEquals(date.minusDays(3), job.work().baseReportDate());
        jdbc.update("UPDATE news_reports SET report_status = 'FALLBACK' WHERE id = ?", pending.getId());
        persistence.enqueue(current.getId());
        assertEquals(baseline.getId(), comparisons.find(current.getId()).orElseThrow().result().baseReportId());
        assertEquals(64, jdbc.queryForObject("SELECT input_hash FROM news_report_comparisons WHERE report_id = ?", String.class, current.getId()).length());
    }

    @Test void concurrentClaimsHaveOneOwnerAndInterruptedCallsAreNeverRepeatedOrPublishedLate() throws Exception {
        report(date.minusDays(1), ReportStatus.GENERATED, snapshot(side(1, 10, "과거")));
        var current = report(date, ReportStatus.GENERATED, snapshot(side(1, 20, "현재")));
        persistence.enqueue(current.getId());
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { latch.await(); return comparisons.claim(current.getId(), LocalDateTime.now()); });
            var second = pool.submit(() -> { latch.await(); return comparisons.claim(current.getId(), LocalDateTime.now()); });
            latch.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        var claimed = comparisons.find(current.getId()).orElseThrow();
        assertEquals(ReportChanges.Status.RUNNING, claimed.status());
        comparisons.expireRunning(LocalDateTime.now().plusMinutes(1), LocalDateTime.now());
        assertFalse(comparisons.claim(current.getId(), LocalDateTime.now()));
        comparisons.finish(current.getId(), new ReportComparisonEngine().prepare(claimed.work()).result(), LocalDateTime.now());
        assertEquals(ReportChanges.Status.FAILED, comparisons.find(current.getId()).orElseThrow().status());
    }

    @Test void hidingBaselineDuringProviderCallPreventsQuotePublication() {
        var baseline = report(date.minusDays(1), ReportStatus.GENERATED, snapshot(side(1, 10, "숨겨질 과거")));
        var current = report(date, ReportStatus.GENERATED, snapshot(side(1, 20, "현재")));
        persistence.enqueue(current.getId());
        var claimed = persistence.claim(current.getId()).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                reports.findByIdForUpdate(baseline.getId()).orElseThrow().hide(LocalDateTime.now()));
        persistence.finish(current.getId(), new ReportComparisonEngine().prepare(claimed.work()).result());
        var result = comparisons.find(current.getId()).orElseThrow();
        assertEquals(ReportChanges.Status.UNAVAILABLE, result.status());
        assertTrue(result.result().items().isEmpty());
    }
}
