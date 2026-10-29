package com.example.be.domain.companyinterest;

import com.example.be.domain.feedback.model.FeedbackModels;
import com.example.be.domain.feedback.repository.FeedbackStore;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.global.apiPayload.exception.GeneralException;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.example.be.domain.companyinterest.CompanyInterestModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"news.agent.enabled=false", "news.notifications.automation-enabled=false", "news.feedback.enabled=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CompanyInterestOracleIntegrationTests {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired CompanyInterestStore store;
    @Autowired CompanyInterestService service;
    @Autowired FeedbackStore feedback;
    @Autowired ObjectMapper json;
    @Autowired com.example.be.domain.reports.comparison.ReportComparisonCriteria comparisonCriteria;
    private final List<Long> reports = new ArrayList<>(), topics = new ArrayList<>();
    private int eventNumber;

    @AfterEach void clean() {
        for (long id : reports) {
            jdbc.update("DELETE FROM news_feedback WHERE report_id=?", id);
            jdbc.update("DELETE FROM news_reports WHERE id=?", id);
        }
        for (long id : topics) jdbc.update("DELETE FROM news_topics WHERE id=?", id);
    }

    @Test void reviewProducesOneDraftAndAppliedStatusRequiresCompletedCurrentVersionReport() {
        long topic = topic(), report = report(ReportStatus.GENERATED);
        long feedbackId = review(report, List.of(topic), "수학 풀이 성과만 다룬 소식은 관심 없습니다.", false, true);
        var draft = criterion(feedbackId);
        assertEquals(Status.DRAFT, draft.status()); assertEquals(topic, draft.topicId()); assertNull(draft.lastApplication());
        assertEquals("수학 성능 중심 소식은 제외한다.", draft.instruction());
        assertTrue(store.active(topic).isEmpty());
        var active = service.update(draft.id(), new Update(draft.version(), draft.instruction(), topic, "ACTIVE"));
        var snapshot = store.active(topic).getFirst();
        assertNull(active.lastApplication());
        long nextReport = report(ReportStatus.PENDING);
        store.recordApplication(nextReport, snapshot, 7, 2, now());
        assertNull(criterion(feedbackId).lastApplication());
        jdbc.update("UPDATE news_reports SET report_status='GENERATED',generated_at=? WHERE id=?", now(), nextReport);
        var applied = criterion(feedbackId).lastApplication();
        assertNotNull(applied); assertEquals(nextReport, applied.reportId()); assertEquals(2, applied.suppressedCount());
        assertEquals("+09:00", applied.appliedAt().getOffset().toString());
        var updated = service.update(active.id(), new Update(active.version(), "사업 연결 없는 수학 연구 소식만 제외", topic, "ACTIVE"));
        assertNull(updated.lastApplication());
        assertThrows(GeneralException.class, () -> service.update(active.id(), new Update(active.version(), "과거 문구", topic, "ACTIVE")));
        assertEquals(snapshot.instruction(), jdbc.queryForObject("SELECT instruction_snapshot FROM news_company_interest_apps WHERE report_id=?", String.class, nextReport));
        // A repeated review cannot reset the edited criterion, even if its proposal differs.
        store.propose(feedback.byId(feedbackId).orElseThrow(), result(false), now());
        assertEquals(updated.version(), criterion(feedbackId).version());
        assertEquals(updated.instruction(), criterion(feedbackId).instruction());
        var currentSnapshot = store.active(topic).getFirst();
        long mock = report(ReportStatus.MOCK);
        store.recordApplication(mock, currentSnapshot, 4, 0, now());
        assertNull(criterion(feedbackId).lastApplication());
        jdbc.update("UPDATE news_reports SET report_status='FALLBACK' WHERE id=?", mock);
        assertEquals(0, criterion(feedbackId).lastApplication().suppressedCount());
        var revoked = service.update(updated.id(), new Update(updated.version(), updated.instruction(), topic, "REVOKED"));
        assertNull(revoked.lastApplication()); assertTrue(store.active(topic).isEmpty());
        assertEquals(updated.activatedAt(), revoked.activatedAt());
    }

    @Test void comparisonReadsFrozenCriterionVersionsFromSourceDaysAndWeeklySelectionAfterCurrentPolicyChanges() {
        long topic = topic(), origin = report(ReportStatus.GENERATED);
        var draft = criterion(review(origin, List.of(topic), "순수 수학 소식은 제외", false, true));
        var active = service.update(draft.id(), new Update(draft.version(), draft.instruction(), topic, "ACTIVE"));
        var dailyVersion = store.active(topic).getFirst();
        long first = report(ReportStatus.GENERATED), second = report(ReportStatus.GENERATED);
        store.recordApplication(first, dailyVersion, 10, 2, now());
        store.recordApplication(second, dailyVersion, 12, 3, now());
        var weekly = week(List.of(first, second));
        var changed = service.update(active.id(), new Update(active.version(), "사업 연결 없는 수학 연구만 제외", topic, "ACTIVE"));
        var weeklyVersion = store.active(topic).getFirst();
        store.recordApplication(weekly.getId(), weeklyVersion, 8, 1, now());
        var expected = java.util.Set.of(
                new com.example.be.domain.reports.comparison.ReportComparisonCriteria.AppliedCriterion(
                        dailyVersion.id(), dailyVersion.version(), topic),
                new com.example.be.domain.reports.comparison.ReportComparisonCriteria.AppliedCriterion(
                        weeklyVersion.id(), weeklyVersion.version(), topic));
        assertEquals(expected, comparisonCriteria.applied(weekly));
        assertFalse(comparisonCriteria.hasMixedSourceCriteria(weekly));
        service.update(changed.id(), new Update(changed.version(), changed.instruction(), topic, "REVOKED"));
        assertTrue(store.active(topic).isEmpty());
        assertEquals(expected, comparisonCriteria.applied(weekly), "current policy changes must not rewrite comparison provenance");
        var empty = NewsReport.builder().id(report(ReportStatus.GENERATED)).build();
        assertTrue(comparisonCriteria.applied(empty).isEmpty());
        assertFalse(comparisonCriteria.hasMixedSourceCriteria(empty));
    }

    @Test void comparisonRejectsMixedDailyCriteriaEvenWhenBothWeeklyUnionsMatchAndKeepsEmptyDaysDistinct() {
        long topic = topic(), origin = report(ReportStatus.GENERATED);
        var draft = criterion(review(origin, List.of(topic), "순수 수학 소식은 제외", false, true));
        var active = service.update(draft.id(), new Update(draft.version(), draft.instruction(), topic, "ACTIVE"));
        var firstVersion = store.active(topic).getFirst();
        service.update(active.id(), new Update(active.version(), "사업 연결 없는 수학 소식만 제외", topic, "ACTIVE"));
        var secondVersion = store.active(topic).getFirst();
        long first = report(ReportStatus.GENERATED), second = report(ReportStatus.GENERATED);
        long third = report(ReportStatus.GENERATED), fourth = report(ReportStatus.GENERATED);
        store.recordApplication(first, firstVersion, 5, 0, now());
        store.recordApplication(second, secondVersion, 5, 0, now());
        store.recordApplication(third, secondVersion, 5, 0, now());
        store.recordApplication(fourth, firstVersion, 5, 0, now());
        var prior = week(List.of(first, second));
        var current = week(List.of(third, fourth));
        assertEquals(comparisonCriteria.applied(prior), comparisonCriteria.applied(current));
        assertTrue(comparisonCriteria.hasMixedSourceCriteria(prior));
        assertTrue(comparisonCriteria.hasMixedSourceCriteria(current));
        long noApplication = report(ReportStatus.GENERATED);
        assertTrue(comparisonCriteria.hasMixedSourceCriteria(week(List.of(first, noApplication))),
                "a source day without a recorded criterion must not disappear from the comparison");
        assertFalse(comparisonCriteria.hasMixedSourceCriteria(week(List.of(noApplication, report(ReportStatus.GENERATED)))));
    }

    @Test void revokingAnUnactivatedDraftDoesNotInventAnActivationTime() {
        long topic = topic(), report = report(ReportStatus.GENERATED);
        var draft = criterion(review(report, List.of(topic), "순수 수학 연구 소식은 제외", false, true));
        var revoked = service.update(draft.id(), new Update(draft.version(), draft.instruction(), topic, "REVOKED"));
        assertEquals(Status.REVOKED, revoked.status());
        assertNull(revoked.activatedAt());
    }

    @Test void legacyBackfillPreservesFullCommentAndRequiresChoiceForMultipleTopics() throws Exception {
        long first = topic(), second = topic(), report = report(ReportStatus.GENERATED);
        String fullComment = "회사 관심 의견 ".repeat(100);
        long single = review(report, List.of(first), fullComment, false, false);
        long multiple = review(report, List.of(first, second), "두 주제에 걸친 의견", false, false);
        long mock = review(report, List.of(first), "모의 의견", true, false);
        var resource = new ClassPathResource("db/migration/V65__company_interest_criteria.sql");
        String migration;
        try (var stream = resource.getInputStream()) { migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        String backfill = migration.substring(migration.indexOf("INSERT INTO news_company_interests")).strip().replaceFirst(";\\s*$", "");
        jdbc.update(backfill); jdbc.update(backfill);
        var singleDraft = criterion(single);
        assertEquals(fullComment.strip(), singleDraft.comment()); assertEquals(500, singleDraft.instruction().length());
        assertEquals(first, singleDraft.topicId()); assertEquals(Status.DRAFT, singleDraft.status());
        assertNull(criterion(multiple).topicId());
        assertTrue(service.list().criteria().stream().noneMatch(c -> c.feedbackId() == mock));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM news_company_interests WHERE feedback_id=?", Integer.class, single));
    }

    @Test void concurrentActivationsNeverExceedTwentyForTheSameTopic() throws Exception {
        long topic = topic(), report = report(ReportStatus.GENERATED);
        var candidates = new ArrayList<Criterion>();
        for (int i = 0; i < 21; i++) {
            long id = review(report, List.of(topic), "관심 의견 " + i, false, true);
            candidates.add(criterion(id));
        }
        for (var candidate : candidates.subList(0, 19))
            service.update(candidate.id(), new Update(candidate.version(), candidate.instruction(), topic, "ACTIVE"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = candidates.subList(19, 21).stream().map(candidate -> pool.submit(() -> {
                start.await();
                try { service.update(candidate.id(), new Update(candidate.version(), candidate.instruction(), topic, "ACTIVE")); return true; }
                catch (GeneralException conflict) { return false; }
            })).toList();
            start.countDown();
            assertNotEquals(attempts.getFirst().get(15, TimeUnit.SECONDS), attempts.getLast().get(15, TimeUnit.SECONDS));
        }
        assertEquals(20, store.active(topic).size());
    }

    private long review(long report, List<Long> topicIds, String comment, boolean mock, boolean propose) {
        return new TransactionTemplate(manager).execute(tx -> {
            var frozenTopics = topicIds.stream().map(id -> new FeedbackModels.Topic(id, "당시 주제 " + id, List.of("AI"), List.of())).toList();
            String key = "%064x".formatted(++eventNumber);
            var event = new FeedbackModels.EventContext(key, eventNumber, "수학 연구 성과", "요약", "의미", List.of(), frozenTopics, List.of(), null);
            var input = new FeedbackModels.Item(null, null, null, List.of(), null, null, null, null, "v1", "fixture", null, event);
            long id = feedback.submitEvent(report, input, FeedbackModels.Category.OTHER, comment.strip(), UUID.randomUUID().toString(), key, now());
            var saved = feedback.byId(id).orElseThrow();
            var result = result(mock);
            feedback.reviewed(saved, "PREFERENCE", "관심에 대한 의견", result, now());
            if (propose) store.propose(saved, result, now());
            return id;
        });
    }

    private tools.jackson.databind.JsonNode result(boolean mock) {
        return json.readTree("""
                {"verdict":"PREFERENCE","diagnosis":"관심에 대한 의견","proposedCompanyCriterion":
                {"instruction":"수학 성능 중심 소식은 제외한다.","reason":"회사 관심 표현"},"meta":{"mock":%s,"truncated":false}}
                """.formatted(mock));
    }
    private Criterion criterion(long feedbackId) { return service.list().criteria().stream().filter(c -> c.feedbackId() == feedbackId).findFirst().orElseThrow(); }
    private long topic() {
        return new TransactionTemplate(manager).execute(tx -> {
            var topic = com.example.be.domain.topics.entity.Topic.builder().name("회사 기준 주제 " + UUID.randomUUID())
                    .batchSize(100).intervalMinutes(1440).active(true).requiredKeywords(List.of()).optionalKeywords(List.of("AI")).excludedKeywords(List.of()).build();
            em.persist(topic); em.flush(); topics.add(topic.getId()); return topic.getId();
        });
    }
    private long report(ReportStatus status) {
        return new TransactionTemplate(manager).execute(tx -> {
            var report = NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2197, 1, 1).plusDays(reports.size()))
                    .reportStatus(status).title("회사 기준 보고서").markdownBody("본문").modelName("fixture").generatedAt(now()).build();
            em.persist(report); em.flush(); reports.add(report.getId()); return report.getId();
        });
    }
    private NewsReport week(List<Long> sourceIds) {
        return new TransactionTemplate(manager).execute(tx -> {
            var from = LocalDate.of(2197, 1, 1).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
                    .plusWeeks(reports.size());
            var sources = sourceIds.stream().map(id -> em.find(NewsReport.class, id))
                    .map(com.example.be.domain.reports.entity.WeeklyReportInput.DailySource::from).toList();
            var input = new com.example.be.domain.reports.entity.WeeklyReportInput(from, from.plusDays(6), sources, List.of());
            var report = NewsReport.builder().reportScope(ReportScope.WEEKLY).reportDate(from).reportEndDate(from.plusDays(6))
                    .sourceReportIds(sourceIds).sourceReportCount((long) sourceIds.size()).weeklyInput(input)
                    .reportStatus(ReportStatus.GENERATED).title("회사 기준 비교 주간 보고서").markdownBody("본문")
                    .modelName("fixture").generatedAt(now()).build();
            em.persist(report); em.flush(); reports.add(report.getId()); return report;
        });
    }

    private static LocalDateTime now() { return LocalDateTime.now(ApiTimeZone.ZONE); }
}
