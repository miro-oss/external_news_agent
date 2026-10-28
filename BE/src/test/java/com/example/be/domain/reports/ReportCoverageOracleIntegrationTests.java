package com.example.be.domain.reports;

import com.example.be.domain.analysis.entity.*;
import com.example.be.domain.collection.entity.*;
import com.example.be.domain.collection.service.command.ArticleBodyStorage;
import com.example.be.domain.issues.entity.*;
import com.example.be.domain.reports.coverage.ReportCoverageDTO;
import com.example.be.domain.reports.entity.*;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.*;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportCoverageOracleIntegrationTests {
    private static final LocalDate MONDAY = LocalDate.of(2090, 1, 2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    private static final LocalDateTime NOW = MONDAY.plusWeeks(1).atTime(9, 0);
    @Autowired EntityManager em;
    @Autowired ArticleBodyStorage bodies;
    @Autowired NewsReportRepository reports;
    @Autowired ReportPersistenceService persistence;
    @Autowired DailyReportPersistenceService daily;
    @Autowired DailyReportSelector selector;
    @Autowired TopicWeeklyReportPersistenceService topicWeekly;
    @Autowired WeeklyReportPersistenceService weekly;
    @Autowired ReportQueryService queries;

    @Test void runClobSnapshotSurvivesLaterMembershipMovesAndNewArticlesWhileVisibilityMayShrink() {
        Fixture fixture = fixture();
        var reservation = persistence.reserve(fixture.run().getId(), NOW);
        persistence.complete(reservation.reportId(), document(fixture.finding().getId()), NOW);
        em.flush(); em.clear();
        var stored = reports.findById(reservation.reportId()).orElseThrow();
        var original = stored.getReportCoverageSnapshot();
        assertNotNull(original);
        var keywordInput = stored.getReportKeywordSnapshot();
        assertNotNull(keywordInput, "V77 CLOB must persist the separately frozen candidate universe");
        assertNull(keywordInput.unavailableReason());
        assertEquals(ZoneOffset.ofHours(9), keywordInput.capturedAt().getOffset());
        assertEquals(List.of("집계", "확인한"), keywordInput.candidates().getFirst().matchedKeywordKeys());
        assertEquals(fixture.issue().getId(), keywordInput.candidates().getFirst().issueId());
        assertEquals(1, keywordInput.candidates().size(), "an unanalysed member adds coverage but not a candidate issue");
        assertEquals(ZoneOffset.ofHours(9), original.capturedAt().getOffset());
        assertEquals(2, original.observations().size());
        assertEquals(2, queries.getReport(stored.getId(), true).getCoverage().events().getFirst().articleCount());
        assertEquals(List.of(), queries.getReport(stored.getId(), false).getCoverage().findings());
        assertEquals(2, queries.getReport(stored.getId(), false).getCoverage().events().getFirst().articleCount());

        var topic = em.find(Topic.class, fixture.topic().getId());
        var run = em.find(CollectionRun.class, fixture.run().getId());
        var source = em.find(Source.class, fixture.source().getId());
        var oldIssue = em.find(NewsIssue.class, fixture.issue().getId());
        var later = article(topic, source, run, "later", "매체 C");
        membership(later, oldIssue, IssueArticleRole.MEMBER);
        observation(later, topic, source, run);
        var moved = issue(topic, "나중 병합");
        em.find(IssueArticle.class, fixture.representativeLink().getId()).moveToIssue(moved);
        em.flush(); em.clear();
        assertEquals(original, reports.findById(stored.getId()).orElseThrow().getReportCoverageSnapshot());
        assertEquals(2, queries.getReport(stored.getId(), true).getCoverage().events().getFirst().articleCount());
        assertFalse(persistence.reserve(run.getId(), NOW.plusHours(1)).owner());
        em.flush(); em.clear();
        assertEquals(keywordInput, reports.findById(stored.getId()).orElseThrow().getReportKeywordSnapshot(),
                "retry and later membership changes must preserve the original keyword evidence");

        em.find(Article.class, fixture.second().getId()).applyFullText(null, FetchStatus.METADATA_ONLY, NOW);
        em.flush(); em.clear();
        assertEquals(1, queries.getReport(stored.getId(), true).getCoverage().events().getFirst().articleCount());
        assertEquals(2, reports.findById(stored.getId()).orElseThrow().getReportCoverageSnapshot().observations().size());
    }

    @Test void dailyAndWeeklyScopesPersistObservationQueriesWithoutDependingOnCandidateRunIds() {
        Fixture fixture = fixture();
        var prepared = selector.prepare(MONDAY, 10);
        assertEquals(1, prepared.candidates().size());
        var dailyReservation = daily.reserveWithSnapshot(MONDAY, List.of(fixture.run().getId()),
                List.of(fixture.finding().getId()), NOW, null, prepared);
        persistence.complete(dailyReservation.reportId(), document(fixture.finding().getId()), NOW);
        em.flush(); // JDBC weekly readiness must see the completed DAILY inside this rollback-only test transaction.
        var weeklyReservation = weekly.reserve(MONDAY, NOW);
        assertTrue(weeklyReservation.owner());
        persistence.complete(weeklyReservation.reportId(), document(fixture.finding().getId()), NOW);
        // Additional topic observations appear after the global input was fixed; it must not grow.
        CollectionRun laterRun = run(MONDAY.plusDays(3).atTime(10, 0));
        Article later = article(fixture.topic(), fixture.source(), laterRun, "week-only", "매체 C");
        membership(later, fixture.issue(), IssueArticleRole.MEMBER);
        observation(later, fixture.topic(), fixture.source(), laterRun);
        observation(fixture.first(), fixture.topic(), fixture.source(), laterRun);
        em.flush();
        var topicReservation = topicWeekly.reserve(fixture.topic().getId(), MONDAY, NOW);
        persistence.complete(topicReservation.reportId(), document(fixture.finding().getId()), NOW);
        em.flush(); em.clear();

        assertEquals(2, queries.getReport(dailyReservation.reportId(), true).getCoverage().events().getFirst().articleCount());
        var topicStored = reports.findById(topicReservation.reportId()).orElseThrow();
        assertEquals(List.of(fixture.run().getId()), topicStored.getSourceRunIds(), "preserve candidate-based existing contract");
        assertEquals(4, topicStored.getReportCoverageSnapshot().observations().size(), "all topic observations include run without finding");
        assertEquals(3, queries.getReport(topicStored.getId(), true).getCoverage().events().getFirst().articleCount(), "repeat article is a union");
        assertEquals(2, queries.getReport(weeklyReservation.reportId(), true).getCoverage().events().getFirst().articleCount(), "global week copies DAILY scope");
        assertEquals(ReportCoverageDTO.Status.AVAILABLE, queries.getReport(weeklyReservation.reportId(), true).getCoverage().events().getFirst().status());
        for (Long reportId : List.of(dailyReservation.reportId(), weeklyReservation.reportId(), topicReservation.reportId())) {
            var keywords = reports.findById(reportId).orElseThrow().getReportKeywordSnapshot();
            assertNotNull(keywords);
            assertNull(keywords.unavailableReason());
            assertEquals(1, keywords.candidates().size());
            assertEquals(List.of("집계", "확인한"), keywords.candidates().getFirst().matchedKeywordKeys());
            assertEquals(fixture.finding().getId(), keywords.candidates().getFirst().findingId());
        }
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        Source source = Source.builder().sourceKind(Source.KIND_FEED).name("coverage source " + suffix)
                .urlTemplate("https://example.com/" + suffix + ".xml").language("ko").active(true).build();
        Topic topic = Topic.builder().name("coverage topic " + suffix).requiredKeywords(List.of("집계"))
                .optionalKeywords(List.of("확인한")).batchSize(10).intervalMinutes(60).active(true).build();
        em.persist(source); em.persist(topic);
        CollectionRun run = run(MONDAY.atTime(10, 0));
        var item = CollectionRunItem.builder().topic(topic).source(source).status(RunItemStatus.SUCCESS)
                .topicSnapshot(CollectionTopicSnapshot.capture(topic)).build();
        run.addItem(item);
        em.persist(item);
        NewsIssue issue = issue(topic, "집계 이슈");
        Article first = article(topic, source, run, suffix + "/1", "매체 A");
        Article second = article(topic, source, run, suffix + "/2", "매체 B");
        IssueArticle representative = membership(first, issue, IssueArticleRole.REPRESENTATIVE);
        membership(second, issue, IssueArticleRole.MEMBER);
        observation(first, topic, source, run); observation(second, topic, source, run);
        Finding finding = Finding.builder().run(run).article(first).changeType(ChangeType.NEW).summary("확인한 사실")
                .keyPoints(List.of(new FindingKeyPoint("확인한 사실", List.of(0), "grounded", null, "FACT", null)))
                .sentiment(Sentiment.NEUTRAL).sensitivity(FindingSensitivity.legacy(SensitivityLevel.LOW))
                .relevance(Relevance.IMPORTANT).category("정책").analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "확인한 사실"))).analyzedAt(run.getStartedAt()).build();
        em.persist(finding); em.flush();
        return new Fixture(source, topic, run, issue, first, second, finding, representative);
    }
    private CollectionRun run(LocalDateTime started) {
        var run = CollectionRun.builder().status(RunStatus.SUCCESS).triggerType(TriggerType.MANUAL).startedAt(started).build();
        em.persist(run); return run;
    }
    private NewsIssue issue(Topic topic, String title) {
        var issue = NewsIssue.builder().topic(topic).title(title).status(IssueStatus.EMERGING).articleCount(3)
                .publisherCount(3).independentContentCount(3).importanceScore(BigDecimal.ONE)
                .firstSeenAt(NOW.atOffset(ZoneOffset.ofHours(9))).lastSeenAt(NOW.atOffset(ZoneOffset.ofHours(9))).build();
        em.persist(issue); return issue;
    }
    private Article article(Topic topic, Source source, CollectionRun run, String suffix, String publisher) {
        var article = Article.builder().topic(topic).source(source).urlHash(UUID.randomUUID().toString().replace("-", "").repeat(2))
                .canonicalUrl("https://example.com/" + suffix).title("집계 기사 " + suffix).sourceName(publisher)
                .storedBody(bodies.intern("확인한 사실 " + suffix)).fetchStatus(FetchStatus.FULLTEXT).language("ko")
                .firstSeenRun(run).lastSeenRun(run).collectedAt(run.getStartedAt()).build();
        em.persist(article); return article;
    }
    private IssueArticle membership(Article article, NewsIssue issue, IssueArticleRole role) {
        var membership = IssueArticle.builder().article(article).issue(issue).role(role).stance(IssueStance.SUPPORTS)
                .stanceSource(IssueStanceSource.RULE).stanceConfidence(BigDecimal.ONE).joinedAt(NOW).build();
        em.persist(membership); return membership;
    }
    private void observation(Article article, Topic topic, Source source, CollectionRun run) {
        em.persist(CollectionRunArticle.observe(run, article, topic, source, ChangeType.NEW, run.getStartedAt()));
    }
    private ReportDocument document(long findingId) {
        return new ReportDocument("집계 보고서", "저장한 요약", "test", null, null, null, null, null, null,
                ReportStatus.GENERATED, List.of(findingId), List.of(), new ReportContent(List.of("확인한 사실"),
                List.of(new ReportContent.ImportantEvent("사건", "확인한 사실", "", List.of(findingId))), List.of(), List.of()));
    }
    private record Fixture(Source source, Topic topic, CollectionRun run, NewsIssue issue,
                           Article first, Article second, Finding finding, IssueArticle representativeLink) { }
}
