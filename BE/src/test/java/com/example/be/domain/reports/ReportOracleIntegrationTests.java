package com.example.be.domain.reports;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportCreationService;
import com.example.be.domain.reports.service.ReportQueryService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.apiPayload.PageResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportOracleIntegrationTests {

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private FindingRepository findingRepository;

    @Autowired
    private NewsReportRepository reportRepository;

    @Autowired
    private ReportCreationService creationService;

    @Autowired
    private ReportQueryService queryService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private com.example.be.domain.reports.repository.DailyReportJdbcRepository dailyRepository;
    @Autowired
    private com.example.be.domain.reports.service.DailyReportPersistenceService dailyPersistence;
    @Autowired
    private com.example.be.domain.reports.service.ReportPersistenceService persistence;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void dailyCalendarBoundaryDefersRunningRunAndQueriesWithoutRunJoin() {
        java.time.LocalDate date = java.time.LocalDate.of(1998, 2, 3);
        CollectionRun first = runRepository.save(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).startedAt(date.atStartOfDay()).build());
        CollectionRun last = runRepository.save(CollectionRun.builder().status(RunStatus.RUNNING)
                .triggerType(TriggerType.SCHEDULED).startedAt(date.atTime(23, 59)).build());
        runRepository.save(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).startedAt(date.plusDays(1).atStartOfDay()).build());
        entityManager.flush();
        assertTrue(dailyRepository.findDueDates(date, date.plusDays(1)).isEmpty());
        assertTrue(dailyRepository.findBlockedDates(date.plusDays(10).atStartOfDay()).stream()
                .anyMatch(blocked -> blocked.date().equals(date) && blocked.pendingRunCount() == 1));
        jdbcTemplate.update("UPDATE news_collection_runs SET status = 'PARTIAL' WHERE id = ?", last.getId());
        assertEquals(List.of(date), dailyRepository.findDueDates(date, date.plusDays(1)));
        assertEquals(List.of(first.getId(), last.getId()), dailyRepository.findSourceRunIds(date));
        assertEquals(0, dailyRepository.sourceStats(date).collected());

        var reserved = dailyPersistence.reserve(date, List.of(first.getId(), last.getId()), List.of(),
                date.plusDays(1).atTime(0, 5));
        assertTrue(reserved.owner());
        assertEquals(reserved.reportId(), dailyPersistence.reserve(date, List.of(), List.of(),
                date.plusDays(1).atTime(0, 6)).reportId());
        persistence.complete(reserved.reportId(),
                new com.example.be.domain.reports.service.ReportDocument("일일 보고서", "근거 없음", "fallback"),
                date.plusDays(1).atTime(0, 7));
        entityManager.flush();
        entityManager.clear();
        var detail = queryService.getReport(reserved.reportId(), false);
        assertNull(detail.getRunId());
        assertEquals(com.example.be.domain.reports.entity.ReportScope.DAILY, detail.getReportScope());
        assertEquals(date, detail.getReportDate());
        assertEquals(List.of(first.getId(), last.getId()), detail.getSourceRunIds());
        assertEquals(0, detail.getSummaryStats().getFindingCount());
        var page = queryService.getReports(date.plusDays(1).toString(), date.plusDays(1).toString(), 0, 20,
                com.example.be.domain.reports.entity.ReportScope.DAILY);
        assertEquals(List.of(reserved.reportId()), page.getContent().stream().map(ReportResDTO.Summary::getId).toList());
        assertTrue(dailyRepository.findDueDates(date, date.plusDays(1)).isEmpty());
        assertTrue(dailyRepository.findBlockedDates(date.plusDays(10).atStartOfDay()).stream()
                .noneMatch(blocked -> blocked.date().equals(date)));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentDailyReservationsHaveOnlyOneOwner() throws Exception {
        java.time.LocalDate date = java.time.LocalDate.of(1998, 2, 4);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<com.example.be.domain.reports.service.ReportPersistenceService.Reservation> action = () -> {
                start.await();
                return dailyPersistence.reserve(date, List.of(), List.of(), LocalDateTime.now(ApiTimeZone.ZONE));
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertEquals(a.reportId(), b.reportId());
                assertTrue(a.owner() != b.owner());
            } finally {
                reportRepository.deleteById(a.reportId());
            }
        }
    }

    @Test
    void persistsOneClobReportLinksRunAndReadsNotionContract() {
        long reportCountBefore = reportRepository.count();
        String suffix = Long.toString(System.nanoTime());
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Source source = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("M5 Oracle source " + suffix)
                .urlTemplate("https://example.com/m5-" + suffix + ".xml")
                .language("ko")
                .active(true)
                .build());
        Topic topic = topicRepository.save(Topic.builder()
                .name("M5 Oracle topic " + suffix)
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());
        CollectionRun run = runRepository.save(CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .forceRefresh(false)
                .startedAt(now)
                .scannedCount(0)
                .newCount(0)
                .updatedCount(0)
                .skippedCount(0)
                .build());
        Article article = articleRepository.save(Article.builder()
                .topic(topic)
                .source(source)
                .urlHash(String.format("%064d", Math.abs(System.nanoTime() % 1_000_000_000L)))
                .canonicalUrl("https://example.com/articles/" + suffix)
                .title("Oracle에서 검증하는 M5 기사")
                .summary("수집 요약")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(now)
                .build());
        Finding finding = findingRepository.save(Finding.builder()
                .run(run)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary("Oracle CLOB 보고서에 들어갈 한국어 요약이다.")
                .keyPoints(List.of(new FindingKeyPoint("핵심 근거", List.of(0), "grounded")))
                .intent("통합 검증")
                .sentiment(Sentiment.NEUTRAL)
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.HIGH))
                .relevance(Relevance.IMPORTANT)
                .category("정책")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "Oracle 통합 테스트 본문")))
                .analyzedAt(now)
                .build());
        entityManager.flush();

        Long firstId = creationService.generate(run.getId());
        Long secondId = creationService.generate(run.getId());
        entityManager.flush();
        entityManager.clear();

        assertEquals(firstId, secondId);
        assertEquals(reportCountBefore + 1, reportRepository.count());
        assertEquals(firstId, runRepository.findById(run.getId()).orElseThrow().getReportId());
        NewsReport report = reportRepository.findById(firstId).orElseThrow();
        assertEquals(ReportStatus.FALLBACK, report.getReportStatus());
        assertTrue(report.isCoverageRecorded());
        assertEquals(List.of(finding.getId()), report.getReflectedFindingIds());
        assertEquals(List.of(), report.getExcludedFindingIds());

        ReportResDTO.Detail detail = queryService.getReport(firstId, true);
        assertEquals(run.getId(), detail.getRunId());
        assertTrue(detail.getMarkdownBody().contains("Oracle CLOB 보고서에 들어갈 한국어 요약"));
        assertEquals(1, detail.getSummaryStats().getFindingCount());
        assertEquals(1, detail.getSummaryStats().getBySensitivityLevel().get("high"));
        assertNotNull(detail.getFindings());
        assertEquals(article.getId(), detail.getFindings().getFirst().getArticleId());

        PageResponse<ReportResDTO.Summary> reports = queryService.getReports(null, null, 0, 100);
        ReportResDTO.Summary summary = reports.getContent().stream()
                .filter(candidate -> firstId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, summary.getFindingCount());
        assertEquals(1, summary.getHighSensitivityCount());
        assertEquals("NOT_SENT", summary.getDeliveryStatus());

        ReportResDTO.Detail withoutFindings = queryService.getReport(firstId, false);
        assertNull(withoutFindings.getFindings());
        assertEquals(1, withoutFindings.getSummaryStats().getFindingCount());
        assertEquals(1, withoutFindings.getSummaryStats().getBySensitivityLevel().get("high"));

        // 별도 날짜로 묶어 실제 수집 데이터와 독립적으로 DAILY 집계 SQL을 검증한다.
        var date = java.time.LocalDate.of(1998, 2, 5);
        jdbcTemplate.update("UPDATE news_collection_runs SET started_at = ?, scanned_count = 99 WHERE id = ?",
                java.sql.Timestamp.valueOf(date.atTime(1, 0)), run.getId());
        entityManager.persist(com.example.be.domain.collection.entity.CollectionRunItem.builder()
                .run(entityManager.getReference(CollectionRun.class, run.getId())).topic(topic).source(source)
                .status(com.example.be.domain.collection.entity.RunItemStatus.SUCCESS).scannedCount(40).build());
        CollectionRun other = runRepository.save(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.MANUAL).startedAt(date.atTime(2, 0)).scannedCount(7).build());
        entityManager.flush();
        assertEquals(47, dailyRepository.sourceStats(date).collected());

        var daily = dailyPersistence.reserve(date, List.of(run.getId(), other.getId()), List.of(finding.getId()), now);
        persistence.complete(daily.reportId(), new com.example.be.domain.reports.service.ReportDocument(
                "일일 보고서", "본문", "fallback", null, null, null, null, null, null,
                ReportStatus.FALLBACK, List.of(finding.getId()), List.of()), now);
        entityManager.flush();
        var count = findingRepository.countForDailyReports(List.of(daily.reportId()), java.math.BigDecimal.valueOf(70))
                .getFirst();
        assertEquals(daily.reportId(), count.getReportId());
        assertEquals(1, count.getFindingCount());
        assertEquals(1, count.getHighSensitivityCount());

        var investigations = new com.example.be.domain.analysis.agent.investigation.IssueInvestigationJdbcRepository(jdbcTemplate);
        // 멀티 run 조회의 빈 결과도 유효하며 null run으로 조인하지 않는다.
        assertTrue(investigations.findTraces(List.of(run.getId(), other.getId())).isEmpty());
    }
}
