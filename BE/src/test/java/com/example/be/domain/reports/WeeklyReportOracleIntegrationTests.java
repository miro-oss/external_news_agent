package com.example.be.domain.reports;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.ArticleBodyStorage;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.repository.WeeklyReportJdbcRepository;
import com.example.be.domain.reports.service.*;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class WeeklyReportOracleIntegrationTests {
    @Autowired private NewsReportRepository reports;
    @Autowired private CollectionRunRepository runs;
    @Autowired private WeeklyReportJdbcRepository readiness;
    @Autowired private WeeklyReportPersistenceService reservation;
    @Autowired private ReportPersistenceService persistence;
    @Autowired private WeeklyReportGenerator generator;
    @Autowired private ReportQueryService queries;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FindingRepository findings;
    @Autowired private SourceRepository sources;
    @Autowired private TopicRepository topics;
    @Autowired private ArticleRepository articles;
    @Autowired private ArticleBodyStorage bodyStorage;

    @Test
    void reservationPersistsImmutableDailyInputsAndQueriesWeeklySavedEvidenceCounts() {
        LocalDate monday = LocalDate.of(1996, 1, 1);
        LocalDateTime now = monday.plusWeeks(1).atTime(0, 5);
        Finding evidence = evidence(monday.atTime(8, 0));
        NewsReport first = daily(monday, ReportStatus.GENERATED, evidence);
        NewsReport last = daily(monday.plusDays(6), ReportStatus.FALLBACK, evidence);
        NewsReport hidden = daily(monday.plusDays(2), ReportStatus.GENERATED, null);
        hidden.hide(now);
        entities.flush();

        var owner = reservation.reserve(monday, now);
        assertTrue(owner.owner());
        assertEquals(List.of(first.getId(), last.getId()), owner.input().sources().stream()
                .map(WeeklyReportInput.DailySource::reportId).toList());
        assertEquals(5, owner.input().missingReportDates().size());
        assertFalse(reservation.reserve(monday, now.plusMinutes(1)).owner());
        persistence.complete(owner.reportId(), generator.generate(owner.input()), now);
        entities.flush();
        entities.clear();

        NewsReport stored = reports.findById(owner.reportId()).orElseThrow();
        assertEquals(owner.input(), stored.getWeeklyInput());
        assertEquals(ReportScope.WEEKLY, stored.getReportScope());
        assertEquals(monday.plusDays(6), stored.getReportEndDate());
        assertEquals(List.of(evidence.getRun().getId()), stored.getSourceRunIds());
        var detail = queries.getReport(owner.reportId(), false);
        assertEquals(2, detail.getSourceReportCount());
        assertEquals(1, detail.getSummaryStats().getFindingCount());
        assertEquals(List.of(first.getId(), last.getId()), detail.getSourceReportIds());
        assertEquals(monday.plusDays(6), detail.getReportEndDate());
        var list = queries.getReports(now.toLocalDate().toString(), now.toLocalDate().toString(), 0, 20, ReportScope.WEEKLY);
        assertEquals(1, list.getTotalElements());
        assertEquals(1, list.getContent().getFirst().getFindingCount());
        assertEquals(owner.reportId(), queries.getLatest(false, ReportScope.WEEKLY).getId());

        // Hiding an original after completion must not change the reservation-time weekly sources.
        reports.findById(first.getId()).orElseThrow().hide(now.plusHours(1));
        entities.flush();
        entities.clear();
        assertEquals(owner.input(), reports.findById(owner.reportId()).orElseThrow().getWeeklyInput());
        assertEquals(2, queries.getReport(owner.reportId(), false).getSourceReportCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM news_report_comparisons WHERE report_id = ?", Integer.class, owner.reportId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_batches WHERE report_id = ?", Integer.class, owner.reportId()));
    }

    @Test
    void readinessWaitsForRunsAndPendingDailiesButRecordsUnrecoverableCalendarGaps() {
        LocalDate monday = LocalDate.of(1996, 2, 5);
        LocalDate today = monday.plusWeeks(1);
        CollectionRun running = run(monday.plusDays(6).atTime(23, 59), RunStatus.RUNNING);
        entities.flush();
        assertTrue(readiness.hasUnfinishedInputs(monday, today));
        jdbc.update("UPDATE news_collection_runs SET status = 'SUCCESS' WHERE id = ?", running.getId());
        entities.clear();
        // Missing DAILY is still eligible for normal daily backfill, so weekly waits.
        assertTrue(readiness.hasUnfinishedInputs(monday, today));
        NewsReport pending = daily(monday.plusDays(6), ReportStatus.PENDING, null);
        entities.flush();
        assertTrue(readiness.hasUnfinishedInputs(monday, today.plusDays(20)));
        jdbc.update("UPDATE news_reports SET report_status = 'FALLBACK' WHERE id = ?", pending.getId());
        entities.clear();
        assertFalse(readiness.hasUnfinishedInputs(monday, today));

        run(monday.atTime(8, 0), RunStatus.SUCCESS);
        entities.flush();
        assertTrue(readiness.hasUnfinishedInputs(monday, today));
        assertFalse(readiness.hasUnfinishedInputs(monday, today.plusDays(1)));
        var created = reservation.reserve(monday, today.plusDays(1).atTime(0, 5));
        assertTrue(created.owner());
        assertTrue(created.input().missingReportDates().contains(monday));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentWeeklyReservationsProduceOnlyOneOwner() throws Exception {
        LocalDate monday = LocalDate.of(1996, 4, 1);
        NewsReport source = daily(monday, ReportStatus.FALLBACK, null);
        Long weeklyId = null;
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<WeeklyReportPersistenceService.Reservation> action = () -> {
                start.await();
                return reservation.reserve(monday, monday.plusWeeks(1).atTime(0, 5));
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            var one = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            weeklyId = one.reportId();
            var two = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(one.reportId(), two.reportId());
            assertNotEquals(one.owner(), two.owner());
        } finally {
            if (weeklyId != null) reports.deleteById(weeklyId);
            reports.deleteById(source.getId());
        }
    }

    @Test
    void oracleConstraintRejectsNonMondayOrNonSevenDayWeeklyPeriod() {
        LocalDate monday = LocalDate.of(1996, 3, 4);
        daily(monday, ReportStatus.FALLBACK, null);
        entities.flush();
        var created = reservation.reserve(monday, monday.plusWeeks(1).atTime(0, 5));
        entities.flush();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE news_reports SET report_date = report_date + 1 WHERE id = ?", created.reportId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE news_reports SET report_end_date = report_end_date + 1 WHERE id = ?", created.reportId()));
    }

    private CollectionRun run(LocalDateTime time, RunStatus status) {
        return runs.save(CollectionRun.builder().status(status).triggerType(TriggerType.MANUAL).startedAt(time).build());
    }

    private NewsReport daily(LocalDate date, ReportStatus status, Finding finding) {
        List<Long> ids = finding == null ? List.of() : List.of(finding.getId());
        ReportContent content = new ReportContent(List.of("당시에 저장한 확정 발표"), finding == null ? List.of()
                : List.of(new ReportContent.ImportantEvent("설비 증설", "당시에 저장한 확정 발표", "공급 확대", ids)),
                List.of(), List.of("일일 보고서 출처"));
        return reports.saveAndFlush(NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(date)
                .sourceRunIds(finding == null ? List.of() : List.of(finding.getRun().getId()))
                .title(date + " 일일 통합 뉴스 보고서").markdownBody("원본 일일 본문")
                .structuredContent(content).reflectedFindingIds(ids).coverageRecorded(true)
                .reportStatus(status).modelName("daily-model").generatedAt(date.plusDays(1).atStartOfDay()).build());
    }

    private Finding evidence(LocalDateTime now) {
        String suffix = UUID.randomUUID().toString();
        Source source = sources.save(Source.builder().sourceKind(Source.KIND_FEED).name("weekly " + suffix)
                .urlTemplate("https://example.com/" + suffix).language("ko").active(true).build());
        Topic topic = topics.save(Topic.builder().name("weekly " + suffix).batchSize(10).intervalMinutes(60).active(true).build());
        CollectionRun run = run(now, RunStatus.SUCCESS);
        Article article = articles.save(Article.builder().topic(topic).source(source)
                .urlHash(suffix.replace("-", "").repeat(2)).canonicalUrl("https://example.com/articles/" + suffix)
                .title("설비 증설 발표").summary("요약").storedBody(bodyStorage.intern("기업은 설비 증설을 발표했다."))
                .language("ko").fetchStatus(FetchStatus.FULLTEXT).firstSeenRun(run).lastSeenRun(run).collectedAt(now).build());
        return findings.saveAndFlush(Finding.builder().run(run).article(article).changeType(ChangeType.NEW)
                .summary("저장 시점 뒤 바뀔 수 있는 현재 분석")
                .keyPoints(List.of(new FindingKeyPoint("기업은 설비 증설을 발표했다.", List.of(0), "grounded")))
                .sections(List.of(new FindingSection(0, "기업은 설비 증설을 발표했다.")))
                .sentiment(Sentiment.NEUTRAL).sensitivity(FindingSensitivity.legacy(SensitivityLevel.HIGH))
                .relevance(Relevance.IMPORTANT).category(FindingCategory.COMPANY).analysisSource(AnalysisSource.LLM).analyzedAt(now).build());
    }
}
