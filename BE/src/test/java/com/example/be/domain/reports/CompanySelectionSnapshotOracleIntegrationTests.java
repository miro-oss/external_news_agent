package com.example.be.domain.reports;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.FindingSensitivity;
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
import com.example.be.domain.collection.service.command.ArticleBodyStorage;
import com.example.be.domain.companyinterest.CompanyInterestModels.ActiveCriterion;
import com.example.be.domain.companyinterest.CompanyInterestStore;
import com.example.be.domain.feedback.model.FeedbackModels;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportDocument;
import com.example.be.domain.reports.service.ReportFindings;
import com.example.be.domain.reports.service.ReportPersistenceService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"news.agent.enabled=false", "news.scheduling.enabled=false",
        "news.notifications.automation-enabled=false", "news.feedback.enabled=false"})
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CompanySelectionSnapshotOracleIntegrationTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2091, 1, 8, 10, 0);
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArticleBodyStorage bodies;
    @Autowired NewsReportRepository reports;
    @Autowired FindingRepository findings;
    @Autowired ReportPersistenceService persistence;
    @Autowired FeedbackStore feedback;
    @Autowired CompanyInterestStore interests;

    @Test
    void completionAndRecoveryPersistSuppressionSeparatelyFromGeneratorCoverage() {
        Fixture fixture = fixture(ReportStatus.PENDING);
        NewsReport report = fixture.report();
        List<Long> ids = fixture.findingIds();
        List<Long> selected = List.of(ids.get(0), ids.get(1), ids.get(3));
        persistence.freezeCompanySelection(report.getId(), selected, List.of(ids.get(2)), null, List.of(), NOW);
        em.flush();
        em.clear();
        assertEquals(List.of(ids.get(2)), reports.findById(report.getId()).orElseThrow().getCompanySuppressedFindingIds());

        persistence.replace(report.getId(), document(List.of(ids.get(0)), List.of(ids.get(1))), NOW.plusMinutes(1));
        em.flush();
        em.clear();
        NewsReport completed = reports.findById(report.getId()).orElseThrow();
        assertEquals(selected, visibleIds(completed), "the uncovered STUB finding stays visible after completion");
        assertEquals(List.of(ids.get(0)), completed.getReflectedFindingIds());
        assertEquals(List.of(ids.get(1)), completed.getExcludedFindingIds());

        persistence.replace(report.getId(), document(List.of(), List.of()), NOW.plusMinutes(2));
        em.flush();
        em.clear();
        NewsReport recovered = reports.findById(report.getId()).orElseThrow();
        assertEquals(selected, visibleIds(recovered), "narrower recovery coverage must not shrink visibility");
        assertEquals(List.of(ids.get(2)), recovered.getCompanySuppressedFindingIds());
        assertTrue(recovered.getReflectedFindingIds().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"-1,GENERATED", "0,GENERATED", "1,GENERATED", "-1,PENDING", "0,PENDING", "1,PENDING"})
    void v66RestoresZeroSuppressionButPreservesPreviousVisibilityWhenOriginalSelectionWasLost(
            int suppressedCount, ReportStatus status) throws Exception {
        Fixture fixture = fixture(status);
        NewsReport report = fixture.report();
        List<Long> ids = fixture.findingIds();
        List<Long> reflected = status == ReportStatus.PENDING
                ? List.of(ids.get(0), ids.get(1), ids.get(3)) : List.of(ids.get(0));
        List<Long> excluded = status == ReportStatus.PENDING ? List.of() : List.of(ids.get(1));
        jdbc.update("""
                UPDATE news_reports SET company_selection_recorded_yn='Y',coverage_recorded_yn='Y',
                    report_reflected_finding_ids=?,report_excluded_finding_ids=? WHERE id=?
                """, reflected.toString(), excluded.toString(), report.getId());
        if (suppressedCount >= 0) application(report.getId(), fixture.topicId(), suppressedCount);
        String migration = new ClassPathResource("db/migration/V66__run_company_suppression_snapshot.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String backfill = migration.substring(migration.indexOf("UPDATE news_reports report")).strip().replaceFirst(";\\s*$", "");
        // Execute the actual migration backfill against this fixture only; all changes roll back.
        jdbc.update(backfill + " AND report.id=?", report.getId());
        em.clear();

        NewsReport migrated = reports.findById(report.getId()).orElseThrow();
        List<Long> previousVisible = status == ReportStatus.PENDING ? reflected : List.of(ids.get(0), ids.get(1));
        List<Long> expectedSuppressed = suppressedCount > 0
                ? ids.stream().filter(id -> !previousVisible.contains(id)).toList() : List.of();
        assertEquals(expectedSuppressed, migrated.getCompanySuppressedFindingIds());
        assertEquals(suppressedCount > 0 ? previousVisible : ids, visibleIds(migrated));
        assertEquals(reflected, migrated.getReflectedFindingIds());
        assertEquals(excluded, migrated.getExcludedFindingIds());
    }

    private void application(long reportId, long topicId, int suppressedCount) {
        String key = "a".repeat(64);
        var event = new FeedbackModels.EventContext(key, 0, "검토한 사건", "요약", null, List.of(), List.of(), List.of(), null);
        var item = new FeedbackModels.Item(null, null, null, List.of(), null, null, null, null, null, null, null, event);
        long feedbackId = feedback.submitEvent(reportId, item, FeedbackModels.Category.PREFERENCE,
                "회사 기준", UUID.randomUUID().toString(), key, NOW);
        jdbc.update("""
                INSERT INTO news_company_interests(feedback_id,topic_id,instruction,reason,created_at,updated_at)
                VALUES(?,?,'회사 기준','검토 이유',?,?)
                """, feedbackId, topicId, NOW, NOW);
        long criterionId = jdbc.queryForObject("SELECT id FROM news_company_interests WHERE feedback_id=?", Long.class, feedbackId);
        interests.recordApplication(reportId, new ActiveCriterion(criterionId, topicId, "주제", "회사 기준", 1),
                4, suppressedCount, NOW);
    }

    private Fixture fixture(ReportStatus status) {
        String suffix = UUID.randomUUID().toString();
        Source source = Source.builder().sourceKind(Source.KIND_FEED).name("선정 source " + suffix)
                .urlTemplate("https://example.com/" + suffix + ".xml").language("ko").active(true).build();
        Topic topic = Topic.builder().name("선정 topic " + suffix).batchSize(10).intervalMinutes(60).active(true).build();
        CollectionRun run = CollectionRun.builder().status(RunStatus.SUCCESS).triggerType(TriggerType.MANUAL).startedAt(NOW).build();
        em.persist(source);
        em.persist(topic);
        em.persist(run);
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
            Article article = Article.builder().topic(topic).source(source).urlHash(hash)
                    .canonicalUrl("https://example.com/" + suffix + "/" + index).title("선정 기사 " + index)
                    .summary("수집 요약").storedBody(bodies.intern("Oracle 선정 근거 " + suffix + " " + index))
                    .language("ko").fetchStatus(FetchStatus.FULLTEXT).firstSeenRun(run).lastSeenRun(run).collectedAt(NOW).build();
            em.persist(article);
            Finding finding = Finding.builder().run(run).article(article).changeType(ChangeType.NEW).summary("선정 근거 " + index)
                    .keyPoints(List.of(new FindingKeyPoint("확인한 근거", List.of(0), "grounded")))
                    .sentiment(Sentiment.NEUTRAL).sensitivity(FindingSensitivity.legacy(SensitivityLevel.LOW))
                    .relevance(Relevance.IMPORTANT).category("정책").analysisSource(index == 3 ? AnalysisSource.STUB : AnalysisSource.LLM)
                    .sections(List.of(new FindingSection(0, "확인한 근거"))).analyzedAt(NOW).build();
            em.persist(finding);
            ids.add(finding.getId());
        }
        NewsReport report = NewsReport.builder().run(run).title("선정 보고서").markdownBody("원래 본문")
                .modelName("test-model").reportStatus(status).generatedAt(NOW).build();
        em.persist(report);
        em.flush();
        return new Fixture(report, List.copyOf(ids), topic.getId());
    }

    private List<Long> visibleIds(NewsReport report) {
        return ReportFindings.load(report, findings).stream().map(Finding::getId).toList();
    }

    private ReportDocument document(List<Long> reflected, List<Long> excluded) {
        return new ReportDocument("완성 보고서", "저장 본문", "test-model", null, null, null, null, null, null,
                ReportStatus.FALLBACK, reflected, excluded);
    }

    private record Fixture(NewsReport report, List<Long> findingIds, long topicId) { }
}
